package billing

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Settler is the local-day settlement job (docs/design/BILLING.md): every
// entity — advertiser account and publisher alike — settles on ITS OWN
// local days. Each entity carries a settled_until instant cursor; whenever
// the entity's next local midnight (plus the finality lag) has passed, the
// settler books the window [settled_until, next local midnight) and
// advances the cursor. Windows chain on instants, so coverage is gapless
// and overlap-free by construction: a timezone change moves only future
// boundaries (one short/long window, booked exactly once) and DST needs no
// special-casing.
//
// The advertiser and publisher sides of the same events book at different
// times into different windows, meeting at the platform clearing account
// (see service.go). Afterwards each pass evaluates advertiser wallets
// (suspend when unfunded, resume when funded again).
//
// It ticks well inside every day rather than at each entity's midnight
// because each pass is idempotent: settled windows are recorded in
// settlement_windows and every ledger posting dedupes on its settlement
// key, so re-running costs one metering call.
type Settler struct {
	svc      *Service
	pool     *pgxpool.Pool
	core     CoreClient
	marginAt func(ctx context.Context, at time.Time) (int, error)

	// entitySuspended reports whether the org owning a core entity is
	// OPERATOR-suspended. Wallet health must never resume an advertiser the
	// operator suspended — governance outranks billing. Nil = no org layer
	// (tests): never operator-suspended.
	entitySuspended func(ctx context.Context, entityID string) (bool, error)

	// entityTimezone returns the IANA zone of the org owning a core entity
	// ("" = UTC; org-less legacy entities and lookup errors = UTC). The
	// same orgs.timezone drives budget rollover on the core, so the
	// advertiser's budget day and billing day coincide. Nil = no org layer
	// (tests): UTC.
	entityTimezone func(ctx context.Context, entityID string) (string, error)

	interval time.Duration
	// maxLookbackDays bounds catch-up to the core's tracking_events
	// retention; gaps older than this are unrecoverable and alerted.
	maxLookbackDays int
	now             func() time.Time
}

// SetEntitySuspendedLookup wires the org-suspension check (see
// entitySuspended). Called once at startup.
func (s *Settler) SetEntitySuspendedLookup(f func(ctx context.Context, entityID string) (bool, error)) {
	s.entitySuspended = f
}

// SetEntityTimezoneLookup wires the org-timezone read (see entityTimezone).
// Called once at startup.
func (s *Settler) SetEntityTimezoneLookup(f func(ctx context.Context, entityID string) (string, error)) {
	s.entityTimezone = f
}

// operatorSuspended is the nil-safe read of entitySuspended; lookup errors
// count as suspended so a flaky org read can't accidentally resume.
func (s *Settler) operatorSuspended(ctx context.Context, entityID string) bool {
	if s.entitySuspended == nil {
		return false
	}
	suspended, err := s.entitySuspended(ctx, entityID)
	if err != nil {
		slog.Error("org suspension lookup failed; treating as suspended", "entityId", entityID, "error", err)
		return true
	}
	return suspended
}

// entityZone is the nil-safe read of entityTimezone. Lookup errors fall
// back to UTC with an alert: the window still books (money must not stall
// on a flaky org read), and if the org's real zone differs the next zone
// change is just a short/long window like any operator zone change.
func (s *Settler) entityZone(ctx context.Context, entityID string) string {
	if s.entityTimezone == nil {
		return ""
	}
	tz, err := s.entityTimezone(ctx, entityID)
	if err != nil {
		slog.Error("org timezone lookup failed; using UTC", "entityId", entityID, "error", err)
		return ""
	}
	return tz
}

func NewSettler(svc *Service, pool *pgxpool.Pool, core CoreClient, marginAt func(context.Context, time.Time) (int, error)) *Settler {
	return &Settler{
		svc:             svc,
		pool:            pool,
		core:            core,
		marginAt:        marginAt,
		interval:        30 * time.Minute,
		maxLookbackDays: 30,
		now:             time.Now,
	}
}

