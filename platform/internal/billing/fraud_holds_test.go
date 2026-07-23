package billing

// Fraud settlement hold + clawback integration tests (docs/design/
// FRAUD_PREVENTION.md L3.1). Same harness as settlement_test.go: fake core
// (httptest) + real ledger in a throwaway schema, gated on
// BILLING_TEST_DATABASE_URL. The reconciliation identity
// (cash = wallets + payables + revenue + clearing, all entries sum to zero)
// is asserted after every money move — that is what makes hold/release/
// clawback provably correct rather than plausible.

import (
	"context"
	"net/http/httptest"
	"testing"
)

// heldSet wires a fixed held-site set onto the settler (the production
// lookup calls core.ListFraudFlags; here we inject directly so the test
// controls exactly which site is flagged at settle time).
func heldSet(s *Settler, sites ...string) {
	set := make(map[string]bool, len(sites))
	for _, x := range sites {
		set[x] = true
	}
	s.SetHeldSitesLookup(func(context.Context) (map[string]bool, error) { return set, nil })
}

func mustReconcile(t *testing.T, svc *Service) Reconciliation {
	t.Helper()
	rec, err := svc.Reconcile(context.Background())
	if err != nil {
		t.Fatalf("reconcile: %v", err)
	}
	if !rec.OK {
		t.Fatalf("reconcile identity broken: %+v", rec)
	}
	return rec
}

func acct(t *testing.T, svc *Service, ot OwnerType, id string) int64 {
	t.Helper()
	a, err := svc.GetAccount(context.Background(), ot, id)
	if err != nil {
		return 0 // account not yet created == zero balance
	}
	return a.BalanceMicros
}

// TestFraudHoldThenRelease: a flagged site's gross is held in clearing (not
// paid to the publisher); releasing it later pays the publisher exactly as
// a normal settlement would have. Both zones UTC to keep the arithmetic on
// one window.
func TestFraudHoldThenRelease(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	core.add(fakeEvent{"advF", "campC", "siteBad", "pubB", utc(2026, 7, 9, 10, 0), 8_000_000})
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler, now := makeSettler(t, svc, server.URL, nil, 1500) // UTC, 15%
	heldSet(settler, "siteBad")

	if _, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advF", AmountMicros: 20_000_000, IdempotencyKey: "t-f",
	}); err != nil {
		t.Fatalf("fund advF: %v", err)
	}

	// Advertiser and publisher UTC days for 07-09 both close by 07-11.
	*now = utc(2026, 7, 11, 2, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("settle pass: %v", err)
	}

	// Advertiser charged the full gross; it sits in clearing, HELD — the
	// publisher payable was never created.
	if w := acct(t, svc, OwnerAdvertiser, "advF"); w != 12_000_000 {
		t.Fatalf("advertiser wallet = %d, want 12000000 (charged 8.00)", w)
	}
	if c := acct(t, svc, OwnerPlatform, PlatformClearing); c != 8_000_000 {
		t.Fatalf("clearing = %d, want 8000000 held", c)
	}
	if p := acct(t, svc, OwnerPublisher, "pubB"); p != 0 {
		t.Fatalf("publisher paid while held: payable = %d", p)
	}
	if r := acct(t, svc, OwnerPlatform, PlatformRevenue); r != 0 {
		t.Fatalf("revenue booked while held: %d", r)
	}
	held, err := svc.ListHeldForSite(ctx, "siteBad")
	if err != nil || len(held) != 1 || held[0].GrossMicros != 8_000_000 {
		t.Fatalf("held rows = %+v err=%v", held, err)
	}
	if ht, _ := svc.HeldTotalMicros(ctx); ht != 8_000_000 {
		t.Fatalf("held total = %d", ht)
	}
	mustReconcile(t, svc)

	// A re-settle of the same window is a no-op — the hold dedupes.
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("idempotent settle: %v", err)
	}
	if held2, _ := svc.ListHeldForSite(ctx, "siteBad"); len(held2) != 1 {
		t.Fatalf("hold duplicated on re-settle: %d rows", len(held2))
	}

	// Release (false positive): the publisher is paid net, platform takes
	// the fee, clearing drains to zero.
	n, err := svc.ReleaseSiteHolds(ctx, "siteBad", "admin@op")
	if err != nil || n != 1 {
		t.Fatalf("release: n=%d err=%v", n, err)
	}
	if p := acct(t, svc, OwnerPublisher, "pubB"); p != 6_800_000 { // net of 8.00 at 15%
		t.Fatalf("publisher payable = %d, want 6800000", p)
	}
	if r := acct(t, svc, OwnerPlatform, PlatformRevenue); r != 1_200_000 {
		t.Fatalf("revenue = %d, want 1200000", r)
	}
	if c := acct(t, svc, OwnerPlatform, PlatformClearing); c != 0 {
		t.Fatalf("clearing after release = %d, want 0", c)
	}
	if ht, _ := svc.HeldTotalMicros(ctx); ht != 0 {
		t.Fatalf("held total after release = %d", ht)
	}
	mustReconcile(t, svc)

	// Release is idempotent — no double pay.
	if n2, err := svc.ReleaseSiteHolds(ctx, "siteBad", "admin@op"); err != nil || n2 != 0 {
		t.Fatalf("re-release: n=%d err=%v", n2, err)
	}
	if p := acct(t, svc, OwnerPublisher, "pubB"); p != 6_800_000 {
		t.Fatalf("payable changed on re-release: %d", p)
	}
}

