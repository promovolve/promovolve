package billing

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// Fraud settlement holds and clawback (docs/design/FRAUD_PREVENTION.md
// Layer 3.1). The clearing account already models money-in-transit, so a
// "hold" is simply the absence of the publisher-side drain: the gross the
// advertiser was charged stays in clearing instead of moving to the
// publisher's payable. A fraud_holds row records the cell so the hold can
// later be released (pay the publisher normally) or clawed back (refund
// the advertiser). Both resolutions keep the reconciliation identity
// (cash = wallets + payables + revenue + clearing) exact.

// RecordFraudHold parks one publisher cell in the held state WITHOUT any
// ledger movement — the gross remains in clearing where the advertiser
// side left it. Called by the settler in place of RecordPublisherSettlement
// when the site has an open fraud flag. Idempotent per (publisher, window
// start, cell): a re-settle of the same window is a no-op.
func (s *Service) RecordFraudHold(ctx context.Context, p PubSettlementParams) (FraudHold, bool, error) {
	if p.GrossMicros <= 0 {
		return FraudHold{}, false, errors.New("billing: hold gross must be positive (skip zero cells)")
	}
	if p.PublisherID == "" {
		return FraudHold{}, false, errors.New("billing: hold requires a publisher id")
	}
	var h FraudHold
	err := s.pool.QueryRow(ctx, `
		INSERT INTO fraud_holds
			(site_id, publisher_id, campaign_id, advertiser_id,
			 window_from, window_to, local_date, timezone,
			 impressions, gross_micros, margin_bps, status)
		VALUES ($1, $2, $3, $4, $5, $6, $7::date, $8, $9, $10, $11, 'held')
		ON CONFLICT (publisher_id, window_from, site_id, campaign_id, advertiser_id)
			DO NOTHING
		RETURNING id::text, status`,
		p.SiteID, p.PublisherID, p.CampaignID, p.AdvertiserID,
		p.Window.From, p.Window.To, p.Window.LocalDate.Format("2006-01-02"), p.Window.Timezone,
		p.Impressions, p.GrossMicros, p.MarginBps,
	).Scan(&h.ID, &h.Status)
	if errors.Is(err, pgx.ErrNoRows) {
		return FraudHold{}, true, nil // already held (duplicate settle pass)
	}
	if err != nil {
		return FraudHold{}, false, err
	}
	return h, false, nil
}

