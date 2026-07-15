package billing

// Full-cycle ledger test against a real Postgres, gated on
// BILLING_TEST_DATABASE_URL (skipped otherwise). Everything runs inside a
// throwaway schema so it is safe to point at the dev database:
//
//   BILLING_TEST_DATABASE_URL='postgres://promovolve:promovolve@localhost:5432/promovolve?sslmode=disable' \
//     go test ./internal/billing/

import (
	"context"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/hanishi/promovolve/platform/internal/db"
)

func testPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("BILLING_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("BILLING_TEST_DATABASE_URL not set; skipping ledger integration test")
	}
	ctx := context.Background()

	schema := fmt.Sprintf("billing_test_%d", os.Getpid())

	admin, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	if _, err := admin.Exec(ctx, "DROP SCHEMA IF EXISTS "+schema+" CASCADE"); err != nil {
		t.Fatalf("drop stale schema: %v", err)
	}
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatalf("create schema: %v", err)
	}
	t.Cleanup(func() {
		admin.Exec(context.Background(), "DROP SCHEMA IF EXISTS "+schema+" CASCADE")
		admin.Close()
	})

	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		t.Fatalf("parse config: %v", err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("connect with search_path: %v", err)
	}
	t.Cleanup(pool.Close)

	if err := db.Migrate(pool); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	return pool
}