// TestFraudHoldThenClawback: confirming fraud refunds the held gross to the
// advertiser; the publisher is never paid and the platform takes no fee.
func TestFraudHoldThenClawback(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	core.add(fakeEvent{"advF", "campC", "siteBad", "pubB", utc(2026, 7, 9, 10, 0), 8_000_000})
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler, now := makeSettler(t, svc, server.URL, nil, 1500)
	heldSet(settler, "siteBad")

	if _, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advF", AmountMicros: 20_000_000, IdempotencyKey: "t-f",
	}); err != nil {
		t.Fatalf("fund: %v", err)
	}
	*now = utc(2026, 7, 11, 2, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("settle: %v", err)
	}

	// Confirm → clawback: wallet refunded to its pre-charge balance.
	n, err := svc.ClawbackSiteHolds(ctx, "siteBad", "admin@op")
	if err != nil || n != 1 {
		t.Fatalf("clawback: n=%d err=%v", n, err)
	}
	if w := acct(t, svc, OwnerAdvertiser, "advF"); w != 20_000_000 {
		t.Fatalf("wallet after clawback = %d, want 20000000 (fully refunded)", w)
	}
	if c := acct(t, svc, OwnerPlatform, PlatformClearing); c != 0 {
		t.Fatalf("clearing after clawback = %d, want 0", c)
	}
	if p := acct(t, svc, OwnerPublisher, "pubB"); p != 0 {
		t.Fatalf("publisher paid on fraud: %d", p)
	}
	if r := acct(t, svc, OwnerPlatform, PlatformRevenue); r != 0 {
		t.Fatalf("platform kept fee on fraud: %d", r)
	}
	if ht, _ := svc.HeldTotalMicros(ctx); ht != 0 {
		t.Fatalf("held total after clawback = %d", ht)
	}
	mustReconcile(t, svc)

	// Idempotent — re-confirm never double-refunds.
	if n2, err := svc.ClawbackSiteHolds(ctx, "siteBad", "admin@op"); err != nil || n2 != 0 {
		t.Fatalf("re-clawback: n=%d err=%v", n2, err)
	}
	if w := acct(t, svc, OwnerAdvertiser, "advF"); w != 20_000_000 {
		t.Fatalf("wallet changed on re-clawback: %d", w)
	}
}