// ListHeldForSite returns the still-held cells for a site (the ones a
// Release or Clawback will act on), oldest first.
func (s *Service) ListHeldForSite(ctx context.Context, siteID string) ([]FraudHold, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id::text, site_id, publisher_id, campaign_id, advertiser_id,
		       window_from, window_to, local_date, timezone,
		       impressions, gross_micros, margin_bps, status
		FROM fraud_holds
		WHERE site_id = $1 AND status = 'held'
		ORDER BY window_from ASC`, siteID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []FraudHold
	for rows.Next() {
		var h FraudHold
		if err := rows.Scan(&h.ID, &h.SiteID, &h.PublisherID, &h.CampaignID, &h.AdvertiserID,
			&h.WindowFrom, &h.WindowTo, &h.LocalDate, &h.Timezone,
			&h.Impressions, &h.GrossMicros, &h.MarginBps, &h.Status); err != nil {
			return nil, err
		}
		out = append(out, h)
	}
	return out, rows.Err()
}

// HeldTotalMicros is the sum of gross still held across all sites — the
// admin billing "held" tile. This gross is a subset of the clearing
// balance (clearing also carries benign in-transit float), so it is
// reported separately, not subtracted.
func (s *Service) HeldTotalMicros(ctx context.Context) (int64, error) {
	var total int64
	err := s.pool.QueryRow(ctx,
		`SELECT COALESCE(SUM(gross_micros), 0) FROM fraud_holds WHERE status = 'held'`,
	).Scan(&total)
	return total, err
}

// ReleaseSiteHolds resolves every held cell for a site as a false
// positive: each cell is drained from clearing to the publisher's payable
// and the platform's revenue exactly as a normal settlement would have,
// and the hold is marked released. Returns the number of cells paid.
// Idempotent — the underlying publisher settlement dedupes on its own key,
// and already-resolved holds are skipped.
func (s *Service) ReleaseSiteHolds(ctx context.Context, siteID, by string) (int, error) {
	holds, err := s.ListHeldForSite(ctx, siteID)
	if err != nil {
		return 0, err
	}
	var released int
	for _, h := range holds {
		res, err := s.RecordPublisherSettlement(ctx, PubSettlementParams{
			Window: SettlementWindow{
				From: h.WindowFrom, To: h.WindowTo, LocalDate: h.LocalDate, Timezone: h.Timezone,
			},
			PublisherID:  h.PublisherID,
			SiteID:       h.SiteID,
			CampaignID:   h.CampaignID,
			AdvertiserID: h.AdvertiserID,
			Impressions:  h.Impressions,
			GrossMicros:  h.GrossMicros,
			MarginBps:    h.MarginBps,
		})
		if err != nil {
			return released, fmt.Errorf("release hold %s: %w", h.ID, err)
		}
		if _, err := s.pool.Exec(ctx, `
			UPDATE fraud_holds
			SET status = 'released', resolved_at = NOW(), resolved_by = $2, txn_id = $3
			WHERE id = $1 AND status = 'held'`,
			h.ID, by, res.TxnID,
		); err != nil {
			return released, err
		}
		released++
	}
	return released, nil
}

// ClawbackSiteHolds resolves every held cell for a site as confirmed
// fraud: the gross is refunded from clearing back to the advertiser's
// wallet (the publisher was never paid), and the hold is marked
// clawed_back. Returns the number of cells reversed. Idempotent via
// ClawbackKey — re-confirming a site never double-refunds.
func (s *Service) ClawbackSiteHolds(ctx context.Context, siteID, by string) (int, error) {
	holds, err := s.ListHeldForSite(ctx, siteID)
	if err != nil {
		return 0, err
	}
	var clawed int
	for _, h := range holds {
		if err := s.clawbackHeld(ctx, h, by); err != nil {
			return clawed, fmt.Errorf("clawback hold %s: %w", h.ID, err)
		}
		clawed++
	}
	return clawed, nil
}

// clawbackHeld refunds one held cell's gross clearing→wallet and marks the
// hold clawed_back, in a single transaction.
func (s *Service) clawbackHeld(ctx context.Context, h FraudHold, by string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	legs := []Leg{
		{OwnerType: OwnerPlatform, OwnerID: PlatformClearing, AmountMicros: h.GrossMicros},
		{OwnerType: OwnerAdvertiser, OwnerID: h.AdvertiserID, AmountMicros: -h.GrossMicros},
	}
	memo := fmt.Sprintf("fraud clawback %s campaign %s on site %s",
		h.LocalDate.Format("2006-01-02"), h.CampaignID, h.SiteID)
	key := ClawbackKey(h.PublisherID, h.WindowFrom, h.SiteID, h.CampaignID, h.AdvertiserID)
	// Ledger createdBy is a platform_users UUID; the acting operator is
	// captured on the fraud_holds row (resolved_by) and in audit_log
	// instead, so the reversal txn is system-attributed like a settlement.
	res, err := post(ctx, tx, TxnClawback, key, memo, nil, legs)
	if err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		UPDATE fraud_holds
		SET status = 'clawed_back', resolved_at = NOW(), resolved_by = $2, txn_id = $3
		WHERE id = $1 AND status = 'held'`,
		h.ID, by, res.TxnID,
	); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ClawbackSettledWindow reverses cells that were ALREADY paid to the