func TestLedgerFullCycle(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	// --- Top-up: $100 into advertiser advA ------------------------------
	topup, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID:   "advA",
		AmountMicros:   100_000_000,
		Memo:           "bank ref 123",
		IdempotencyKey: "topup-1",
	})
	if err != nil {
		t.Fatalf("topup: %v", err)
	}
	if topup.Duplicate || topup.Wallet.BalanceMicros != 100_000_000 {
		t.Fatalf("topup wallet = %+v", topup.Wallet)
	}

	// Same idempotency key: no double credit.
	dup, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advA", AmountMicros: 100_000_000, IdempotencyKey: "topup-1",
	})
	if err != nil || !dup.Duplicate {
		t.Fatalf("duplicate topup: dup=%v err=%v", dup.Duplicate, err)
	}
	if w, _ := svc.GetAccount(ctx, OwnerAdvertiser, "advA"); w.BalanceMicros != 100_000_000 {
		t.Fatalf("wallet after duplicate topup = %d, want 100000000", w.BalanceMicros)
	}

	// --- Settlement: $24.86 gross at 15% margin, two-sided ----------------
	// The advertiser's local-day window and the publisher's differ (Tokyo vs
	// New York); the gross meets at clearing and drains to zero once both
	// sides have booked the same events.
	day := time.Date(2026, 7, 3, 0, 0, 0, 0, time.UTC)
	advWindow := SettlementWindow{
		From: day.Add(-9 * time.Hour), To: day.Add(15 * time.Hour), // Tokyo 07-03
		LocalDate: day, Timezone: "Asia/Tokyo",
	}
	pubWindow := SettlementWindow{
		From: day.Add(4 * time.Hour), To: day.Add(28 * time.Hour), // New York 07-03
		LocalDate: day, Timezone: "America/New_York",
	}
	st, err := svc.RecordAdvertiserSettlement(ctx, AdvSettlementParams{
		Window: advWindow, AdvertiserID: "advA", CampaignID: "campX", SiteID: "site1",
		PublisherID: "pubP", Impressions: 12431, GrossMicros: 24_860_000,
	})
	if err != nil {
		t.Fatalf("advertiser settlement: %v", err)
	}
	if st.Wallet.BalanceMicros != 100_000_000-24_860_000 {
		t.Fatalf("wallet after settlement = %d", st.Wallet.BalanceMicros)
	}
	if c, _ := svc.GetAccount(ctx, OwnerPlatform, PlatformClearing); c.BalanceMicros != 24_860_000 {
		t.Fatalf("clearing after advertiser side = %d, want 24860000 in transit", c.BalanceMicros)
	}

	// Re-running the same cell is a no-op.
	again, err := svc.RecordAdvertiserSettlement(ctx, AdvSettlementParams{
		Window: advWindow, AdvertiserID: "advA", CampaignID: "campX", SiteID: "site1",
		PublisherID: "pubP", Impressions: 12431, GrossMicros: 24_860_000,
	})
	if err != nil || !again.Duplicate {
		t.Fatalf("settlement rerun: dup=%v err=%v", again.Duplicate, err)
	}

	pst, err := svc.RecordPublisherSettlement(ctx, PubSettlementParams{
		Window: pubWindow, PublisherID: "pubP", SiteID: "site1", CampaignID: "campX",
		AdvertiserID: "advA", Impressions: 12431, GrossMicros: 24_860_000, MarginBps: 1500,
	})
	if err != nil {
		t.Fatalf("publisher settlement: %v", err)
	}
	if pst.FeeMicros != 3_729_000 || pst.NetMicros != 21_131_000 {
		t.Fatalf("settlement split fee=%d net=%d", pst.FeeMicros, pst.NetMicros)
	}
	if c, _ := svc.GetAccount(ctx, OwnerPlatform, PlatformClearing); c.BalanceMicros != 0 {
		t.Fatalf("clearing after both sides = %d, want 0", c.BalanceMicros)
	}

	payable, err := svc.GetAccount(ctx, OwnerPublisher, "pubP")
	if err != nil || payable.BalanceMicros != 21_131_000 {
		t.Fatalf("payable = %+v err=%v", payable, err)
	}
	if sts, _ := svc.ListPublisherSettlements(ctx, "pubP", 10); len(sts) != 1 ||
		sts[0].MarginBps != 1500 || sts[0].Timezone != "America/New_York" {
		t.Fatalf("publisher settlement rows = %+v", sts)
	}
	if sts, _ := svc.ListAdvertiserSettlements(ctx, "advA", 10); len(sts) != 1 ||
		sts[0].GrossMicros != 24_860_000 || sts[0].Timezone != "Asia/Tokyo" {
		t.Fatalf("advertiser settlement rows = %+v", sts)
	}

	// --- Payout: full payable to pubP ------------------------------------
	payout, err := svc.CreatePayout(ctx, PayoutParams{
		PublisherID: "pubP", PeriodStart: day, PeriodEnd: day, IdempotencyKey: "payout-1",
	})
	if err != nil {
		t.Fatalf("payout: %v", err)
	}
	if payout.AmountMicros != 21_131_000 || payout.Status != PayoutPending {
		t.Fatalf("payout = %+v", payout)
	}
	if p, _ := svc.GetAccount(ctx, OwnerPublisher, "pubP"); p.BalanceMicros != 0 {
		t.Fatalf("payable after payout = %d, want 0", p.BalanceMicros)
	}
	// Overdraw is impossible now that the payable is empty.
	if _, err := svc.CreatePayout(ctx, PayoutParams{
		PublisherID: "pubP", AmountMicros: 1, PeriodStart: day, PeriodEnd: day, IdempotencyKey: "payout-2",
	}); !errors.Is(err, ErrInsufficientFunds) {
		t.Fatalf("overdraw payout err = %v, want ErrInsufficientFunds", err)
	}
	if err := svc.MarkPayoutPaid(ctx, payout.ID, "wire-789"); err != nil {
		t.Fatalf("mark paid: %v", err)
	}
	if err := svc.MarkPayoutPaid(ctx, payout.ID, "wire-789"); !errors.Is(err, ErrBadPayoutStatus) {
		t.Fatalf("double mark-paid err = %v, want ErrBadPayoutStatus", err)
	}

	// --- Cancel path: second settlement, payout, then cancel -------------
	pubWindow2 := SettlementWindow{
		From: pubWindow.To, To: pubWindow.To.Add(24 * time.Hour),
		LocalDate: day.AddDate(0, 0, 1), Timezone: "America/New_York",
	}
	if _, err := svc.RecordAdvertiserSettlement(ctx, AdvSettlementParams{
		Window: SettlementWindow{From: advWindow.To, To: advWindow.To.Add(24 * time.Hour),
			LocalDate: day.AddDate(0, 0, 1), Timezone: "Asia/Tokyo"},
		AdvertiserID: "advA", CampaignID: "campX", SiteID: "site1",
		PublisherID: "pubP", Impressions: 100, GrossMicros: 1_000_000,
	}); err != nil {
		t.Fatalf("advertiser settlement 2: %v", err)
	}
	st2, err := svc.RecordPublisherSettlement(ctx, PubSettlementParams{
		Window: pubWindow2, PublisherID: "pubP", SiteID: "site1", CampaignID: "campX",
		AdvertiserID: "advA", Impressions: 100, GrossMicros: 1_000_000, MarginBps: 1500,
	})
	if err != nil {
		t.Fatalf("settlement 2: %v", err)
	}
	p2, err := svc.CreatePayout(ctx, PayoutParams{
		PublisherID: "pubP", PeriodStart: day, PeriodEnd: day, IdempotencyKey: "payout-3",
	})
	if err != nil {
		t.Fatalf("payout 2: %v", err)
	}
	if err := svc.CancelPayout(ctx, p2.ID, ""); err != nil {
		t.Fatalf("cancel payout: %v", err)
	}
	if p, _ := svc.GetAccount(ctx, OwnerPublisher, "pubP"); p.BalanceMicros != st2.NetMicros {
		t.Fatalf("payable after cancel = %d, want %d", p.BalanceMicros, st2.NetMicros)
	}
	if err := svc.CancelPayout(ctx, p2.ID, ""); !errors.Is(err, ErrBadPayoutStatus) {
		t.Fatalf("double cancel err = %v, want ErrBadPayoutStatus", err)
	}

	// --- Refund the remaining wallet, then reconcile ----------------------
	if _, err := svc.RefundAdvertiser(ctx, "advA", 200_000_000, "too much", "", "refund-big"); !errors.Is(err, ErrInsufficientFunds) {
		t.Fatalf("overdraw refund err = %v, want ErrInsufficientFunds", err)
	}
	wallet, _ := svc.GetAccount(ctx, OwnerAdvertiser, "advA")
	if _, err := svc.RefundAdvertiser(ctx, "advA", wallet.BalanceMicros, "close account", "", "refund-1"); err != nil {
		t.Fatalf("refund: %v", err)
	}

	rec, err := svc.Reconcile(ctx)
	if err != nil {
		t.Fatalf("reconcile: %v", err)
	}
	if !rec.OK {
		t.Fatalf("reconcile not OK: %+v", rec)
	}
	// Identity: cash = wallets + payables + revenue + clearing.
	if rec.CashMicros != rec.WalletsMicros+rec.PayableMicros+rec.RevenueMicros+rec.ClearingMicros {
		t.Fatalf("identity broken: %+v", rec)
	}
	if rec.ClearingMicros != 0 {
		t.Fatalf("clearing = %d, want 0 (both sides fully settled)", rec.ClearingMicros)
	}
	// Revenue captured = both settlement fees.
	if rec.RevenueMicros != pst.FeeMicros+st2.FeeMicros {
		t.Fatalf("revenue = %d, want %d", rec.RevenueMicros, pst.FeeMicros+st2.FeeMicros)
	}

	// --- Journal ----------------------------------------------------------
	txns, err := svc.ListTransactions(ctx, 50, 0)
	if err != nil {
		t.Fatalf("journal: %v", err)
	}
	// topup, settlement x2 sides x2 days, payout x2, cancel adjustment, refund = 9
	if len(txns) != 9 {
		t.Fatalf("journal has %d transactions, want 9", len(txns))
	}
	for _, txn := range txns {
		var sum int64
		for _, e := range txn.Entries {
			sum += e.AmountMicros
		}
		if sum != 0 {
			t.Errorf("transaction %s (%s) entries sum to %d, want 0", txn.ID, txn.Kind, sum)
		}
	}
}

