package billing

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Settler is the daily settlement job (docs/design/BILLING.md): it pulls
// per-day metering aggregates from the core API, books them into the ledger
// with the margin rate effective on the settled day, tracks which days are
// done, and evaluates advertiser wallets afterwards (suspend when unfunded,
// resume when funded again).
//
// It ticks well inside every UTC day rather than exactly at 00:30 because
// each pass is idempotent: already-settled days are recorded in
// billing_settlement_days and every ledger posting dedupes on its
// settlement key, so re-running costs one metering call.
type Settler struct {
	svc      *Service
	pool     *pgxpool.Pool
	core     CoreClient
	marginAt func(ctx context.Context, at time.Time) (int, error)

	interval time.Duration
	// maxLookbackDays bounds catch-up to the core's tracking_events
	// retention; gaps older than this are unrecoverable and alerted.
	maxLookbackDays int
	now             func() time.Time
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

// RunOnce settles every unsettled day up to yesterday (UTC), then
// evaluates wallets. Safe to call concurrently with user actions; not
// designed for multiple replicas (the platform runs as a single pod).
func (s *Settler) RunOnce(ctx context.Context) error {
	if err := s.catchUp(ctx); err != nil {
		return err
	}
	return s.EvaluateWallets(ctx)
}

// settleFinalityLag delays settling a day until this long past its UTC
// midnight, so late-projecting tracking events land before the day is
// billed — a cell settled low can never be topped up later (its
// settlement key already exists).
const settleFinalityLag = time.Hour

// settleRetroDays re-settles this many recent already-marked days each
// pass. RecordSettlement is idempotent, so re-runs are cheap no-ops —
// what this buys is late-arriving cells: a site whose publisher mapping
// was fixed after being skipped, or a metering cell that appeared late,
// gets billed instead of being lost behind the day marker.
const settleRetroDays = 3

func (s *Settler) catchUp(ctx context.Context) error {
	latest := s.today().AddDate(0, 0, -1)
	if s.now().UTC().Sub(s.today()) < settleFinalityLag {
		// Within the finality lag after midnight: yesterday's metering may
		// still be settling into the projection — don't bill it yet.
		latest = latest.AddDate(0, 0, -1)
	}

	var first, last *time.Time
	err := s.pool.QueryRow(ctx, `SELECT MIN(day), MAX(day) FROM billing_settlement_days`).Scan(&first, &last)
	if err != nil {
		return fmt.Errorf("read settlement checkpoint: %w", err)
	}

	var start time.Time
	switch {
	case last == nil:
		// First run ever: billing starts now — settle the latest eligible
		// day only, do not retroactively charge pre-billing history.
		start = latest
		slog.Info("first settlement run; starting with the latest eligible day", "day", day(latest))
	default:
		start = last.UTC().Truncate(24*time.Hour).AddDate(0, 0, 1)
		if retro := latest.AddDate(0, 0, -(settleRetroDays - 1)); retro.Before(start) {
			start = retro // re-settle the retro window (idempotent)
		}
		// The retro window must never reach past the billing epoch: days
		// before the first-ever marker are pre-billing history and stay
		// unbilled by design.
		if epoch := first.UTC().Truncate(24 * time.Hour); start.Before(epoch) {
			start = epoch
		}
		oldest := latest.AddDate(0, 0, -(s.maxLookbackDays - 1))
		if start.Before(oldest) {
			// The gap exceeds the core's retention: those days' metering is
			// gone and CANNOT be billed. This is the alert the design doc
			// requires — the operator has lost revenue records.
			slog.Error("settlement gap exceeds metering retention; unbillable days skipped",
				"lastSettled", day(*last), "resumingFrom", day(oldest),
				"lostDays", int(oldest.Sub(start).Hours()/24))
			start = oldest
		}
	}

	for d := start; !d.After(latest); d = d.AddDate(0, 0, 1) {
		if err := s.settleDay(ctx, d, false); err != nil {
			return fmt.Errorf("settle %s: %w", day(d), err)
		}
	}
	return nil
}

// SettleDay force-settles a single UTC day, bypassing the scheduled pass's
// up-to-yesterday finality window. Idempotent (RecordSettlement's keys
// collide on re-run), so it's safe to call repeatedly. For operator /
// testing use via the `settle-day` CLI — the normal path is the daily tick.
func (s *Settler) SettleDay(ctx context.Context, d time.Time) error {
	// allowPartial=true so an in-progress day can be settled from the CLI
	// (core would otherwise reject the current day as day_not_final). Safe
	// once no further impressions will land for the day; harmless for a
	// past day (the guard only blocks current/future).
	return s.settleDay(ctx, d.UTC().Truncate(24*time.Hour), true)
}

// settleDay books one UTC day. The day marker is written only after every
// row posted, so a partial failure retries next tick and the ledger's
// idempotency keys absorb the rows that already went through.
func (s *Settler) settleDay(ctx context.Context, d time.Time, allowPartial bool) error {
	rows, err := s.core.MeteringDaily(ctx, d, allowPartial)
	if err != nil {
		return fmt.Errorf("metering: %w", err)
	}
	// The margin in effect at the END of the settled day covers the whole
	// day; the bps snapshot lands on every settlement row.
	bps, err := s.marginAt(ctx, d.AddDate(0, 0, 1))
	if err != nil {
		return fmt.Errorf("margin lookup: %w", err)
	}

	var skipped int
	for _, r := range rows {
		if r.GrossMicros <= 0 {
			continue
		}
		if r.PublisherID == "" {
			// No site→publisher mapping: there is nobody to credit. Never
			// silently swallow money — skip, alert, and count it.
			slog.Error("metering row has no publisher mapping; NOT settled",
				"day", day(d), "siteId", r.SiteID, "advertiserId", r.AdvertiserID,
				"gross", Dollars(r.GrossMicros))
			skipped++
			continue
		}
		if _, err := s.svc.RecordSettlement(ctx, SettlementParams{
			Day:          d,
			AdvertiserID: r.AdvertiserID,
			CampaignID:   r.CampaignID,
			SiteID:       r.SiteID,
			PublisherID:  r.PublisherID,
			Impressions:  r.Impressions,
			GrossMicros:  r.GrossMicros,
			MarginBps:    bps,
		}); err != nil {
			return fmt.Errorf("record settlement %s/%s/%s: %w", r.AdvertiserID, r.CampaignID, r.SiteID, err)
		}
	}

	// The health marker reads the day's totals back from daily_settlements
	// rather than this pass's counters: after a partial failure the retry
	// only posts the remainder, and pass-local counts would under-report
	// the day on the admin health panel.
	var settled int
	var grossTotal int64
	if err := s.pool.QueryRow(ctx, `
		SELECT COUNT(*), COALESCE(SUM(gross_micros), 0)
		FROM daily_settlements WHERE day = $1::date`,
		day(d),
	).Scan(&settled, &grossTotal); err != nil {
		return fmt.Errorf("aggregate settled day: %w", err)
	}

	_, err = s.pool.Exec(ctx, `
		INSERT INTO billing_settlement_days (day, rows_settled, rows_skipped, gross_micros)
		VALUES ($1::date, $2, $3, $4)
		ON CONFLICT (day) DO UPDATE SET rows_settled = $2, rows_skipped = $3,
			gross_micros = $4, settled_at = NOW()`,
		day(d), settled, skipped, grossTotal,
	)
	if err != nil {
		return fmt.Errorf("mark day settled: %w", err)
	}
	slog.Info("settled day", "day", day(d), "rows", settled, "skipped", skipped,
		"gross", Dollars(grossTotal), "marginBps", bps)
	return nil
}

// EvaluateWallets applies the wallet policy to every advertiser account
// using the PROJECTED balance — settled balance minus the unsettled spend
// core has metered since the last settled day. That is the Phase 5
// intraday check: an advertiser burning through their wallet today is
// suspended within a tick, not tomorrow at settlement, which bounds the
// prepaid overdraft to hours instead of a full day. No ledger writes here;
// the money is only booked by the daily settlement.
//
//	projected <= 0                     → suspend (core stops serving)
//	suspended and projected > 0        → resume (e.g. funded by adjustment)
//	active and projected < watermark   → low_balance (dashboard warning)
//	low_balance and projected >= mark  → active
//
// The low-balance watermark is 2 × the trailing-7-day average daily gross.
// Core calls happen before the status flips, so a failed call retries on
// the next pass instead of leaving the platform and core disagreeing.
// If the intraday metering call fails (old core image, network), the pass
// degrades to settled balances — Phase 3 behavior — rather than skipping.
func (s *Settler) EvaluateWallets(ctx context.Context) error {
	watermarks, err := s.trailingSpend(ctx)
	if err != nil {
		return fmt.Errorf("trailing spend: %w", err)
	}

	intradayOK := true
	unsettled, err := s.core.MeteringIntraday(ctx, s.unsettledSince(ctx))
	if err != nil {
		// Degraded: suspend decisions on settled balances are still safe
		// (settled <= 0 is genuinely out of money), but RESUMING without
		// fresh intraday data could un-suspend an advertiser who was
		// suspended precisely because of unsettled spend — an unbounded
		// overdraft for as long as the outage lasts. Never resume blind.
		slog.Warn("intraday metering unavailable; evaluating on settled balances only, resumes deferred", "error", err)
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

// unsettledSince returns the first day whose spend is not yet in the
// ledger: the day after the last settled day. On a fresh install (no
// checkpoint — normally impossible here since catchUp runs first) it
// falls back to today, matching first-run semantics: pre-billing history
// is never counted against wallets.
func (s *Settler) unsettledSince(ctx context.Context) time.Time {
	var last *time.Time
	if err := s.pool.QueryRow(ctx, `SELECT MAX(day) FROM billing_settlement_days`).Scan(&last); err == nil && last != nil {
		return last.UTC().Truncate(24*time.Hour).AddDate(0, 0, 1)
	}
	return s.today()
}

// ResumeServing is for handlers that just funded a suspended wallet
// (TopupResult.Reactivated): it tells core to serve again immediately
// instead of waiting for the next settlement pass.
func (s *Settler) ResumeServing(ctx context.Context, advertiserID string) error {
	return s.core.ResumeAdvertiser(ctx, advertiserID)
}

// trailingSpend returns per-advertiser settled gross over the last 7 days.
func (s *Settler) trailingSpend(ctx context.Context) (map[string]int64, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT advertiser_id, COALESCE(SUM(gross_micros), 0)
		FROM daily_settlements
		WHERE day > $1::date - INTERVAL '7 days'
		GROUP BY advertiser_id`,
		day(s.today()),
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

func (s *Settler) today() time.Time {
	return s.now().UTC().Truncate(24 * time.Hour)
}

func day(t time.Time) string { return t.UTC().Format("2006-01-02") }