// publisher before the flag landed (a Layer-2 catch that arrived after the
// site settled, so no hold was recorded). For each publisher_settlements
// row on the site within [from, to) that has not already been clawed back,
// it books one correcting transaction: payable→clearing (net) and
// revenue→clearing (fee) undo the publisher settlement, then clearing→wallet
// (gross) refunds the advertiser — net effect identical to a held
// clawback, reached from a different starting state. Idempotent via
// ClawbackKey. Returns the number of cells reversed.
//
// If the publisher has already been paid out (payable insufficient), the
// correcting entry still posts and drives payable negative — a recorded
// clawback debt the operator recovers from the next payout, exactly as the
// design intends double-entry to make an ordinary entry of it.
func (s *Service) ClawbackSettledWindow(ctx context.Context, siteID string, from, to time.Time, by string) (int, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT publisher_id, campaign_id, advertiser_id, window_from, window_to,
		       local_date, timezone, gross_micros, fee_micros, net_micros
		FROM publisher_settlements
		WHERE site_id = $1 AND window_from >= $2 AND window_from < $3
		ORDER BY window_from ASC`, siteID, from, to)
	if err != nil {
		return 0, err
	}
	type cell struct {
		pub, camp, adv, tz  string
		wFrom, wTo, localDt time.Time
		gross, fee, net     int64
	}
	var cells []cell
	for rows.Next() {
		var c cell
		if err := rows.Scan(&c.pub, &c.camp, &c.adv, &c.wFrom, &c.wTo,
			&c.localDt, &c.tz, &c.gross, &c.fee, &c.net); err != nil {
			rows.Close()
			return 0, err
		}
		cells = append(cells, c)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}

	var clawed int
	for _, c := range cells {
		tx, err := s.pool.Begin(ctx)
		if err != nil {
			return clawed, err
		}
		legs := []Leg{
			// Refund the advertiser the full gross.
			{OwnerType: OwnerAdvertiser, OwnerID: c.adv, AmountMicros: -c.gross},
		}
		// Undo the publisher settlement: pull net back out of payable and the
		// fee back out of revenue. Both return to clearing, which the gross
		// refund then drains — clearing nets to zero for the cell. Zero legs
		// are dropped (extreme margins can round net or fee to zero).
		clearingDelta := c.gross
		if c.net > 0 {
			legs = append(legs, Leg{OwnerType: OwnerPublisher, OwnerID: c.pub, AmountMicros: c.net})
			clearingDelta -= c.net
		}
		if c.fee > 0 {
			legs = append(legs, Leg{OwnerType: OwnerPlatform, OwnerID: PlatformRevenue, AmountMicros: c.fee})
			clearingDelta -= c.fee
		}
		if clearingDelta != 0 {
			legs = append(legs, Leg{OwnerType: OwnerPlatform, OwnerID: PlatformClearing, AmountMicros: clearingDelta})
		}
		memo := fmt.Sprintf("fraud clawback (settled) %s campaign %s on site %s",
			c.localDt.Format("2006-01-02"), c.camp, siteID)
		key := ClawbackKey(c.pub, c.wFrom, siteID, c.camp, c.adv)
		res, err := post(ctx, tx, TxnClawback, key, memo, nil, legs)
		if err != nil {
			tx.Rollback(ctx)
			return clawed, fmt.Errorf("clawback settled %s/%s: %w", c.camp, c.adv, err)
		}
		if err := tx.Commit(ctx); err != nil {
			return clawed, err
		}
		// A duplicate key means this cell was already clawed back (re-confirm) —
		// no legs were posted; don't count it as a fresh reversal.
		if !res.Duplicate {
			clawed++
		}
	}
	return clawed, nil
}

// SuspendedSiteFlag is one CONFIRMED-fraud flag whose site is still
// frozen. Open (pending-review) flags are deliberately excluded here —
// they live on the Fraud Review page as decisions to make, so a site is
// never listed in two places at once. A confirmed flag that's later
// resumed becomes 'released' and drops out. Read straight from the shared
// fraud_flags table.
type SuspendedSiteFlag struct {
	FlagID    int64
	SiteID    string
	Signal    string
	Severity  float64
	WindowDay time.Time
	Evidence  string
	Status    string // confirmed
	FlaggedAt time.Time
}

// ListSuspendedSites returns the flags for confirmed-fraud sites that are
// still frozen (status 'confirmed'), newest first — the "manage frozen
// sites" list on Site Requests. Pending-review (open) flags are handled on
// Fraud Review, not here. A site may appear more than once (multiple
// signals/days); the caller groups by site.
func (s *Service) ListSuspendedSites(ctx context.Context) ([]SuspendedSiteFlag, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, site_id, signal, severity, window_day, evidence, status, flagged_at
		FROM fraud_flags
		WHERE status = 'confirmed'
		ORDER BY flagged_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []SuspendedSiteFlag
	for rows.Next() {
		var f SuspendedSiteFlag
		if err := rows.Scan(&f.FlagID, &f.SiteID, &f.Signal, &f.Severity,
			&f.WindowDay, &f.Evidence, &f.Status, &f.FlaggedAt); err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

// ResolveSiteFlags marks every currently-active flag for a site (open or
// confirmed) as released — used when an operator resumes a suspended site
// from Site Requests, so a site with several flags clears in one action
// and the detector won't re-suspend it for the same day (the status guard
// blocks re-open). Returns the number of flags cleared.
func (s *Service) ResolveSiteFlags(ctx context.Context, siteID, by string) (int64, error) {
	tag, err := s.pool.Exec(ctx, `
		UPDATE fraud_flags
		SET status = 'released', resolved_at = NOW(), resolved_by = $2
		WHERE site_id = $1 AND status IN ('open', 'confirmed')`, siteID, by)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

// RestoreKey is the idempotency key for restoring one clawed-back cell —
// distinct from ClawbackKey so a restore never collides with the clawback
// it reverses, and re-running a restore is a no-op.
func RestoreKey(publisherID string, windowFrom time.Time, siteID, campaignID, advertiserID string) string {
	return fmt.Sprintf("restore:%s:%s:%s:%s:%s",
		publisherID, windowFrom.UTC().Format(time.RFC3339), siteID, campaignID, advertiserID)
}

// RestoreSiteEarnings reverses a confirmed site's clawback — the "the
// confirm was wrong, this publisher earned it" path. For every cell that
// was clawed back (fraud_holds status 'clawed_back'), it re-charges the
// advertiser the gross and credits the publisher net + platform fee in one
// balanced transaction, reaching the same end state a normal settlement
// would have. The hold is marked 'restored'. Idempotent via RestoreKey.
// Returns the number of cells restored and the total gross re-charged.
//
// Only held-path clawbacks are reversed here; the rare already-settled
// backstop (ClawbackSettledWindow, no fraud_holds row) is not auto-restored
// — that leaves a recorded clawback the operator can adjust by hand.
func (s *Service) RestoreSiteEarnings(ctx context.Context, siteID, by string) (cells int, grossMicros int64, err error) {
	rows, qerr := s.pool.Query(ctx, `
		SELECT id::text, publisher_id, campaign_id, advertiser_id,
		       window_from, local_date, gross_micros, margin_bps
		FROM fraud_holds
		WHERE site_id = $1 AND status = 'clawed_back'
		ORDER BY window_from ASC`, siteID)
	if qerr != nil {
		return 0, 0, qerr
	}
	type cell struct {
		id, pub, camp, adv string
		wFrom, localDt     time.Time
		gross              int64
		marginBps          int
	}
	var cs []cell
	for rows.Next() {
		var c cell
		if serr := rows.Scan(&c.id, &c.pub, &c.camp, &c.adv, &c.wFrom, &c.localDt,
			&c.gross, &c.marginBps); serr != nil {
			rows.Close()
			return 0, 0, serr
		}
		cs = append(cs, c)
	}
	rows.Close()
	if rows.Err() != nil {
		return 0, 0, rows.Err()
	}

	for _, c := range cs {
		net, fee := SplitFee(c.gross, c.marginBps)
		tx, terr := s.pool.Begin(ctx)
		if terr != nil {
			return cells, grossMicros, terr
		}
		// Re-charge the advertiser, pay the publisher, earn the fee — the
		// inverse of the clawback's net effect. Positive debits (advertiser
		// charged), negative credits (publisher + revenue paid). Sums to zero
		// because gross == net + fee.
		legs := []Leg{
			{OwnerType: OwnerAdvertiser, OwnerID: c.adv, AmountMicros: c.gross},
			{OwnerType: OwnerPublisher, OwnerID: c.pub, AmountMicros: -net},
			{OwnerType: OwnerPlatform, OwnerID: PlatformRevenue, AmountMicros: -fee},
		}
		memo := fmt.Sprintf("fraud restore %s campaign %s on site %s",
			c.localDt.Format("2006-01-02"), c.camp, siteID)
		key := RestoreKey(c.pub, c.wFrom, siteID, c.camp, c.adv)
		res, perr := post(ctx, tx, TxnSettlement, key, memo, nil, legs)
		if perr != nil {
			tx.Rollback(ctx)
			return cells, grossMicros, fmt.Errorf("restore %s: %w", c.id, perr)
		}
		if _, uerr := tx.Exec(ctx, `
			UPDATE fraud_holds
			SET status = 'restored', resolved_at = NOW(), resolved_by = $2, txn_id = $3
			WHERE id = $1 AND status = 'clawed_back'`,
			c.id, by, res.TxnID,
		); uerr != nil {
			tx.Rollback(ctx)
			return cells, grossMicros, uerr
		}
		if cerr := tx.Commit(ctx); cerr != nil {
			return cells, grossMicros, cerr
		}
		if !res.Duplicate {
			cells++
			grossMicros += c.gross
		}
	}
	return cells, grossMicros, nil
}

// SuspectActivityRow is one (site, reason) event count for the current
// UTC day. Reason "clean" = events with no suspect mark; the rest use the
// core's suspect_reason vocabulary (bot_ua, chain, timing, rate_cap,
// datacenter_asn).
type SuspectActivityRow struct {
	SiteID string
	Reason string
	Count  int64
}

// SuspectActivityToday reads today's per-site tracking-event counts
// grouped by suspect reason — the live Layer-0/1 view an operator sees
// BEFORE any Layer-2 flag trips. Clean traffic is included so callers can
// compute each site's suspect share. UTC day buckets, matching the
// detector's windows. Suspect events never reach the dashboard rollups
// (the projection drops them), so this reads tracking_events directly.
func (s *Service) SuspectActivityToday(ctx context.Context) ([]SuspectActivityRow, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT site_id, COALESCE(suspect_reason, 'clean') AS reason, COUNT(*)::bigint
		FROM tracking_events
		WHERE event_time >= date_trunc('day', now() AT TIME ZONE 'utc') AT TIME ZONE 'utc'
		GROUP BY site_id, COALESCE(suspect_reason, 'clean')`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []SuspectActivityRow
	for rows.Next() {
		var r SuspectActivityRow
		if err := rows.Scan(&r.SiteID, &r.Reason, &r.Count); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}