// TestClawbackThenRestore: confirming fraud claws the held gross back to
// the advertiser; a later Restore (the confirm was a mistake) re-charges
// the advertiser and pays the publisher net + platform fee — the exact
// end state a normal release would have reached. Books reconcile at every
// step and restore is idempotent.
func TestClawbackThenRestore(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	core.add(fakeEvent{"advF", "campC", "siteBad", "pubB", utc(2026, 7, 9, 10, 0), 8_000_000})
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler, now := makeSettler(t, svc, server.URL, nil, 1500)
	heldSet(settler, "siteBad")

	if _, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advF", AmountMicros: 20_000_000, IdempotencyKey: "t-fr",
	}); err != nil {
		t.Fatalf("fund: %v", err)
	}
	*now = utc(2026, 7, 11, 2, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("settle: %v", err)
	}
	if n, err := svc.ClawbackSiteHolds(ctx, "siteBad", "admin@op"); err != nil || n != 1 {
		t.Fatalf("clawback: n=%d err=%v", n, err)
	}
	mustReconcile(t, svc)

	// Restore: the publisher is made whole (net of 8.00 @ 15% = 6.80), the
	// platform earns its fee (1.20), and the advertiser is re-charged (wallet
	// back down to 12.00 from the refunded 20.00).
	cells, gross, err := svc.RestoreSiteEarnings(ctx, "siteBad", "admin@op")
	if err != nil || cells != 1 || gross != 8_000_000 {
		t.Fatalf("restore: cells=%d gross=%d err=%v", cells, gross, err)
	}
	if p := acct(t, svc, OwnerPublisher, "pubB"); p != 6_800_000 {
		t.Fatalf("publisher payable after restore = %d, want 6800000", p)
	}
	if r := acct(t, svc, OwnerPlatform, PlatformRevenue); r != 1_200_000 {
		t.Fatalf("revenue after restore = %d, want 1200000", r)
	}
	if w := acct(t, svc, OwnerAdvertiser, "advF"); w != 12_000_000 {
		t.Fatalf("wallet after restore = %d, want 12000000 (re-charged)", w)
	}
	if c := acct(t, svc, OwnerPlatform, PlatformClearing); c != 0 {
		t.Fatalf("clearing after restore = %d, want 0", c)
	}
	mustReconcile(t, svc)

	// Idempotent — re-restore never double-pays.
	if c2, _, err := svc.RestoreSiteEarnings(ctx, "siteBad", "admin@op"); err != nil || c2 != 0 {
		t.Fatalf("re-restore: cells=%d err=%v", c2, err)
	}
	if p := acct(t, svc, OwnerPublisher, "pubB"); p != 6_800_000 {
		t.Fatalf("payable changed on re-restore: %d", p)
	}
	mustReconcile(t, svc)
}

// TestClawbackAfterSettlement: the flag arrives AFTER the publisher window
// already settled and paid out (no hold was recorded). Confirm must reverse
// the already-settled legs — publisher payable and platform revenue back
// out, advertiser refunded — reaching the same end state as a held
// clawback from a different starting point.
func TestClawbackAfterSettlement(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	core.add(fakeEvent{"advF", "campC", "siteBad", "pubB", utc(2026, 7, 9, 10, 0), 8_000_000})
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	// NOTE: no held set — the site settles NORMALLY first (flag not yet
	// raised at settle time).
	settler, now := makeSettler(t, svc, server.URL, nil, 1500)

	if _, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advF", AmountMicros: 20_000_000, IdempotencyKey: "t-f",
	}); err != nil {
		t.Fatalf("fund: %v", err)
	}
	*now = utc(2026, 7, 11, 2, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("settle: %v", err)
	}
	// Normal outcome first: publisher paid, platform fee taken, clearing 0.
	if p := acct(t, svc, OwnerPublisher, "pubB"); p != 6_800_000 {
		t.Fatalf("payable = %d, want 6800000 (settled normally)", p)
	}
	if acct(t, svc, OwnerPlatform, PlatformClearing) != 0 {
		t.Fatal("clearing not drained by normal settle")
	}
	mustReconcile(t, svc)

	// Flag lands late → confirm claws back the settled window.
	n, err := svc.ClawbackSettledWindow(ctx, "siteBad",
		utc(2026, 7, 1, 0, 0), utc(2026, 8, 1, 0, 0), "admin@op")
	if err != nil || n != 1 {
		t.Fatalf("clawback settled: n=%d err=%v", n, err)
	}
	if w := acct(t, svc, OwnerAdvertiser, "advF"); w != 20_000_000 {
		t.Fatalf("wallet after clawback = %d, want 20000000", w)
	}
	if p := acct(t, svc, OwnerPublisher, "pubB"); p != 0 {
		t.Fatalf("publisher payable after clawback = %d, want 0 (net reversed)", p)
	}
	if r := acct(t, svc, OwnerPlatform, PlatformRevenue); r != 0 {
		t.Fatalf("revenue after clawback = %d, want 0 (fee reversed)", r)
	}
	if c := acct(t, svc, OwnerPlatform, PlatformClearing); c != 0 {
		t.Fatalf("clearing after clawback = %d, want 0", c)
	}
	mustReconcile(t, svc)

	// Idempotent.
	if n2, err := svc.ClawbackSettledWindow(ctx, "siteBad",
		utc(2026, 7, 1, 0, 0), utc(2026, 8, 1, 0, 0), "admin@op"); err != nil || n2 != 0 {
		t.Fatalf("re-clawback settled: n=%d err=%v", n2, err)
	}
	if w := acct(t, svc, OwnerAdvertiser, "advF"); w != 20_000_000 {
		t.Fatalf("wallet changed on re-clawback: %d", w)
	}
}

