// Package billing is the double-entry ledger behind PromoVolve's billing &
// settlement design (docs/design/BILLING.md): prepaid advertiser wallets,
// accrued publisher payables, and captured platform margin.
//
// Amounts are int64 micro-dollars (1_000_000 = $1) so ledger arithmetic is
// exact; conversion to/from float happens only at the metering and display
// boundaries. Entries are signed double-entry amounts — positive debits an
// account, negative credits it — and every transaction's entries sum to
// zero, which is what makes the reconciliation identity checkable instead
// of aspirational.
package billing

import (
	"errors"
	"fmt"
	"math"
	"time"
)

type OwnerType string

const (
	OwnerAdvertiser OwnerType = "advertiser"
	OwnerPublisher  OwnerType = "publisher"
	OwnerPlatform   OwnerType = "platform"
)

// The two singleton platform accounts (owner_type = 'platform').
const (
	// PlatformCash is the operator's asset account: external money received
	// (top-ups) minus money sent (payouts, refunds).
	PlatformCash = "cash"
	// PlatformRevenue is the income account margin fees accumulate into.
	PlatformRevenue = "revenue"
)

type TxnKind string

const (
	TxnTopup      TxnKind = "topup"
	TxnSettlement TxnKind = "settlement"
	TxnPayout     TxnKind = "payout"
	TxnAdjustment TxnKind = "adjustment"
	TxnRefund     TxnKind = "refund"
)

type AccountStatus string

const (
	StatusActive     AccountStatus = "active"
	StatusLowBalance AccountStatus = "low_balance"
	StatusSuspended  AccountStatus = "suspended"
)

type PayoutStatus string

const (
	PayoutPending   PayoutStatus = "pending"
	PayoutPaid      PayoutStatus = "paid"
	PayoutCancelled PayoutStatus = "cancelled"
)

var (
	ErrUnbalanced        = errors.New("billing: transaction legs do not sum to zero")
	ErrBadLegs           = errors.New("billing: transaction needs at least two non-zero legs")
	ErrInsufficientFunds = errors.New("billing: amount exceeds account balance")
	ErrAccountNotFound   = errors.New("billing: account not found")
	ErrPayoutNotFound    = errors.New("billing: payout not found")
	ErrBadPayoutStatus   = errors.New("billing: payout is not in the required status")
	ErrMemoRequired      = errors.New("billing: adjustments require a memo")
)

type Account struct {
	ID            string
	OwnerType     OwnerType
	OwnerID       string
	Currency      string
	BalanceMicros int64
	Status        AccountStatus
	CreatedAt     time.Time
}

// Leg is one side of a transaction. AmountMicros is the signed double-entry
// amount: positive debits the account, negative credits it.
type Leg struct {
	OwnerType    OwnerType
	OwnerID      string
	AmountMicros int64
}

type Entry struct {
	OwnerType    OwnerType
	OwnerID      string
	AmountMicros int64
}

type Transaction struct {
	ID             string
	Kind           TxnKind
	IdempotencyKey string
	Memo           string
	CreatedBy      *string
	CreatedAt      time.Time
	Entries        []Entry
}

type Settlement struct {
	ID           string
	Day          time.Time
	AdvertiserID string
	CampaignID   string
	SiteID       string
	PublisherID  string
	Impressions  int64
	GrossMicros  int64
	MarginBps    int
	FeeMicros    int64
	NetMicros    int64
	TxnID        string
	CreatedAt    time.Time
}

type Payout struct {
	ID           string
	PublisherID  string
	AmountMicros int64
	Currency     string
	PeriodStart  time.Time
	PeriodEnd    time.Time
	Status       PayoutStatus
	ExternalRef  string
	TxnID        string
	CreatedBy    *string
	CreatedAt    time.Time
	PaidAt       *time.Time
}

// debitNormal reports whether an account's natural (displayed) balance grows
// with debits. Only the operator's cash account is an asset; wallets and
// payables are liabilities and platform revenue is income, all credit-normal.
func debitNormal(ownerType OwnerType, ownerID string) bool {
	return ownerType == OwnerPlatform && ownerID == PlatformCash
}

// NaturalDelta converts a raw ledger entry amount (debit-positive) into the
// change to the account's natural balance — for rendering journal legs.
func NaturalDelta(ownerType OwnerType, ownerID string, entryAmountMicros int64) int64 {
	if debitNormal(ownerType, ownerID) {
		return entryAmountMicros
	}
	return -entryAmountMicros
}

// SplitFee divides a gross amount into publisher net and platform fee at the
// given margin. The fee is rounded half-up once and net is the exact
// remainder, so fee + net == gross always — never recompute one of them from
// a percentage downstream.
func SplitFee(grossMicros int64, marginBps int) (netMicros, feeMicros int64) {
	feeMicros = (grossMicros*int64(marginBps) + 5000) / 10000
	return grossMicros - feeMicros, feeMicros
}

// DollarsToMicros converts a float dollar amount (the metering boundary) to
// micro-dollars, rounding half away from zero.
func DollarsToMicros(dollars float64) int64 {
	return int64(math.Round(dollars * 1e6))
}

// Dollars converts micro-dollars back to float for display.
func Dollars(micros int64) float64 {
	return float64(micros) / 1e6
}

// SettlementKey is the idempotency key for one settlement cell; re-running a
// settlement day is a no-op because these collide.
func SettlementKey(day time.Time, advertiserID, campaignID, siteID string) string {
	return fmt.Sprintf("settle:%s:%s:%s:%s", day.UTC().Format("2006-01-02"), advertiserID, campaignID, siteID)
}

func validateLegs(legs []Leg) error {
	if len(legs) < 2 {
		return ErrBadLegs
	}
	var sum int64
	for _, l := range legs {
		if l.AmountMicros == 0 {
			return ErrBadLegs
		}
		if l.OwnerID == "" {
			return ErrBadLegs
		}
		sum += l.AmountMicros
	}
	if sum != 0 {
		return ErrUnbalanced
	}
	return nil
}
