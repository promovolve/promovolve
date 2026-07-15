package billing

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Service struct {
	pool *pgxpool.Pool
}

func NewService(pool *pgxpool.Pool) *Service {
	return &Service{pool: pool}
}

// ---------------------------------------------------------------------------
// Accounts

// EnsureAccount returns the account for an owner, creating it (zero balance,
// active) on first touch.
func (s *Service) EnsureAccount(ctx context.Context, ownerType OwnerType, ownerID string) (Account, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Account{}, err
	}
	defer tx.Rollback(ctx)
	acc, err := ensureAccountTx(ctx, tx, ownerType, ownerID, false)
	if err != nil {
		return Account{}, err
	}
	return acc, tx.Commit(ctx)
}

// GetAccount returns ErrAccountNotFound for owners that never touched money.
func (s *Service) GetAccount(ctx context.Context, ownerType OwnerType, ownerID string) (Account, error) {
	var a Account
	err := s.pool.QueryRow(ctx, `
		SELECT id::text, owner_type, owner_id, currency, balance_micros, status, created_at
		FROM billing_accounts WHERE owner_type = $1 AND owner_id = $2`,
		ownerType, ownerID,
	).Scan(&a.ID, &a.OwnerType, &a.OwnerID, &a.Currency, &a.BalanceMicros, &a.Status, &a.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Account{}, ErrAccountNotFound
	}
	return a, err
}

// SetAccountStatus is the policy hook for the settlement job (low-balance
// watermarks, suspension). The ledger itself only auto-transitions on top-up.
func (s *Service) SetAccountStatus(ctx context.Context, ownerType OwnerType, ownerID string, status AccountStatus) error {
	tag, err := s.pool.Exec(ctx, `
		UPDATE billing_accounts SET status = $3 WHERE owner_type = $1 AND owner_id = $2`,
		ownerType, ownerID, status,
	)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrAccountNotFound
	}
	return nil
}

// ensureAccountTx get-or-creates inside the caller's transaction. When
// forUpdate is set the returned row is locked; callers that lock multiple
// accounts must do so in a deterministic order (see post).
func ensureAccountTx(ctx context.Context, tx pgx.Tx, ownerType OwnerType, ownerID string, forUpdate bool) (Account, error) {
	_, err := tx.Exec(ctx, `
		INSERT INTO billing_accounts (owner_type, owner_id) VALUES ($1, $2)
		ON CONFLICT (owner_type, owner_id) DO NOTHING`,
		ownerType, ownerID,
	)
	if err != nil {
		return Account{}, err
	}
	q := `
		SELECT id::text, owner_type, owner_id, currency, balance_micros, status, created_at
		FROM billing_accounts WHERE owner_type = $1 AND owner_id = $2`
	if forUpdate {
		q += ` FOR UPDATE`
	}
	var a Account
	err = tx.QueryRow(ctx, q, ownerType, ownerID).Scan(
		&a.ID, &a.OwnerType, &a.OwnerID, &a.Currency, &a.BalanceMicros, &a.Status, &a.CreatedAt)
	return a, err
}

// ---------------------------------------------------------------------------
// Core poster

// postResult carries what a posting produced; when Duplicate is set the
// idempotency key had already been used and nothing was written.
type postResult struct {
	TxnID     string
	Duplicate bool
	// Accounts holds the post-update state of every touched account,
	// keyed by ownerType+"/"+ownerID.
	Accounts map[string]Account
}

func accountKey(t OwnerType, id string) string { return string(t) + "/" + id }