// TestClawbackSettledWindowScoped: a site that settled on two different
// days is clawed back only for the flagged day's range — the other day's
// earnings survive. This is the safety property behind scoping the settled
// backstop to the flagged day rather than sweeping all history.
func TestClawbackSettledWindowScoped(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	// Both events within the settler's 72h genesis-discovery span of `now`
	// but two days apart, so the fraud day's ±1 clawback margin can't reach
	// the honest day.
	core := &fakeCore{}
	core.add(
		fakeEvent{"advF", "campC", "siteBad", "pubB", utc(2026, 7, 11, 12, 0), 5_000_000}, // honest day
		fakeEvent{"advF", "campC", "siteBad", "pubB", utc(2026, 7, 13, 12, 0), 8_000_000}, // fraud day
	)
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler, now := makeSettler(t, svc, server.URL, nil, 1500) // UTC, both days settle normally
	if _, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advF", AmountMicros: 30_000_000, IdempotencyKey: "t-f",
	}); err != nil {
		t.Fatalf("fund: %v", err)
	}
	*now = utc(2026, 7, 14, 6, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("settle: %v", err)
	}
	// Both days paid the publisher: net of 13.00 at 15% = 11.05.
	if p := acct(t, svc, OwnerPublisher, "pubB"); p != 11_050_000 {
		t.Fatalf("payable = %d, want 11050000", p)
	}
	mustReconcile(t, svc)

	// Clawback ONLY the fraud day (07-13) ±1 → [07-12, 07-15). The honest
	// 07-11 cell is outside the range and untouched.
	n, err := svc.ClawbackSettledWindow(ctx, "siteBad",
		utc(2026, 7, 12, 0, 0), utc(2026, 7, 15, 0, 0), "admin@op")
	if err != nil || n != 1 {
		t.Fatalf("scoped clawback: n=%d err=%v", n, err)
	}
	// Only the 8.00 fraud day was reversed: payable = net of 5.00 = 4.25;
	// advertiser refunded the 8.00 (30 − 13 charged + 8 back = 25).
	if p := acct(t, svc, OwnerPublisher, "pubB"); p != 4_250_000 {
		t.Fatalf("payable after scoped clawback = %d, want 4250000 (honest day survives)", p)
	}
	if w := acct(t, svc, OwnerAdvertiser, "advF"); w != 25_000_000 {
		t.Fatalf("wallet = %d, want 25000000 (only fraud day refunded)", w)
	}
	mustReconcile(t, svc)
}

// TestFraudHoldLeavesHonestSitesUntouched: only the flagged site is held;
// an honest site in the same publisher window settles and pays normally.
func TestFraudHoldLeavesHonestSitesUntouched(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	core.add(
		fakeEvent{"advF", "campC", "siteBad", "pubB", utc(2026, 7, 9, 10, 0), 8_000_000},
		fakeEvent{"advF", "campC", "siteOK", "pubB", utc(2026, 7, 9, 11, 0), 4_000_000},
	)
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler, now := makeSettler(t, svc, server.URL, nil, 1500)
	heldSet(settler, "siteBad") // only siteBad is flagged

	if _, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advF", AmountMicros: 20_000_000, IdempotencyKey: "t-f",
	}); err != nil {
		t.Fatalf("fund: %v", err)
	}
	*now = utc(2026, 7, 11, 2, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("settle: %v", err)
	}

	// The honest site paid the publisher (net of 4.00); the flagged site is
	// held in clearing.
	if p := acct(t, svc, OwnerPublisher, "pubB"); p != 3_400_000 { // net of 4.00 at 15%
		t.Fatalf("honest payable = %d, want 3400000", p)
	}
	if c := acct(t, svc, OwnerPlatform, PlatformClearing); c != 8_000_000 {
		t.Fatalf("clearing = %d, want 8000000 (only siteBad held)", c)
	}
	if held, _ := svc.ListHeldForSite(ctx, "siteOK"); len(held) != 0 {
		t.Fatalf("honest site was held: %+v", held)
	}
	mustReconcile(t, svc)
}