// Run loops until ctx is cancelled: one pass immediately (catch-up after
// downtime), then one per tick.
func (s *Settler) Run(ctx context.Context) {
	slog.Info("settlement job starting", "interval", s.interval)
	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()
	for {
		if err := s.RunOnce(ctx); err != nil && !errors.Is(err, context.Canceled) {
			slog.Error("settlement pass failed; will retry next tick", "error", err)
		}
		select {
		case <-ctx.Done():
			slog.Info("settlement job stopping")
			return
		case <-ticker.C:
		}
	}
}

// RunOnce settles every entity window that has become final, replays the
// recent recorded windows for late cells, then evaluates wallets. Wallet
// evaluation runs even when settlement partially failed — one flaky
// entity must not defer suspensions for everyone else. Safe to call
// concurrently with user actions; not designed for multiple replicas
// (the platform runs as a single pod).
func (s *Settler) RunOnce(ctx context.Context) error {
	return errors.Join(s.catchUp(ctx), s.EvaluateWallets(ctx))
}

// settleFinalityLag delays settling a window until this long past its end,
// so late-projecting tracking events land before the window is billed — a
// cell settled low can never be topped up later (its settlement key
// already exists). The core's metering range guard enforces the same lag.
const settleFinalityLag = time.Hour

// settleRetroWindow: recorded windows ending within this span are replayed
// each pass. Postings are idempotent, so re-runs are cheap no-ops — what
// this buys is late-arriving cells: a site whose publisher mapping was
// fixed after being skipped, or a metering cell that appeared late, gets
// billed instead of being lost behind the window marker. Entity discovery
// looks back the same span so a publisher first seen through a late
// mapping fix still gets windows covering those events.
const settleRetroWindow = 72 * time.Hour

// cursor is one settlement_cursors row.
type cursor struct {
	ownerType    OwnerType
	ownerID      string
	settledUntil time.Time
}

func (s *Settler) catchUp(ctx context.Context) error {
	now := s.now()

	// Discover entities with billable traffic that have no cursor yet and
	// give them a genesis cursor at the local midnight before their first
	// billable event — nothing pre-genesis is ever billed (billing starts
	// at install/reset, bounded by the discovery lookback), and nothing
	// post-genesis can fall outside every window. Discovery failing must
	// not stall entities that already have cursors.
	advs, pubs, err := s.core.MeteringEntities(ctx, now.Add(-settleRetroWindow))
	if err != nil {
		slog.Warn("entity discovery unavailable; settling known cursors only", "error", err)
	} else {
		if err := s.ensureCursors(ctx, OwnerAdvertiser, advs); err != nil {
			return fmt.Errorf("genesis advertiser cursors: %w", err)
		}
		if err := s.ensureCursors(ctx, OwnerPublisher, pubs); err != nil {
			return fmt.Errorf("genesis publisher cursors: %w", err)
		}
	}

	cursors, err := s.loadCursors(ctx)
	if err != nil {
		return fmt.Errorf("read settlement cursors: %w", err)
	}

	// One failing entity must not stall the others; collect and report.
	var errs []error
	for _, c := range cursors {
		if err := s.settleEntityDue(ctx, c, now, false); err != nil {
			errs = append(errs, fmt.Errorf("settle %s %s: %w", c.ownerType, c.ownerID, err))
		}
	}

	if err := s.replayRecentWindows(ctx, now); err != nil {
		errs = append(errs, fmt.Errorf("retro replay: %w", err))
	}
	return errors.Join(errs...)
}

// ensureCursors genesis-inserts cursors for discovered entities. Existing
// cursors win (ON CONFLICT DO NOTHING) — a cursor, once set, only ever
// moves forward through settled windows.
func (s *Settler) ensureCursors(ctx context.Context, ownerType OwnerType, earliest map[string]time.Time) error {
	for id, first := range earliest {
		tz := s.entityZone(ctx, id)
		genesis := localDayStart(first, loadZone(tz))
		tag, err := s.pool.Exec(ctx, `
			INSERT INTO settlement_cursors (owner_type, owner_id, settled_until, timezone)
			VALUES ($1, $2, $3, $4)
			ON CONFLICT (owner_type, owner_id) DO NOTHING`,
			ownerType, id, genesis, tz,
		)
		if err != nil {
			return err
		}
		if tag.RowsAffected() > 0 {
			slog.Info("settlement cursor created", "ownerType", ownerType, "ownerId", id,
				"genesis", genesis.Format(time.RFC3339), "timezone", tz)
		}
	}
	return nil
}