func TestPayoutFloor(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	// Unset floor degrades to the package default.
	floor, err := svc.PayoutFloorMicros(ctx)
	if err != nil || floor != DefaultMinPayoutMicros {
		t.Fatalf("default floor = %d err=%v", floor, err)
	}

	// pubF ($0.20 owed) stated a 10-cent preference; pubG ($0.30 owed)
	// never configured a payout method at all.
	if _, _, err := svc.Adjust(ctx, AdjustParams{
		Kind: TxnAdjustment, Memo: "seed payables", IdempotencyKey: "floor-adj-1",
		Legs: []Leg{
			{OwnerType: OwnerPublisher, OwnerID: "pubF", AmountMicros: -200_000},
			{OwnerType: OwnerPublisher, OwnerID: "pubG", AmountMicros: -300_000},
			{OwnerType: OwnerPlatform, OwnerID: PlatformRevenue, AmountMicros: 500_000},
		},
	}); err != nil {
		t.Fatalf("seed payables: %v", err)
	}
	if err := svc.UpsertPayoutMethod(ctx, PayoutMethod{
		PublisherID: "pubF", Method: "bank", MinPayoutMicros: 100_000,
	}); err != nil {
		t.Fatalf("method: %v", err)
	}

	// Default floor ($50): both are clamped to it, neither over threshold.
	q, err := svc.PayoutQueue(ctx)
	if err != nil || len(q) != 2 {
		t.Fatalf("queue = %+v err=%v", q, err)
	}
	for _, e := range q {
		if e.OverThreshold || e.MinPayoutMicros != DefaultMinPayoutMicros {
			t.Fatalf("default floor not applied: %+v", e)
		}
	}

	// Operator lowers the floor to $0.15: pubF's 10-cent preference is
	// still clamped UP to the floor; pubG (no preference) follows the
	// floor exactly — lowering it must affect unconfigured publishers.
	if err := svc.SetPayoutFloorMicros(ctx, 150_000, ""); err != nil {
		t.Fatalf("set floor: %v", err)
	}
	if floor, _ = svc.PayoutFloorMicros(ctx); floor != 150_000 {
		t.Fatalf("floor = %d", floor)
	}
	q, _ = svc.PayoutQueue(ctx)
	for _, e := range q {
		if !e.OverThreshold || e.MinPayoutMicros != 150_000 {
			t.Fatalf("lowered floor not applied: %+v", e)
		}
	}
}

