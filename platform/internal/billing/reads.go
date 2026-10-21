package billing

// Read-side queries for the dashboard pages (Phase 4). Nothing here writes
// to the ledger.

import (
	"context"
	"errors"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/hanishi/promovolve/platform/internal/currency"
)

// SettlementCursorHealth is one entity's settlement cursor as shown on the
// admin health panel. Behind means the entity's next window has been final
// for a while (past the lag plus a grace tick) yet remains unsettled — the
// job is stuck or erroring for this entity.
type SettlementCursorHealth struct {
	OwnerType    OwnerType
	OwnerID      string
	SettledUntil time.Time
	Timezone     string
	UpdatedAt    time.Time
	Behind       bool
}

// SettlementWindowRow is one row of the settled-window journal.
type SettlementWindowRow struct {
	OwnerType   OwnerType
	OwnerID     string
	WindowFrom  time.Time
	WindowTo    time.Time
	LocalDate   time.Time
	Timezone    string
	RowsSettled int
	RowsSkipped int
	GrossMicros int64
	SettledAt   time.Time
}

// SettlementCursors returns every entity's settlement cursor with its
// freshness flag — the admin dashboard's settlement-health panel. Empty
// means the job has never seen billable traffic (fresh install).
func (s *Service) SettlementCursors(ctx context.Context) ([]SettlementCursorHealth, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT owner_type, owner_id, settled_until, timezone, updated_at
		FROM settlement_cursors ORDER BY settled_until ASC, owner_type, owner_id`,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	now := time.Now()
	var out []SettlementCursorHealth
	for rows.Next() {
		var c SettlementCursorHealth
		if err := rows.Scan(&c.OwnerType, &c.OwnerID, &c.SettledUntil, &c.Timezone, &c.UpdatedAt); err != nil {
			return nil, err
		}
		due := nextMidnightAfter(c.SettledUntil, loadZone(c.Timezone)).Add(settleFinalityLag + 2*time.Hour)
		c.Behind = due.Before(now)
		out = append(out, c)
	}
	return out, rows.Err()
}

// RecentSettlementWindows returns the latest settled windows, newest
// first — the journal half of the health panel.
func (s *Service) RecentSettlementWindows(ctx context.Context, limit int) ([]SettlementWindowRow, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT owner_type, owner_id, window_from, window_to, local_date, timezone,
		       rows_settled, rows_skipped, gross_micros, settled_at
		FROM settlement_windows ORDER BY settled_at DESC, window_from DESC LIMIT $1`,
		limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []SettlementWindowRow
	for rows.Next() {
		var w SettlementWindowRow
		if err := rows.Scan(&w.OwnerType, &w.OwnerID, &w.WindowFrom, &w.WindowTo, &w.LocalDate,
			&w.Timezone, &w.RowsSettled, &w.RowsSkipped, &w.GrossMicros, &w.SettledAt); err != nil {
			return nil, err
		}
		out = append(out, w)
	}
	return out, rows.Err()
}