func (s *Settler) loadCursors(ctx context.Context) ([]cursor, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT owner_type, owner_id, settled_until FROM settlement_cursors
		ORDER BY owner_type, owner_id`,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []cursor
	for rows.Next() {
		var c cursor
		if err := rows.Scan(&c.ownerType, &c.ownerID, &c.settledUntil); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// settleEntityDue books every window of one entity that has become final,
// advancing the cursor window by window. throughNow additionally settles
// the partial window [cursor, now) — the settle-entity operator/test path.
func (s *Settler) settleEntityDue(ctx context.Context, c cursor, now time.Time, throughNow bool) error {
	tz := s.entityZone(ctx, c.ownerID)
	loc := loadZone(tz)
	cur := c.settledUntil

	// Retention clamp: a cursor stalled past the core's tracking_events
	// retention cannot be caught up — that metering is gone and CANNOT be
	// billed. This is the alert the design doc requires: the operator has
	// lost revenue records.
	if oldest := now.AddDate(0, 0, -(s.maxLookbackDays - 1)); cur.Before(oldest) {
		// Windows chain on instants, so resuming mid-day is fine: the first
		// window after the clamp is a partial local day, correctly labeled.
		resume := oldest
		slog.Error("settlement gap exceeds metering retention; unbillable span skipped",
			"ownerType", c.ownerType, "ownerId", c.ownerID,
			"settledUntil", cur.Format(time.RFC3339), "resumingFrom", resume.Format(time.RFC3339),
			"lostDays", int(resume.Sub(cur).Hours()/24))
		if _, err := s.pool.Exec(ctx, `
			UPDATE settlement_cursors SET settled_until = $3, updated_at = NOW()
			WHERE owner_type = $1 AND owner_id = $2 AND settled_until < $3`,
			c.ownerType, c.ownerID, resume,
		); err != nil {
			return fmt.Errorf("clamp cursor: %w", err)
		}
		cur = resume
	}

	for {
		// A recorded window starting at the cursor wins over a freshly
		// computed boundary: if a previous pass wrote the window marker but
		// crashed before advancing the cursor, and the zone changed since,
		// recomputing the end from the new zone would slice the same events
		// differently under colliding keys. Recorded windows are the
		// authority (same rule as the retro replay).
		next, found, err := s.recordedWindowEnd(ctx, c.ownerType, c.ownerID, cur)
		if err != nil {
			return err
		}
		if !found {
			next = nextMidnightAfter(cur, loc)
		}
		if now.Before(next.Add(settleFinalityLag)) {
			break
		}
		if err := s.settleWindow(ctx, c.ownerType, c.ownerID, cur, next, tz, false); err != nil {
			return err
		}
		cur = next
	}

	if throughNow && cur.Before(now) {
		if err := s.settleWindow(ctx, c.ownerType, c.ownerID, cur, now, tz, true); err != nil {
			return err
		}
	}
	return nil
}

func (s *Settler) recordedWindowEnd(ctx context.Context, ownerType OwnerType, ownerID string, from time.Time) (time.Time, bool, error) {
	var to time.Time
	err := s.pool.QueryRow(ctx, `
		SELECT window_to FROM settlement_windows
		WHERE owner_type = $1 AND owner_id = $2 AND window_from = $3`,
		ownerType, ownerID, from,
	).Scan(&to)
	if errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, false, nil
	}
	if err != nil {
		return time.Time{}, false, err
	}
	return to, true, nil
}

// replayRecentWindows re-settles recorded windows ending within the retro
// span, using their RECORDED boundaries. Cells that already posted dedupe
// on their keys; only late arrivals book. Windows settled within the last
// tick are skipped — nothing can have arrived late for them yet.
func (s *Settler) replayRecentWindows(ctx context.Context, now time.Time) error {
	rows, err := s.pool.Query(ctx, `
		SELECT owner_type, owner_id, window_from, window_to, timezone
		FROM settlement_windows
		WHERE window_to > $1 AND settled_at < $2
		ORDER BY owner_type, owner_id, window_from`,
		now.Add(-settleRetroWindow), now.Add(-s.interval),
	)
	if err != nil {
		return err
	}
	type window struct {
		ownerType OwnerType
		ownerID   string
		from, to  time.Time
		tz        string
	}
	var windows []window
	for rows.Next() {
		var w window
		if err := rows.Scan(&w.ownerType, &w.ownerID, &w.from, &w.to, &w.tz); err != nil {
			rows.Close()
			return err
		}
		windows = append(windows, w)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	var errs []error
	for _, w := range windows {
		if err := s.settleWindow(ctx, w.ownerType, w.ownerID, w.from, w.to, w.tz, false); err != nil {
			errs = append(errs, fmt.Errorf("replay %s %s @ %s: %w",
				w.ownerType, w.ownerID, w.from.Format(time.RFC3339), err))
		}
	}
	return errors.Join(errs...)
}

// SettleEntity force-settles one entity: all due windows, plus — when
// throughNow is set — the in-progress partial window up to now
// (allowPartial on the metering side). Idempotent, so it's safe to call
// repeatedly. For operator/testing use via the settle-entity CLI — the
// normal path is the tick. The entity gets a genesis cursor at its most
// recent local midnight if it has none.
func (s *Settler) SettleEntity(ctx context.Context, ownerType OwnerType, ownerID string, throughNow bool) error {
	if ownerType != OwnerAdvertiser && ownerType != OwnerPublisher {
		return fmt.Errorf("billing: settle-entity owner must be advertiser or publisher, got %q", ownerType)
	}
	now := s.now()
	tz := s.entityZone(ctx, ownerID)
	genesis := localDayStart(now, loadZone(tz))
	if _, err := s.pool.Exec(ctx, `
		INSERT INTO settlement_cursors (owner_type, owner_id, settled_until, timezone)
		VALUES ($1, $2, $3, $4)
		ON CONFLICT (owner_type, owner_id) DO NOTHING`,
		ownerType, ownerID, genesis, tz,
	); err != nil {
		return fmt.Errorf("ensure cursor: %w", err)
	}
	var until time.Time
	if err := s.pool.QueryRow(ctx, `
		SELECT settled_until FROM settlement_cursors WHERE owner_type = $1 AND owner_id = $2`,
		ownerType, ownerID,
	).Scan(&until); err != nil {
		return fmt.Errorf("read cursor: %w", err)
	}
	return s.settleEntityDue(ctx, cursor{ownerType: ownerType, ownerID: ownerID, settledUntil: until}, now, throughNow)
}

// settleWindow books one entity-local window. The window marker is written
// only after every cell posted, so a partial failure retries next tick and
// the ledger's idempotency keys absorb the cells that already went
// through; the cursor advances last (and only forward).
func (s *Settler) settleWindow(ctx context.Context, ownerType OwnerType, ownerID string, from, to time.Time, tz string, allowPartial bool) error {
	rows, err := s.core.MeteringRange(ctx, ownerType, ownerID, from, to, allowPartial)
	if err != nil {
		return fmt.Errorf("metering: %w", err)
	}
	loc := loadZone(tz)
	window := SettlementWindow{From: from, To: to, LocalDate: localDayStart(from, loc), Timezone: tz}
	localDay := window.LocalDate.In(loc).Format("2006-01-02")

	var skipped int
	switch ownerType {
	case OwnerAdvertiser:
		for _, r := range rows {
			if r.GrossMicros <= 0 {
				continue
			}
			if r.PublisherID == "" {
				// No site→publisher mapping: nobody is billed — same rule on
				// both sides, so the clearing account stays clean. Alert and
				// count; the retro replay books the cell once the mapping is
				// fixed (within the retro span).
				slog.Error("metering cell has no publisher mapping; NOT settled",
					"advertiserId", ownerID, "siteId", r.SiteID, "localDay", localDay,
					"gross", Dollars(r.GrossMicros))
				skipped++
				continue
			}
			if _, err := s.svc.RecordAdvertiserSettlement(ctx, AdvSettlementParams{
				Window:       window,
				AdvertiserID: ownerID,
				CampaignID:   r.CampaignID,
				SiteID:       r.SiteID,
				PublisherID:  r.PublisherID,
				Impressions:  r.Impressions,
				GrossMicros:  r.GrossMicros,
			}); err != nil {
				return fmt.Errorf("record advertiser settlement %s/%s: %w", r.CampaignID, r.SiteID, err)
			}
		}
	case OwnerPublisher:
		// The margin in effect at the END of the window covers the whole
		// window; the bps snapshot lands on every settlement row.
		bps, err := s.marginAt(ctx, to)
		if err != nil {
			return fmt.Errorf("margin lookup: %w", err)
		}
		for _, r := range rows {
			if r.GrossMicros <= 0 {
				continue
			}
			if _, err := s.svc.RecordPublisherSettlement(ctx, PubSettlementParams{
				Window:       window,
				PublisherID:  ownerID,
				SiteID:       r.SiteID,
				CampaignID:   r.CampaignID,
				AdvertiserID: r.AdvertiserID,
				Impressions:  r.Impressions,
				GrossMicros:  r.GrossMicros,
				MarginBps:    bps,
			}); err != nil {
				return fmt.Errorf("record publisher settlement %s/%s: %w", r.SiteID, r.CampaignID, err)
			}
		}
	default:
		return fmt.Errorf("billing: cannot settle owner type %q", ownerType)
	}

	// The window marker reads its totals back from the settlement table
	// rather than this pass's counters: after a partial failure the retry
	// only posts the remainder, and pass-local counts would under-report
	// the window on the admin health panel.
	table := "advertiser_settlements"
	idCol := "advertiser_id"
	if ownerType == OwnerPublisher {
		table = "publisher_settlements"
		idCol = "publisher_id"
	}
	var settled int
	var grossTotal int64
	if err := s.pool.QueryRow(ctx, `
		SELECT COUNT(*), COALESCE(SUM(gross_micros), 0)
		FROM `+table+` WHERE `+idCol+` = $1 AND window_from = $2`,
		ownerID, from,
	).Scan(&settled, &grossTotal); err != nil {
		return fmt.Errorf("aggregate settled window: %w", err)
	}

	if _, err := s.pool.Exec(ctx, `
		INSERT INTO settlement_windows
			(owner_type, owner_id, window_from, window_to, local_date, timezone,
			 rows_settled, rows_skipped, gross_micros)
		VALUES ($1, $2, $3, $4, $5::date, $6, $7, $8, $9)
		ON CONFLICT (owner_type, owner_id, window_from) DO UPDATE SET
			window_to = $4, local_date = $5::date, timezone = $6,
			rows_settled = $7, rows_skipped = $8, gross_micros = $9, settled_at = NOW()`,
		ownerType, ownerID, from, to, localDay, tz, settled, skipped, grossTotal,
	); err != nil {
		return fmt.Errorf("mark window settled: %w", err)
	}

	// Advance the cursor — only ever forward, so replaying an old recorded
	// window can never rewind it.
	if _, err := s.pool.Exec(ctx, `
		UPDATE settlement_cursors SET settled_until = $3, timezone = $4, updated_at = NOW()
		WHERE owner_type = $1 AND owner_id = $2 AND settled_until < $3`,
		ownerType, ownerID, to, tz,
	); err != nil {
		return fmt.Errorf("advance cursor: %w", err)
	}

	slog.Info("settled window", "ownerType", ownerType, "ownerId", ownerID,
		"localDay", localDay, "from", from.Format(time.RFC3339), "to", to.Format(time.RFC3339),
		"rows", settled, "skipped", skipped, "gross", Dollars(grossTotal))
	return nil
}

// EvaluateWallets applies the wallet policy to every advertiser account
// using the PROJECTED balance — settled balance minus the unsettled spend
// core has metered since the advertiser's OWN cursor (per-advertiser: a
// shared since would double-count spend already settled for advertisers
// with newer cursors). An advertiser burning through their wallet today is
// suspended within a tick, not tomorrow at settlement, which bounds the
// prepaid overdraft to hours instead of a full day. No ledger writes here;
// the money is only booked by window settlement.
//
//	projected <= 0                     → suspend (core stops serving)
//	suspended and projected > 0        → resume (e.g. funded by adjustment)
//	active and projected < watermark   → low_balance (dashboard warning)
//	low_balance and projected >= mark  → active
//
// The low-balance watermark is 2 × the trailing-7-day average daily gross.
// Core calls happen before the status flips, so a failed call retries on
// the next pass instead of leaving the platform and core disagreeing.
// If the unsettled metering call fails (old core image, network), the pass
// degrades to settled balances rather than skipping.
func (s *Settler) EvaluateWallets(ctx context.Context) error {
	watermarks, err := s.trailingSpend(ctx)
	if err != nil {
		return fmt.Errorf("trailing spend: %w", err)
	}

	since, err := s.unsettledSince(ctx)
	if err != nil {
		return fmt.Errorf("unsettled cursors: %w", err)
	}

	intradayOK := true
	unsettled, err := s.core.MeteringUnsettled(ctx, since)
	if err != nil {
		// Degraded: suspend decisions on settled balances are still safe
		// (settled <= 0 is genuinely out of money), but RESUMING without
		// fresh unsettled data could un-suspend an advertiser who was
		// suspended precisely because of unsettled spend — an unbounded
		// overdraft for as long as the outage lasts. Never resume blind.
		slog.Warn("unsettled metering unavailable; evaluating on settled balances only, resumes deferred", "error", err)
		unsettled = map[string]int64{}
		intradayOK = false
	}

	rows, err := s.pool.Query(ctx, `
		SELECT owner_id, balance_micros, status FROM billing_accounts
		WHERE owner_type = 'advertiser'`,
	)
	if err != nil {
		return err
	}
	type wallet struct {
		id      string
		balance int64
		status  AccountStatus
	}
	var wallets []wallet
	for rows.Next() {
		var w wallet
		if err := rows.Scan(&w.id, &w.balance, &w.status); err != nil {
			rows.Close()
			return err
		}
		wallets = append(wallets, w)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	// Advertisers with unsettled spend but NO wallet row yet (a brand-new
	// advertiser's account is only created by the first ledger touch)
	// must be evaluated too — otherwise an unfunded advertiser serves for
	// up to a day until the first settlement creates their negative
	// account, instead of pausing within one tick. Prepaid means prepaid
	// from impression one.
	known := make(map[string]bool, len(wallets))
	for _, w := range wallets {
		known[w.id] = true
	}
	for id, spend := range unsettled {
		if !known[id] && spend > 0 {
			wallets = append(wallets, wallet{id: id, balance: 0, status: StatusActive})
		}
	}

	for _, w := range wallets {
		watermark := 2 * watermarks[w.id] / 7
		projected := w.balance - unsettled[w.id]
		switch {
		case projected <= 0 && w.status != StatusSuspended:
			// The wallet snapshot is unlocked and core calls are slow: a
			// top-up may have landed since the read. Re-check the live
			// balance so a just-funded advertiser isn't suspended for a
			// tick right after paying.
			if acc, err := s.svc.GetAccount(ctx, OwnerAdvertiser, w.id); err == nil &&
				acc.BalanceMicros-unsettled[w.id] > 0 {
				continue
			}
			if err := s.core.SuspendAdvertiser(ctx, w.id); err != nil {
				slog.Error("core suspend failed; will retry next pass", "advertiserId", w.id, "error", err)
				continue
			}
			if _, err := s.svc.EnsureAccount(ctx, OwnerAdvertiser, w.id); err != nil {
				return err
			}
			if err := s.svc.SetAccountStatus(ctx, OwnerAdvertiser, w.id, StatusSuspended); err != nil {
				return err
			}
			slog.Warn("advertiser suspended: wallet unfunded",
				"advertiserId", w.id, "settled", Dollars(w.balance),
				"unsettled", Dollars(unsettled[w.id]), "projected", Dollars(projected))

		case projected > 0 && w.status == StatusSuspended:
			if !intradayOK {
				continue // never resume on stale data (see above)
			}
			if s.operatorSuspended(ctx, w.id) {
				// Funded wallet, but the operator suspended the company —
				// governance outranks billing. Stays benched until the
				// operator resumes the org (which re-runs the core resume).
				continue
			}
			if err := s.core.ResumeAdvertiser(ctx, w.id); err != nil {
				slog.Error("core resume failed; will retry next pass", "advertiserId", w.id, "error", err)
				continue
			}
			if err := s.svc.SetAccountStatus(ctx, OwnerAdvertiser, w.id, StatusActive); err != nil {
				return err
			}
			slog.Info("advertiser resumed: wallet funded", "advertiserId", w.id, "projected", Dollars(projected))

		case w.status == StatusActive && watermark > 0 && projected < watermark:
			if err := s.svc.SetAccountStatus(ctx, OwnerAdvertiser, w.id, StatusLowBalance); err != nil {
				return err
			}
			slog.Info("advertiser low balance", "advertiserId", w.id,
				"projected", Dollars(projected), "watermark", Dollars(watermark))

		case w.status == StatusLowBalance && projected >= watermark:
			if err := s.svc.SetAccountStatus(ctx, OwnerAdvertiser, w.id, StatusActive); err != nil {
				return err
			}
		}
	}
	return nil
}

// unsettledSince maps every advertiser with a settlement cursor to that
// cursor: spend after it is not yet in the ledger. Advertisers without a
// cursor have no billable traffic yet (discovery creates cursors before
// wallet evaluation runs), so they are correctly absent — zero unsettled.
func (s *Settler) unsettledSince(ctx context.Context) (map[string]time.Time, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT owner_id, settled_until FROM settlement_cursors WHERE owner_type = 'advertiser'`,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]time.Time{}
	for rows.Next() {
		var id string
		var until time.Time
		if err := rows.Scan(&id, &until); err != nil {
			return nil, err
		}
		out[id] = until
	}
	return out, rows.Err()
}