func TestSuspendedWalletReactivatesOnTopup(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	if _, err := svc.EnsureAccount(ctx, OwnerAdvertiser, "advB"); err != nil {
		t.Fatalf("ensure: %v", err)
	}
	if err := svc.SetAccountStatus(ctx, OwnerAdvertiser, "advB", StatusSuspended); err != nil {
		t.Fatalf("suspend: %v", err)
	}

	res, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advB", AmountMicros: 5_000_000, IdempotencyKey: "topup-b1",
	})
	if err != nil {
		t.Fatalf("topup: %v", err)
	}
	if !res.Reactivated || res.Wallet.Status != StatusActive {
		t.Fatalf("suspended wallet not reactivated: %+v", res)
	}
}

func TestAdjustRequiresMemoAndBalance(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	// Signup credit: wallet up, funded from platform revenue.
	legs := []Leg{
		{OwnerType: OwnerAdvertiser, OwnerID: "advC", AmountMicros: -1_000_000},
		{OwnerType: OwnerPlatform, OwnerID: PlatformRevenue, AmountMicros: 1_000_000},
	}
	if _, _, err := svc.Adjust(ctx, AdjustParams{
		Kind: TxnAdjustment, Legs: legs, IdempotencyKey: "adj-1",
	}); !errors.Is(err, ErrMemoRequired) {
		t.Fatalf("memo-less adjust err = %v, want ErrMemoRequired", err)
	}
	if _, _, err := svc.Adjust(ctx, AdjustParams{
		Kind: TxnAdjustment, Memo: "signup credit", IdempotencyKey: "adj-2",
		Legs: []Leg{{OwnerType: OwnerAdvertiser, OwnerID: "advC", AmountMicros: -1_000_000}},
	}); err == nil {
		t.Fatal("unbalanced adjust accepted")
	}
	if _, _, err := svc.Adjust(ctx, AdjustParams{
		Kind: TxnAdjustment, Legs: legs, Memo: "signup credit", IdempotencyKey: "adj-3",
	}); err != nil {
		t.Fatalf("adjust: %v", err)
	}

	w, err := svc.GetAccount(ctx, OwnerAdvertiser, "advC")
	if err != nil || w.BalanceMicros != 1_000_000 {
		t.Fatalf("wallet after credit = %+v err=%v", w, err)
	}
	rec, err := svc.Reconcile(ctx)
	if err != nil || !rec.OK {
		t.Fatalf("reconcile after credit: %+v err=%v", rec, err)
	}
	// The credit is negative platform revenue — the operator gave margin away.
	if rec.RevenueMicros != -1_000_000 {
		t.Fatalf("revenue = %d, want -1000000", rec.RevenueMicros)
	}
}
