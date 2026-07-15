package billing

// Read-side queries for the dashboard pages (Phase 4). Nothing here writes
// to the ledger.

import (
	"context"
	"errors"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
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

// DefaultMinPayoutMicros applies when a publisher has no payout_methods row
// and the operator has not configured a platform floor.
const DefaultMinPayoutMicros = 50_000_000 // $50

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