// post writes one balanced transaction inside tx: the transaction row, one
// entry per leg, and the materialized balance updates. Accounts are locked
// in sorted (owner_type, owner_id) order so concurrent postings can't
// deadlock. A duplicate idempotency key returns Duplicate with no writes.
func post(ctx context.Context, tx pgx.Tx, kind TxnKind, idemKey, memo string, createdBy *string, legs []Leg) (postResult, error) {
	if err := validateLegs(legs); err != nil {
		return postResult{}, err
	}
	if idemKey == "" {
		return postResult{}, errors.New("billing: idempotency key required")
	}

	var txnID string
	err := tx.QueryRow(ctx, `
		INSERT INTO ledger_transactions (kind, idempotency_key, memo, created_by)
		VALUES ($1, $2, $3, $4)
		ON CONFLICT (idempotency_key) DO NOTHING
		RETURNING id::text`,
		kind, idemKey, memo, createdBy,
	).Scan(&txnID)
	if errors.Is(err, pgx.ErrNoRows) {
		// Key already used. Look the transaction up outside the insert so the
		// caller can report what it was deduplicated against.
		var existing string
		if err := tx.QueryRow(ctx,
			`SELECT id::text FROM ledger_transactions WHERE idempotency_key = $1`,
			idemKey,
		).Scan(&existing); err != nil {
			return postResult{}, err
		}
		return postResult{TxnID: existing, Duplicate: true}, nil
	}
	if err != nil {
		return postResult{}, err
	}

	ordered := make([]Leg, len(legs))
	copy(ordered, legs)
	sort.Slice(ordered, func(i, j int) bool {
		if ordered[i].OwnerType != ordered[j].OwnerType {
			return ordered[i].OwnerType < ordered[j].OwnerType
		}
		return ordered[i].OwnerID < ordered[j].OwnerID
	})

	accounts := make(map[string]Account, len(ordered))
	for _, leg := range ordered {
		acc, err := ensureAccountTx(ctx, tx, leg.OwnerType, leg.OwnerID, true)
		if err != nil {
			return postResult{}, err
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO ledger_entries (txn_id, account_id, amount_micros)
			VALUES ($1, $2, $3)`,
			txnID, acc.ID, leg.AmountMicros,
		); err != nil {
			return postResult{}, err
		}
		// Natural balance: debits grow assets, credits grow liabilities/income.
		delta := leg.AmountMicros
		if !debitNormal(leg.OwnerType, leg.OwnerID) {
			delta = -delta
		}
		if err := tx.QueryRow(ctx, `
			UPDATE billing_accounts SET balance_micros = balance_micros + $2
			WHERE id = $1 RETURNING balance_micros`,
			acc.ID, delta,
		).Scan(&acc.BalanceMicros); err != nil {
			return postResult{}, err
		}
		accounts[accountKey(leg.OwnerType, leg.OwnerID)] = acc
	}
	return postResult{TxnID: txnID, Accounts: accounts}, nil
}

// ---------------------------------------------------------------------------
// Top-ups

type TopupParams struct {
	AdvertiserID   string
	AmountMicros   int64
	Memo           string // e.g. bank transfer reference
	CreatedBy      string // platform_users.id of the admin; empty for system callers (PSP webhook)
	IdempotencyKey string // form nonce or PSP event id
}

type TopupResult struct {
	TxnID     string
	Duplicate bool
	Wallet    Account
	// Reactivated is set when the top-up brought a suspended wallet back
	// above zero; the caller owes a core-side resume call (Phase 5).
	Reactivated bool
}

// RecordTopup credits an advertiser's wallet against operator cash. The
// manual admin form and a future PSP webhook both call this — the
// idempotency key is what makes retries and double-clicks safe.
func (s *Service) RecordTopup(ctx context.Context, p TopupParams) (TopupResult, error) {
	if p.AmountMicros <= 0 {
		return TopupResult{}, errors.New("billing: top-up amount must be positive")
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return TopupResult{}, err
	}
	defer tx.Rollback(ctx)

	res, err := post(ctx, tx, TxnTopup, p.IdempotencyKey, p.Memo, optional(p.CreatedBy), []Leg{
		{OwnerType: OwnerPlatform, OwnerID: PlatformCash, AmountMicros: p.AmountMicros},
		{OwnerType: OwnerAdvertiser, OwnerID: p.AdvertiserID, AmountMicros: -p.AmountMicros},
	})
	if err != nil {
		return TopupResult{}, err
	}
	if res.Duplicate {
		wallet, gerr := s.GetAccount(ctx, OwnerAdvertiser, p.AdvertiserID)
		if gerr != nil && !errors.Is(gerr, ErrAccountNotFound) {
			return TopupResult{}, gerr
		}
		return TopupResult{TxnID: res.TxnID, Duplicate: true, Wallet: wallet}, nil
	}

	out := TopupResult{TxnID: res.TxnID, Wallet: res.Accounts[accountKey(OwnerAdvertiser, p.AdvertiserID)]}
	// Funded again → the account is no longer suspended/low-balance. The
	// core-side serve resume is the caller's job, flagged via Reactivated.
	if out.Wallet.Status != StatusActive && out.Wallet.BalanceMicros > 0 {
		if _, err := tx.Exec(ctx,
			`UPDATE billing_accounts SET status = $2 WHERE id = $1`,
			out.Wallet.ID, StatusActive,
		); err != nil {
			return TopupResult{}, err
		}
		out.Reactivated = out.Wallet.Status == StatusSuspended
		out.Wallet.Status = StatusActive
	}
	return out, tx.Commit(ctx)
}

// ---------------------------------------------------------------------------
// Settlements
//
// Every entity settles on its own local days, so the advertiser and
// publisher sides of the same events book at different times into different
// windows. The two one-sided postings below meet at the platform clearing
// account; because metering returns per-event integer micros, the two
// sides' totals over any range settled by both are identical and clearing
// nets to exactly zero.

// SettlementWindow identifies one entity-local settlement window. LocalDate
// is the display label (the window's local day in Timezone); From is the
// idempotency anchor.
type SettlementWindow struct {
	From      time.Time
	To        time.Time
	LocalDate time.Time
	Timezone  string
}

type AdvSettlementParams struct {
	Window       SettlementWindow
	AdvertiserID string
	CampaignID   string
	SiteID       string
	PublisherID  string // lineage/alerting only; may be empty (unmapped site cells are the CALLER's skip decision)
	Impressions  int64
	GrossMicros  int64
}

type PubSettlementParams struct {
	Window       SettlementWindow
	PublisherID  string
	SiteID       string
	CampaignID   string
	AdvertiserID string
	Impressions  int64
	GrossMicros  int64
	MarginBps    int // snapshot of the rate effective at window end
}

type SettlementResult struct {
	TxnID     string
	Duplicate bool
	FeeMicros int64
	NetMicros int64
	Wallet    Account
}

// RecordAdvertiserSettlement books the advertiser side of one metered
// (campaign, site) cell for the advertiser's local-day window: the wallet
// is charged gross and the gross parks in clearing until the publisher's
// own window drains it. Idempotent per (advertiser, window start, cell).
func (s *Service) RecordAdvertiserSettlement(ctx context.Context, p AdvSettlementParams) (SettlementResult, error) {
	if p.GrossMicros <= 0 {
		return SettlementResult{}, errors.New("billing: settlement gross must be positive (skip zero cells)")
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return SettlementResult{}, err
	}
	defer tx.Rollback(ctx)

	legs := []Leg{
		{OwnerType: OwnerAdvertiser, OwnerID: p.AdvertiserID, AmountMicros: p.GrossMicros},
		{OwnerType: OwnerPlatform, OwnerID: PlatformClearing, AmountMicros: -p.GrossMicros},
	}
	memo := fmt.Sprintf("settlement %s campaign %s on site %s",
		p.Window.LocalDate.Format("2006-01-02"), p.CampaignID, p.SiteID)
	key := AdvSettlementKey(p.AdvertiserID, p.Window.From, p.CampaignID, p.SiteID)
	res, err := post(ctx, tx, TxnSettlement, key, memo, nil, legs)
	if err != nil {
		return SettlementResult{}, err
	}
	if res.Duplicate {
		return SettlementResult{TxnID: res.TxnID, Duplicate: true}, nil
	}

	if _, err := tx.Exec(ctx, `
		INSERT INTO advertiser_settlements
			(advertiser_id, campaign_id, site_id, publisher_id,
			 window_from, window_to, local_date, timezone,
			 impressions, gross_micros, txn_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7::date, $8, $9, $10, $11)`,
		p.AdvertiserID, p.CampaignID, p.SiteID, p.PublisherID,
		p.Window.From, p.Window.To, p.Window.LocalDate.Format("2006-01-02"), p.Window.Timezone,
		p.Impressions, p.GrossMicros, res.TxnID,
	); err != nil {
		return SettlementResult{}, err
	}

	out := SettlementResult{
		TxnID:  res.TxnID,
		Wallet: res.Accounts[accountKey(OwnerAdvertiser, p.AdvertiserID)],
	}
	return out, tx.Commit(ctx)
}

// RecordPublisherSettlement books the publisher side of one metered
// (site, campaign, advertiser) cell for the publisher's local-day window:
// clearing drains gross, the publisher accrues net, the platform captures
// the fee, and the durable publisher_settlements row snapshots the applied
// margin. Idempotent per (publisher, window start, cell). Zero legs are
// dropped — at extreme margins net or fee can round to zero, and a zero
// leg would fail validation (the old combined RecordSettlement had this
// latent net==0 bug).
func (s *Service) RecordPublisherSettlement(ctx context.Context, p PubSettlementParams) (SettlementResult, error) {
	if p.GrossMicros <= 0 {
		return SettlementResult{}, errors.New("billing: settlement gross must be positive (skip zero cells)")
	}
	if p.MarginBps < 0 || p.MarginBps >= 10000 {
		return SettlementResult{}, errors.New("billing: margin out of range")
	}
	if p.PublisherID == "" {
		return SettlementResult{}, errors.New("billing: publisher settlement requires a publisher id")
	}
	net, fee := SplitFee(p.GrossMicros, p.MarginBps)

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return SettlementResult{}, err
	}
	defer tx.Rollback(ctx)

	legs := []Leg{
		{OwnerType: OwnerPlatform, OwnerID: PlatformClearing, AmountMicros: p.GrossMicros},
	}
	if net > 0 {
		legs = append(legs, Leg{OwnerType: OwnerPublisher, OwnerID: p.PublisherID, AmountMicros: -net})
	}
	if fee > 0 {
		legs = append(legs, Leg{OwnerType: OwnerPlatform, OwnerID: PlatformRevenue, AmountMicros: -fee})
	}
	memo := fmt.Sprintf("earnings %s campaign %s on site %s",
		p.Window.LocalDate.Format("2006-01-02"), p.CampaignID, p.SiteID)
	key := PubSettlementKey(p.PublisherID, p.Window.From, p.SiteID, p.CampaignID, p.AdvertiserID)
	res, err := post(ctx, tx, TxnSettlement, key, memo, nil, legs)
	if err != nil {
		return SettlementResult{}, err
	}
	if res.Duplicate {
		return SettlementResult{TxnID: res.TxnID, Duplicate: true, FeeMicros: fee, NetMicros: net}, nil
	}

	if _, err := tx.Exec(ctx, `
		INSERT INTO publisher_settlements
			(publisher_id, site_id, campaign_id, advertiser_id,
			 window_from, window_to, local_date, timezone,
			 impressions, gross_micros, margin_bps, fee_micros, net_micros, txn_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7::date, $8, $9, $10, $11, $12, $13, $14)`,
		p.PublisherID, p.SiteID, p.CampaignID, p.AdvertiserID,
		p.Window.From, p.Window.To, p.Window.LocalDate.Format("2006-01-02"), p.Window.Timezone,
		p.Impressions, p.GrossMicros, p.MarginBps, fee, net, res.TxnID,
	); err != nil {
		return SettlementResult{}, err
	}

	return SettlementResult{TxnID: res.TxnID, FeeMicros: fee, NetMicros: net}, tx.Commit(ctx)
}

// ---------------------------------------------------------------------------
// Payouts

type PayoutParams struct {
	PublisherID    string
	AmountMicros   int64 // 0 means the publisher's full current payable
	PeriodStart    time.Time
	PeriodEnd      time.Time
	CreatedBy      string
	IdempotencyKey string
}

// CreatePayout moves accrued payable out of the ledger into a pending payout
// the admin then pays externally and marks paid. Never overdraws the payable.
func (s *Service) CreatePayout(ctx context.Context, p PayoutParams) (Payout, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Payout{}, err
	}
	defer tx.Rollback(ctx)

	payable, err := ensureAccountTx(ctx, tx, OwnerPublisher, p.PublisherID, true)
	if err != nil {
		return Payout{}, err
	}
	amount := p.AmountMicros
	if amount == 0 {
		amount = payable.BalanceMicros
	}
	if amount <= 0 || amount > payable.BalanceMicros {
		return Payout{}, ErrInsufficientFunds
	}

	res, err := post(ctx, tx, TxnPayout, p.IdempotencyKey,
		fmt.Sprintf("payout to publisher %s", p.PublisherID), optional(p.CreatedBy), []Leg{
			{OwnerType: OwnerPublisher, OwnerID: p.PublisherID, AmountMicros: amount},
			{OwnerType: OwnerPlatform, OwnerID: PlatformCash, AmountMicros: -amount},
		})
	if err != nil {
		return Payout{}, err
	}
	if res.Duplicate {
		var existing Payout
		if err := scanPayout(tx.QueryRow(ctx, payoutSelect+` WHERE txn_id = $1`, res.TxnID), &existing); err != nil {
			return Payout{}, err
		}
		return existing, nil
	}

	var out Payout
	err = scanPayout(tx.QueryRow(ctx, `
		INSERT INTO payouts (publisher_id, amount_micros, period_start, period_end, txn_id, created_by)
		VALUES ($1, $2, $3::date, $4::date, $5, $6)
		RETURNING `+payoutColumns,
		p.PublisherID, amount,
		p.PeriodStart.UTC().Format("2006-01-02"), p.PeriodEnd.UTC().Format("2006-01-02"),
		res.TxnID, optional(p.CreatedBy),
	), &out)
	if err != nil {
		return Payout{}, err
	}
	return out, tx.Commit(ctx)
}

// MarkPayoutPaid records that the external transfer went out.
func (s *Service) MarkPayoutPaid(ctx context.Context, payoutID, externalRef string) error {
	tag, err := s.pool.Exec(ctx, `
		UPDATE payouts SET status = 'paid', external_ref = $2, paid_at = NOW()
		WHERE id = $1 AND status = 'pending'`,
		payoutID, externalRef,
	)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return errBadPayout(ctx, s.pool, payoutID)
	}
	return nil
}

// CancelPayout reverses a pending payout with a balanced adjustment, putting
// the money back on the publisher's payable.
func (s *Service) CancelPayout(ctx context.Context, payoutID, createdBy string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var p Payout
	err = scanPayout(tx.QueryRow(ctx, payoutSelect+` WHERE id = $1 FOR UPDATE`, payoutID), &p)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrPayoutNotFound
	}
	if err != nil {
		return err
	}
	if p.Status != PayoutPending {
		return ErrBadPayoutStatus
	}

	if _, err := tx.Exec(ctx,
		`UPDATE payouts SET status = 'cancelled' WHERE id = $1`, payoutID,
	); err != nil {
		return err
	}
	_, err = post(ctx, tx, TxnAdjustment, "payout-cancel:"+payoutID,
		fmt.Sprintf("cancel payout %s", payoutID), optional(createdBy), []Leg{
			{OwnerType: OwnerPublisher, OwnerID: p.PublisherID, AmountMicros: -p.AmountMicros},
			{OwnerType: OwnerPlatform, OwnerID: PlatformCash, AmountMicros: p.AmountMicros},
		})
	if err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *Service) ListPayouts(ctx context.Context, publisherID string, limit, offset int) ([]Payout, error) {
	q := payoutSelect + ` WHERE ($1 = '' OR publisher_id = $1) ORDER BY created_at DESC LIMIT $2 OFFSET $3`
	rows, err := s.pool.Query(ctx, q, publisherID, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Payout
	for rows.Next() {
		var p Payout
		if err := scanPayout(rows, &p); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// ---------------------------------------------------------------------------
// Adjustments & refunds

type AdjustParams struct {
	Kind           TxnKind // TxnAdjustment or TxnRefund
	Legs           []Leg
	Memo           string // mandatory — the audit trail for manual money moves
	CreatedBy      string
	IdempotencyKey string
}

// Adjust posts an arbitrary balanced transaction: goodwill credits,
// invalid-traffic clawbacks, corrections. The append-only journal plus the
// mandatory memo is the audit trail; there is no edit or delete.
func (s *Service) Adjust(ctx context.Context, p AdjustParams) (string, bool, error) {
	if p.Memo == "" {
		return "", false, ErrMemoRequired
	}
	if p.Kind != TxnAdjustment && p.Kind != TxnRefund {
		return "", false, errors.New("billing: adjust kind must be adjustment or refund")
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", false, err
	}
	defer tx.Rollback(ctx)
	res, err := post(ctx, tx, p.Kind, p.IdempotencyKey, p.Memo, optional(p.CreatedBy), p.Legs)
	if err != nil {
		return "", false, err
	}
	if res.Duplicate {
		return res.TxnID, true, nil
	}
	return res.TxnID, false, tx.Commit(ctx)
}

// RefundAdvertiser returns unspent wallet funds to the advertiser (operator
// sends the money back externally). Never overdraws the wallet.
func (s *Service) RefundAdvertiser(ctx context.Context, advertiserID string, amountMicros int64, memo, createdBy, idemKey string) (string, error) {
	if memo == "" {
		return "", ErrMemoRequired
	}
	if amountMicros <= 0 {
		return "", errors.New("billing: refund amount must be positive")
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx)

	wallet, err := ensureAccountTx(ctx, tx, OwnerAdvertiser, advertiserID, true)
	if err != nil {
		return "", err
	}
	if amountMicros > wallet.BalanceMicros {
		return "", ErrInsufficientFunds
	}
	res, err := post(ctx, tx, TxnRefund, idemKey, memo, optional(createdBy), []Leg{
		{OwnerType: OwnerAdvertiser, OwnerID: advertiserID, AmountMicros: amountMicros},
		{OwnerType: OwnerPlatform, OwnerID: PlatformCash, AmountMicros: -amountMicros},
	})
	if err != nil {
		return "", err
	}
	if res.Duplicate {
		return res.TxnID, nil
	}
	return res.TxnID, tx.Commit(ctx)
}

// ---------------------------------------------------------------------------
// Reconciliation & journal

type Reconciliation struct {
	CashMicros    int64
	WalletsMicros int64 // Σ advertiser wallet balances
	PayableMicros int64 // Σ publisher payables
	RevenueMicros int64
	// ClearingMicros is settlement gross in transit between advertiser and
	// publisher local billing days. Nonzero is normal while windows are out
	// of phase; a balance that never drains means unmapped traffic awaiting
	// an operator fix.
	ClearingMicros int64
	// EntrySumMicros is the raw sum of every ledger entry; double-entry
	// means it must be zero.
	EntrySumMicros int64
	// Drift lists accounts whose materialized balance disagrees with the
	// sum of their entries. Always empty unless something bypassed post().
	Drift []AccountDrift
	OK    bool
}

type AccountDrift struct {
	OwnerType          OwnerType
	OwnerID            string
	MaterializedMicros int64
	ComputedMicros     int64
}

// Reconcile verifies the books: entries sum to zero, every materialized
// balance matches its entries, and returns the totals behind the identity
// cash = wallets + payables + revenue + clearing (which zero-sum entries
// guarantee whenever OK is true). This is the admin dashboard's green/red
// check.
func (s *Service) Reconcile(ctx context.Context) (Reconciliation, error) {
	var r Reconciliation
	if err := s.pool.QueryRow(ctx,
		`SELECT COALESCE(SUM(amount_micros), 0) FROM ledger_entries`,
	).Scan(&r.EntrySumMicros); err != nil {
		return r, err
	}

	rows, err := s.pool.Query(ctx, `
		SELECT a.owner_type, a.owner_id, a.balance_micros, COALESCE(SUM(e.amount_micros), 0)
		FROM billing_accounts a
		LEFT JOIN ledger_entries e ON e.account_id = a.id
		GROUP BY a.id, a.owner_type, a.owner_id, a.balance_micros`,
	)
	if err != nil {
		return r, err
	}
	defer rows.Close()
	for rows.Next() {
		var ownerType OwnerType
		var ownerID string
		var materialized, entrySum int64
		if err := rows.Scan(&ownerType, &ownerID, &materialized, &entrySum); err != nil {
			return r, err
		}
		computed := entrySum
		if !debitNormal(ownerType, ownerID) {
			computed = -entrySum
		}
		if computed != materialized {
			r.Drift = append(r.Drift, AccountDrift{ownerType, ownerID, materialized, computed})
		}
		switch {
		case ownerType == OwnerAdvertiser:
			r.WalletsMicros += materialized
		case ownerType == OwnerPublisher:
			r.PayableMicros += materialized
		case ownerType == OwnerPlatform && ownerID == PlatformCash:
			r.CashMicros = materialized
		case ownerType == OwnerPlatform && ownerID == PlatformRevenue:
			r.RevenueMicros = materialized
		case ownerType == OwnerPlatform && ownerID == PlatformClearing:
			r.ClearingMicros = materialized
		}
	}
	if err := rows.Err(); err != nil {
		return r, err
	}
	r.OK = r.EntrySumMicros == 0 && len(r.Drift) == 0
	return r, nil
}

// CountPayouts supports the admin payout pager; empty publisherID = all.
func (s *Service) CountPayouts(ctx context.Context, publisherID string) (int, error) {
	var n int
	err := s.pool.QueryRow(ctx,
		`SELECT COUNT(*) FROM payouts WHERE ($1 = '' OR publisher_id = $1)`,
		publisherID,
	).Scan(&n)
	return n, err
}

// CountTransactions supports the admin journal pager.
// CountTransactions supports the admin pagers; with kinds it counts only
// those transaction kinds (e.g. topup+refund for the Top-ups tab).
func (s *Service) CountTransactions(ctx context.Context, kinds ...TxnKind) (int, error) {
	var n int
	err := s.pool.QueryRow(ctx, `
		SELECT COUNT(*) FROM ledger_transactions
		WHERE cardinality($1::text[]) = 0 OR kind = ANY($1::text[])`,
		kindStrings(kinds),
	).Scan(&n)
	return n, err
}

// ListTransactions returns the most recent journal entries with their legs,
// newest first — the admin journal browser. Optional kinds filter to a
// subset of transaction kinds.
func (s *Service) ListTransactions(ctx context.Context, limit, offset int, kinds ...TxnKind) ([]Transaction, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id::text, kind, idempotency_key, memo, created_by::text, created_at
		FROM ledger_transactions
		WHERE cardinality($3::text[]) = 0 OR kind = ANY($3::text[])
		ORDER BY created_at DESC, id LIMIT $1 OFFSET $2`,
		limit, offset, kindStrings(kinds),
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var txns []Transaction
	index := map[string]int{}
	var ids []string
	for rows.Next() {
		var t Transaction
		if err := rows.Scan(&t.ID, &t.Kind, &t.IdempotencyKey, &t.Memo, &t.CreatedBy, &t.CreatedAt); err != nil {
			return nil, err
		}
		index[t.ID] = len(txns)
		ids = append(ids, t.ID)
		txns = append(txns, t)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return nil, nil
	}

	erows, err := s.pool.Query(ctx, `
		SELECT e.txn_id::text, a.owner_type, a.owner_id, e.amount_micros
		FROM ledger_entries e JOIN billing_accounts a ON a.id = e.account_id
		WHERE e.txn_id = ANY($1::uuid[]) ORDER BY e.id`,
		ids,
	)
	if err != nil {
		return nil, err
	}
	defer erows.Close()
	for erows.Next() {
		var txnID string
		var e Entry
		if err := erows.Scan(&txnID, &e.OwnerType, &e.OwnerID, &e.AmountMicros); err != nil {
			return nil, err
		}
		i := index[txnID]
		txns[i].Entries = append(txns[i].Entries, e)
	}
	return txns, erows.Err()
}

// ListAdvertiserSettlements returns advertiser-side settlement cells, newest
// local day first — the raw material for the advertiser statement.
func (s *Service) ListAdvertiserSettlements(ctx context.Context, advertiserID string, limit int) ([]AdvertiserSettlement, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id::text, advertiser_id, campaign_id, site_id, publisher_id,
		       window_from, window_to, local_date, timezone,
		       impressions, gross_micros, txn_id::text, created_at
		FROM advertiser_settlements WHERE advertiser_id = $1
		ORDER BY local_date DESC, window_from DESC, created_at DESC LIMIT $2`,
		advertiserID, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []AdvertiserSettlement
	for rows.Next() {
		var st AdvertiserSettlement
		if err := rows.Scan(&st.ID, &st.AdvertiserID, &st.CampaignID, &st.SiteID, &st.PublisherID,
			&st.WindowFrom, &st.WindowTo, &st.LocalDate, &st.Timezone,
			&st.Impressions, &st.GrossMicros, &st.TxnID, &st.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, st)
	}
	return out, rows.Err()
}

// ListPublisherSettlements returns publisher-side settlement cells, newest
// local day first — the raw material for publisher earnings statements.
func (s *Service) ListPublisherSettlements(ctx context.Context, publisherID string, limit int) ([]PublisherSettlement, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id::text, publisher_id, site_id, campaign_id, advertiser_id,
		       window_from, window_to, local_date, timezone,
		       impressions, gross_micros, margin_bps, fee_micros, net_micros,
		       txn_id::text, created_at
		FROM publisher_settlements WHERE publisher_id = $1
		ORDER BY local_date DESC, window_from DESC, created_at DESC LIMIT $2`,
		publisherID, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PublisherSettlement
	for rows.Next() {
		var st PublisherSettlement
		if err := rows.Scan(&st.ID, &st.PublisherID, &st.SiteID, &st.CampaignID, &st.AdvertiserID,
			&st.WindowFrom, &st.WindowTo, &st.LocalDate, &st.Timezone,
			&st.Impressions, &st.GrossMicros, &st.MarginBps,
			&st.FeeMicros, &st.NetMicros, &st.TxnID, &st.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, st)
	}
	return out, rows.Err()
}

// ---------------------------------------------------------------------------
// helpers

const payoutColumns = `id::text, publisher_id, amount_micros, currency, period_start, period_end,
	status, external_ref, txn_id::text, created_by::text, created_at, paid_at`

const payoutSelect = `SELECT ` + payoutColumns + ` FROM payouts`

func scanPayout(row pgx.Row, p *Payout) error {
	return row.Scan(&p.ID, &p.PublisherID, &p.AmountMicros, &p.Currency,
		&p.PeriodStart, &p.PeriodEnd, &p.Status, &p.ExternalRef,
		&p.TxnID, &p.CreatedBy, &p.CreatedAt, &p.PaidAt)
}

func errBadPayout(ctx context.Context, pool *pgxpool.Pool, payoutID string) error {
	var status string
	err := pool.QueryRow(ctx, `SELECT status FROM payouts WHERE id = $1`, payoutID).Scan(&status)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrPayoutNotFound
	}
	if err != nil {
		return err
	}
	return ErrBadPayoutStatus
}

// optional maps the empty string to NULL for created_by columns.
func optional(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func kindStrings(kinds []TxnKind) []string {
	out := make([]string, len(kinds))
	for i, k := range kinds {
		out[i] = string(k)
	}
	return out
}