// ListAccounts returns every advertiser wallet and publisher payable —
// the admin accounts index. Platform singletons are excluded.
func (s *Service) ListAccounts(ctx context.Context) ([]Account, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id::text, owner_type, owner_id, currency, balance_micros, status, created_at
		FROM billing_accounts
		WHERE owner_type IN ('advertiser', 'publisher')
		ORDER BY owner_type, balance_micros DESC, owner_id`,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Account
	for rows.Next() {
		var a Account
		if err := rows.Scan(&a.ID, &a.OwnerType, &a.OwnerID, &a.Currency,
			&a.BalanceMicros, &a.Status, &a.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// PayoutQueueEntry is one publisher owed money, with their payout threshold.
type PayoutQueueEntry struct {
	PublisherID     string
	PayableMicros   int64
	MinPayoutMicros int64
	Method          string // empty when the publisher has not set one up
	OverThreshold   bool
}

// BaseCurrencyKey is the platform_settings row holding the deployment's
// currency code, seeded by the setup wizard inside its guarded install
// transaction. There is deliberately no setter: a ledger amount is a bare
// int64 with no currency attached, so it only means anything in the currency
// it was booked in, and changing this after any money has moved would
// silently reinterpret history.
const BaseCurrencyKey = "base_currency"

// BaseCurrency is the currency every amount in this deployment is
// denominated in. A missing row means USD — that is what every deployment
// installed before this setting existed has in its ledger.
//
// An unrecognised stored code is returned as an error rather than degraded to
// USD: rendering a yen ledger with a dollar sign is worse than refusing to
// start.
func (s *Service) BaseCurrency(ctx context.Context) (currency.Currency, error) {
	var code string
	err := s.pool.QueryRow(ctx,
		`SELECT value FROM platform_settings WHERE key = $1`, BaseCurrencyKey,
	).Scan(&code)
	if errors.Is(err, pgx.ErrNoRows) {
		return currency.USD, nil
	}
	if err != nil {
		return currency.USD, err
	}
	return currency.Get(code)
}

// Starting-floor settings. These exist because a floor is the one number
// that cannot be given a currency-neutral default: $0.50 is a sensible CPM
// reserve and ¥0.50 is no reserve at all, 157x below market. Rather than ship
// a table of per-currency guesses, the setup wizard asks the operator — who
// knows their own market's rates — and every new site is seeded from these.
const (
	FloorCpmKey    = "floor_cpm_micros"
	MinFloorCpmKey = "min_floor_cpm_micros"
)

// FloorCpmMicros / MinFloorCpmMicros are the seeds for a newly registered
// site: where its floor starts, and the level the sweep may never probe
// below. Unset degrades to the dollar-scale defaults the core already uses,
// which is correct for a USD deployment and the only safe guess otherwise.
func (s *Service) FloorCpmMicros(ctx context.Context) (int64, error) {
	return s.floorSetting(ctx, FloorCpmKey, DefaultFloorCpmMicros)
}

func (s *Service) MinFloorCpmMicros(ctx context.Context) (int64, error) {
	return s.floorSetting(ctx, MinFloorCpmKey, DefaultMinFloorCpmMicros)
}

func (s *Service) floorSetting(ctx context.Context, key string, fallback int64) (int64, error) {
	var v int64
	err := s.pool.QueryRow(ctx,
		`SELECT value::bigint FROM platform_settings WHERE key = $1`, key,
	).Scan(&v)
	// Order matters: a failed Scan leaves v at zero, so testing `v <= 0` first
	// would turn any database error into a silent dollar-scale default. Only a
	// genuinely missing row degrades to the fallback; a real error propagates,
	// and the caller keeps the site's floors rather than seeding 0.50/0.10 on
	// a currency where that is no reserve at all.
	if errors.Is(err, pgx.ErrNoRows) {
		return fallback, nil
	}
	if err != nil {
		return 0, err
	}
	if v <= 0 {
		return fallback, nil
	}
	return v, nil
}

// Dollar-scale last-resort fallbacks, matching the constants the Scala core
// falls back to (FloorSweepOptimizer's _currentFloor / _minFloor). Correct
// for USD, and only ever reached when the setting is missing.
const (
	// DefaultMinPayoutMicros applies when a publisher has no payout_methods
	// row and the operator has not configured a platform floor.
	DefaultMinPayoutMicros   = 50_000_000 // $50
	DefaultFloorCpmMicros    = 500_000    // $0.50
	DefaultMinFloorCpmMicros = 100_000    // $0.10
)

// PayoutFloorKey is the platform_settings row holding the floor; exported
// so the setup wizard can seed it inside its guarded install transaction.
const PayoutFloorKey = "payout_floor_micros"

// PayoutFloorMicros is the operator-wide minimum payout: publishers may
// raise their own threshold above it, never below (payouts cost the
// operator a transfer fee and manual work, so the floor is theirs to set).
// Unset degrades to DefaultMinPayoutMicros.
func (s *Service) PayoutFloorMicros(ctx context.Context) (int64, error) {
	var v int64
	err := s.pool.QueryRow(ctx,
		`SELECT value::bigint FROM platform_settings WHERE key = $1`, PayoutFloorKey,
	).Scan(&v)
	if errors.Is(err, pgx.ErrNoRows) {
		return DefaultMinPayoutMicros, nil
	}
	return v, err
}

func (s *Service) SetPayoutFloorMicros(ctx context.Context, micros int64, updatedBy string) error {
	if micros <= 0 {
		return errors.New("billing: payout floor must be positive")
	}
	_, err := s.pool.Exec(ctx, `
		INSERT INTO platform_settings (key, value, updated_by, updated_at)
		VALUES ($1, $2, $3, NOW())
		ON CONFLICT (key) DO UPDATE SET value = $2, updated_by = $3, updated_at = NOW()`,
		PayoutFloorKey, strconv.FormatInt(micros, 10), optional(updatedBy),
	)
	return err
}

// PayoutQueue lists every publisher with a positive payable, largest first,
// flagging who is over their payout threshold — the admin payout queue.
// The effective threshold is max(publisher preference, platform floor); a
// publisher who never configured a preference gets exactly the floor (the
// $50 package default only applies when the operator never set a floor).
func (s *Service) PayoutQueue(ctx context.Context) ([]PayoutQueueEntry, error) {
	floor, err := s.PayoutFloorMicros(ctx)
	if err != nil {
		return nil, err
	}
	rows, err := s.pool.Query(ctx, `
		SELECT a.owner_id, a.balance_micros,
		       GREATEST(COALESCE(m.min_payout_micros, 0), $1::bigint), COALESCE(m.method, '')
		FROM billing_accounts a
		LEFT JOIN payout_methods m ON m.publisher_id = a.owner_id
		WHERE a.owner_type = 'publisher' AND a.balance_micros > 0
		ORDER BY a.balance_micros DESC`,
		floor,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PayoutQueueEntry
	for rows.Next() {
		var e PayoutQueueEntry
		if err := rows.Scan(&e.PublisherID, &e.PayableMicros, &e.MinPayoutMicros, &e.Method); err != nil {
			return nil, err
		}
		e.OverThreshold = e.PayableMicros >= e.MinPayoutMicros
		out = append(out, e)
	}
	return out, rows.Err()
}

// AccountActivity is one ledger transaction as seen from one account's
// perspective: AmountMicros is the change to that account's natural
// balance (positive = balance went up).
type AccountActivity struct {
	TxnID        string
	Kind         TxnKind
	Memo         string
	AmountMicros int64
	CreatedAt    time.Time
}

// ListAccountActivity returns the transactions touching an owner's account,
// newest first — top-ups, refunds, and adjustments for statements. (Daily
// settlements are better read via ListSettlements, which carries the
// campaign/site breakdown, so they are excluded here.)
func (s *Service) ListAccountActivity(ctx context.Context, ownerType OwnerType, ownerID string, limit int) ([]AccountActivity, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT t.id::text, t.kind, t.memo, e.amount_micros, t.created_at
		FROM ledger_entries e
		JOIN billing_accounts a ON a.id = e.account_id
		JOIN ledger_transactions t ON t.id = e.txn_id
		WHERE a.owner_type = $1 AND a.owner_id = $2 AND t.kind <> 'settlement'
		ORDER BY t.created_at DESC, t.id LIMIT $3`,
		ownerType, ownerID, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	debit := debitNormal(ownerType, ownerID)
	var out []AccountActivity
	for rows.Next() {
		var a AccountActivity
		if err := rows.Scan(&a.TxnID, &a.Kind, &a.Memo, &a.AmountMicros, &a.CreatedAt); err != nil {
			return nil, err
		}
		if !debit {
			a.AmountMicros = -a.AmountMicros
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// MonthlySpendRow aggregates an owner's settled money by calendar month.
type MonthlySpendRow struct {
	Month       string // YYYY-MM
	Impressions int64
	GrossMicros int64
	FeeMicros   int64
	NetMicros   int64
}

// MonthlySettled returns per-month totals for either side, grouped by the
// entity's LOCAL months (local_date is the settlement window's local-day
// label, so calendar months nest cleanly in the entity's own zone).
// Advertiser months carry gross only — the margin split happens on the
// publisher side under local-day settlement.
func (s *Service) MonthlySettled(ctx context.Context, ownerType OwnerType, ownerID string, months int) ([]MonthlySpendRow, error) {
	var q string
	switch ownerType {
	case OwnerAdvertiser:
		q = `
			SELECT to_char(local_date, 'YYYY-MM') AS month,
			       COALESCE(SUM(impressions), 0), COALESCE(SUM(gross_micros), 0),
			       0::bigint, 0::bigint
			FROM advertiser_settlements WHERE advertiser_id = $1
			GROUP BY month ORDER BY month DESC LIMIT $2`
	case OwnerPublisher:
		q = `
			SELECT to_char(local_date, 'YYYY-MM') AS month,
			       COALESCE(SUM(impressions), 0), COALESCE(SUM(gross_micros), 0),
			       COALESCE(SUM(fee_micros), 0), COALESCE(SUM(net_micros), 0)
			FROM publisher_settlements WHERE publisher_id = $1
			GROUP BY month ORDER BY month DESC LIMIT $2`
	default:
		return nil, errors.New("billing: monthly totals are per advertiser or publisher")
	}
	rows, err := s.pool.Query(ctx, q, ownerID, months)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MonthlySpendRow
	for rows.Next() {
		var m MonthlySpendRow
		if err := rows.Scan(&m.Month, &m.Impressions, &m.GrossMicros, &m.FeeMicros, &m.NetMicros); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// DailyCharged returns an advertiser's settled gross by local day
// (YYYY-MM-DD) over roughly the last `days` days. Days with no delivery
// have no entry; the caller reads them as zero.
func (s *Service) DailyCharged(ctx context.Context, advertiserID string, days int) (map[string]int64, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT to_char(local_date, 'YYYY-MM-DD'), COALESCE(SUM(gross_micros), 0)
		FROM advertiser_settlements
		WHERE advertiser_id = $1 AND local_date > CURRENT_DATE - $2::int - 2
		GROUP BY local_date`, advertiserID, days)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int64{}
	for rows.Next() {
		var day string
		var gross int64
		if err := rows.Scan(&day, &gross); err != nil {
			return nil, err
		}
		out[day] = gross
	}
	return out, rows.Err()
}

// LifetimePaid sums a publisher's paid payouts.
func (s *Service) LifetimePaid(ctx context.Context, publisherID string) (int64, error) {
	var sum int64
	err := s.pool.QueryRow(ctx, `
		SELECT COALESCE(SUM(amount_micros), 0) FROM payouts
		WHERE publisher_id = $1 AND status = 'paid'`,
		publisherID,
	).Scan(&sum)
	return sum, err
}

// PayoutMethod is a publisher's payout configuration. Details is free-form
// per operator (bank fields differ per country).
type PayoutMethod struct {
	PublisherID     string
	Method          string
	Details         string // JSON as entered
	MinPayoutMicros int64
	UpdatedAt       time.Time
}

func (s *Service) GetPayoutMethod(ctx context.Context, publisherID string) (PayoutMethod, error) {
	var m PayoutMethod
	err := s.pool.QueryRow(ctx, `
		SELECT publisher_id, method, COALESCE(details->>'text', ''), min_payout_micros, updated_at
		FROM payout_methods WHERE publisher_id = $1`,
		publisherID,
	).Scan(&m.PublisherID, &m.Method, &m.Details, &m.MinPayoutMicros, &m.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		// No stated preference: zero, so callers clamping to the platform
		// floor display the floor itself.
		return PayoutMethod{PublisherID: publisherID}, nil
	}
	return m, err
}

// UpsertPayoutMethod stores Details as free text (wrapped in JSONB) —
// bank instructions vary per country and operator, so no schema is imposed.
func (s *Service) UpsertPayoutMethod(ctx context.Context, m PayoutMethod) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO payout_methods (publisher_id, method, details, min_payout_micros, updated_at)
		VALUES ($1, $2, jsonb_build_object('text', $3::text), $4, NOW())
		ON CONFLICT (publisher_id) DO UPDATE
		SET method = $2, details = jsonb_build_object('text', $3::text),
		    min_payout_micros = $4, updated_at = NOW()`,
		m.PublisherID, m.Method, m.Details, m.MinPayoutMicros,
	)
	return err
}

// PayoutPeriodStart picks the informational period_start for a new payout:
// the day after the last non-cancelled payout's period_end, else the
// publisher's earliest settled local day. ok is false when neither exists.
// Periods are publisher-local days, matching the earnings statement.
func (s *Service) PayoutPeriodStart(ctx context.Context, publisherID string) (time.Time, bool, error) {
	var last *time.Time
	err := s.pool.QueryRow(ctx, `
		SELECT MAX(period_end) FROM payouts
		WHERE publisher_id = $1 AND status <> 'cancelled'`,
		publisherID,
	).Scan(&last)
	if err != nil {
		return time.Time{}, false, err
	}
	if last != nil {
		return last.AddDate(0, 0, 1), true, nil
	}
	var first *time.Time
	err = s.pool.QueryRow(ctx,
		`SELECT MIN(local_date) FROM publisher_settlements WHERE publisher_id = $1`,
		publisherID,
	).Scan(&first)
	if err != nil {
		return time.Time{}, false, err
	}
	if first != nil {
		return *first, true, nil
	}
	return time.Time{}, false, nil
}