// ResumeServing is for handlers that just funded a suspended wallet
// (TopupResult.Reactivated): it tells core to serve again immediately
// instead of waiting for the next settlement pass. A no-op while the
// owning org is operator-suspended — a top-up must not un-bench a
// suspended company.
func (s *Settler) ResumeServing(ctx context.Context, advertiserID string) error {
	if s.operatorSuspended(ctx, advertiserID) {
		slog.Info("top-up resume skipped: org operator-suspended", "advertiserId", advertiserID)
		return nil
	}
	return s.core.ResumeAdvertiser(ctx, advertiserID)
}

// trailingSpend returns per-advertiser settled gross over the last 7 days
// of wall clock (window end within 7 days of now — an approximation for
// the watermark, not an accounting figure).
func (s *Settler) trailingSpend(ctx context.Context) (map[string]int64, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT advertiser_id, COALESCE(SUM(gross_micros), 0)
		FROM advertiser_settlements
		WHERE window_to > $1
		GROUP BY advertiser_id`,
		s.now().Add(-7*24*time.Hour),
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int64{}
	for rows.Next() {
		var id string
		var sum int64
		if err := rows.Scan(&id, &sum); err != nil {
			return nil, err
		}
		out[id] = sum
	}
	return out, rows.Err()
}

// loadZone parses an IANA zone name; "" and unknown ids resolve to UTC
// (mirror of the core's Timezones.zoneOf — total, never errors).
func loadZone(tz string) *time.Location {
	if tz == "" {
		return time.UTC
	}
	loc, err := time.LoadLocation(tz)
	if err != nil {
		return time.UTC
	}
	return loc
}

// localDayStart is the zone-midnight at or before t.
func localDayStart(t time.Time, loc *time.Location) time.Time {
	d := t.In(loc)
	return time.Date(d.Year(), d.Month(), d.Day(), 0, 0, 0, 0, loc)
}

// nextMidnightAfter is the zone-midnight strictly after t — the settlement
// window end (mirror of the core's Timezones.nextMidnightAfter). time.Date
// normalizes DST-skipped midnights the same way java.time's atStartOfDay
// resolves them: the window is simply 23 or 25 hours long.
func nextMidnightAfter(t time.Time, loc *time.Location) time.Time {
	return localDayStart(t, loc).AddDate(0, 0, 1)
}
