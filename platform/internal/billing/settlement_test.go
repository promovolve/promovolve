package billing

// Settlement-job integration test: fake core API (httptest), real ledger in
// a throwaway schema. Gated on BILLING_TEST_DATABASE_URL like the ledger
// integration test.

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeCore serves the /v1/internal surfaces the settler uses and records
// suspend/resume calls.
type fakeCore struct {
	mu           sync.Mutex
	rowsFor      map[string][]map[string]any // date → daily metering rows
	intraday     map[string]string           // advertiserId → unsettled gross (dollars string)
	failIntraday bool
	suspends     []string
	resumes      []string
}

func (f *fakeCore) handler(t *testing.T) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Internal-Key") != "test-key" {
			t.Errorf("missing/wrong X-Internal-Key on %s %s", r.Method, r.URL)
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		f.mu.Lock()
		defer f.mu.Unlock()
		switch {
		case r.URL.Path == "/v1/internal/metering/daily":
			date := r.URL.Query().Get("date")
			rows := f.rowsFor[date]
			if rows == nil {
				rows = []map[string]any{}
			}
			json.NewEncoder(w).Encode(map[string]any{"date": date, "rows": rows})
		case r.URL.Path == "/v1/internal/metering/intraday":
			if f.failIntraday {
				http.Error(w, "boom", http.StatusInternalServerError)
				return
			}
			rows := []map[string]any{}
			for adv, gross := range f.intraday {
				rows = append(rows, map[string]any{"advertiserId": adv, "gross": gross})
			}
			json.NewEncoder(w).Encode(map[string]any{"since": r.URL.Query().Get("since"), "rows": rows})
		case strings.HasSuffix(r.URL.Path, "/suspend"):
			f.suspends = append(f.suspends, pathAdvertiser(r.URL.Path))
			w.WriteHeader(http.StatusNoContent)
		case strings.HasSuffix(r.URL.Path, "/resume"):
			f.resumes = append(f.resumes, pathAdvertiser(r.URL.Path))
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	})
}

func pathAdvertiser(p string) string {
	parts := strings.Split(p, "/") // /v1/internal/advertisers/{id}/suspend
	return parts[len(parts)-2]
}

func meteringRow(adv, camp, site, pub string, imps int64, gross string) map[string]any {
	return map[string]any{
		"advertiserId": adv, "campaignId": camp, "siteId": site,
		"publisherId": pub, "impressions": imps, "gross": gross,
	}
}

func TestSettlerFullCycle(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{rowsFor: map[string][]map[string]any{
		"2026-07-09": {
			meteringRow("advA", "campX", "site1", "pubP", 1000, "10.000000"),
			meteringRow("advA", "campY", "site2", "pubQ", 500, "5.500000"),
			meteringRow("advB", "campZ", "site1", "pubP", 200, "2.000000"),
			meteringRow("advB", "campZ", "siteX", "", 50, "2.000000"), // unmapped site → skipped
		},
	}}
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler := NewSettler(svc, pool, NewHTTPCoreClient(server.URL, "test-key"),
		func(context.Context, time.Time) (int, error) { return 1500, nil })
	now := time.Date(2026, 7, 10, 12, 0, 0, 0, time.UTC)
	settler.now = func() time.Time { return now }

	// advA is funded but thinly: 16.00 covers the 15.50 spend with 0.50 left,
	// which is under the 2×-trailing-average watermark → low_balance.
	// advB is never funded → suspended.
	if _, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advA", AmountMicros: 16_000_000, IdempotencyKey: "t-a1",
	}); err != nil {
		t.Fatalf("fund advA: %v", err)
	}

	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("first pass: %v", err)
	}

	// Day marker: 3 settled rows, 1 skipped, 17.50 gross.
	var settled, skipped int
	var gross int64
	if err := pool.QueryRow(ctx, `
		SELECT rows_settled, rows_skipped, gross_micros
		FROM billing_settlement_days WHERE day = '2026-07-09'`,
	).Scan(&settled, &skipped, &gross); err != nil {
		t.Fatalf("day marker: %v", err)
	}
	if settled != 3 || skipped != 1 || gross != 17_500_000 {
		t.Fatalf("day marker = settled %d skipped %d gross %d", settled, skipped, gross)
	}

	// Wallets: advA charged 15.50 → 0.50 left, low_balance; advB −2.00 → suspended.
	a, _ := svc.GetAccount(ctx, OwnerAdvertiser, "advA")
	if a.BalanceMicros != 500_000 || a.Status != StatusLowBalance {
		t.Fatalf("advA = %+v", a)
	}
	b, _ := svc.GetAccount(ctx, OwnerAdvertiser, "advB")
	if b.BalanceMicros != -2_000_000 || b.Status != StatusSuspended {
		t.Fatalf("advB = %+v", b)
	}
	if len(core.suspends) != 1 || core.suspends[0] != "advB" {
		t.Fatalf("core suspends = %v", core.suspends)
	}

	// Publishers accrued net at 15%: pubP = net(10)+net(2), pubQ = net(5.5).
	p, _ := svc.GetAccount(ctx, OwnerPublisher, "pubP")
	q, _ := svc.GetAccount(ctx, OwnerPublisher, "pubQ")
	if p.BalanceMicros != 8_500_000+1_700_000 || q.BalanceMicros != 4_675_000 {
		t.Fatalf("payables pubP=%d pubQ=%d", p.BalanceMicros, q.BalanceMicros)
	}

	rec, err := svc.Reconcile(ctx)
	if err != nil || !rec.OK {
		t.Fatalf("reconcile: %+v err=%v", rec, err)
	}
	if rec.RevenueMicros != 1_500_000+825_000+300_000 {
		t.Fatalf("revenue = %d", rec.RevenueMicros)
	}

	// Second pass same day: fully idempotent — no re-suspend, no new rows.
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("second pass: %v", err)
	}
	if len(core.suspends) != 1 {
		t.Fatalf("suspend re-fired: %v", core.suspends)
	}

	// Two days later: catch-up settles the two empty days.
	now = time.Date(2026, 7, 12, 1, 0, 0, 0, time.UTC)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("catch-up pass: %v", err)
	}
	var markers int
	pool.QueryRow(ctx, `SELECT COUNT(*) FROM billing_settlement_days`).Scan(&markers)
	if markers != 3 { // 07-09, 07-10, 07-11
		t.Fatalf("settlement day markers = %d, want 3", markers)
	}

	// Funding a suspended wallet by adjustment (not topup) → the next pass
	// resumes serving via core.
	if _, _, err := svc.Adjust(ctx, AdjustParams{
		Kind: TxnAdjustment, Memo: "goodwill credit", IdempotencyKey: "adj-b1",
		Legs: []Leg{
			{OwnerType: OwnerAdvertiser, OwnerID: "advB", AmountMicros: -3_000_000},
			{OwnerType: OwnerPlatform, OwnerID: PlatformRevenue, AmountMicros: 3_000_000},
		},
	}); err != nil {
		t.Fatalf("credit advB: %v", err)
	}
	if err := settler.EvaluateWallets(ctx); err != nil {
		t.Fatalf("evaluate: %v", err)
	}
	if len(core.resumes) != 1 || core.resumes[0] != "advB" {
		t.Fatalf("core resumes = %v", core.resumes)
	}
	b, _ = svc.GetAccount(ctx, OwnerAdvertiser, "advB")
	if b.Status != StatusActive || b.BalanceMicros != 1_000_000 {
		t.Fatalf("advB after credit = %+v", b)
	}

	// Topping up the low_balance wallet re-activates it inline (no core call
	// needed — it was never suspended).
	res, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advA", AmountMicros: 50_000_000, IdempotencyKey: "t-a2",
	})
	if err != nil || res.Reactivated {
		t.Fatalf("advA topup: %+v err=%v", res, err)
	}
	if res.Wallet.Status != StatusActive {
		t.Fatalf("advA status after topup = %s", res.Wallet.Status)
	}
}

func TestSettlerIntradaySuspendAndRecover(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{
		rowsFor:  map[string][]map[string]any{},
		intraday: map[string]string{"advX": "12.000000"}, // unsettled spend today
	}
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler := NewSettler(svc, pool, NewHTTPCoreClient(server.URL, "test-key"),
		func(context.Context, time.Time) (int, error) { return 1500, nil })
	settler.now = func() time.Time { return time.Date(2026, 7, 10, 18, 0, 0, 0, time.UTC) }

	// advX has $10 settled but $12 already metered today: projected −2.
	if _, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advX", AmountMicros: 10_000_000, IdempotencyKey: "x-t1",
	}); err != nil {
		t.Fatalf("fund advX: %v", err)
	}
	if err := settler.EvaluateWallets(ctx); err != nil {
		t.Fatalf("evaluate: %v", err)
	}
	if len(core.suspends) != 1 || core.suspends[0] != "advX" {
		t.Fatalf("intraday suspend not fired: %v", core.suspends)
	}
	a, _ := svc.GetAccount(ctx, OwnerAdvertiser, "advX")
	if a.Status != StatusSuspended || a.BalanceMicros != 10_000_000 {
		t.Fatalf("advX = %+v (settled balance must be untouched — no ledger writes)", a)
	}

	// Intraday outage while suspended: with only the (positive) settled
	// balance visible, a naive pass would mass-resume — it must NOT.
	// The advertiser was suspended precisely because of unsettled spend.
	core.mu.Lock()
	core.failIntraday = true
	core.mu.Unlock()
	if err := settler.EvaluateWallets(ctx); err != nil {
		t.Fatalf("evaluate degraded-suspended: %v", err)
	}
	if len(core.resumes) != 0 {
		t.Fatalf("degraded pass resumed on stale data: %v", core.resumes)
	}
	if a, _ = svc.GetAccount(ctx, OwnerAdvertiser, "advX"); a.Status != StatusSuspended {
		t.Fatalf("advX resumed during outage = %+v", a)
	}

	// Outage over, unsettled tail shrank: projected positive → resume.
	core.mu.Lock()
	core.failIntraday = false
	core.intraday["advX"] = "3.000000"
	core.mu.Unlock()
	if err := settler.EvaluateWallets(ctx); err != nil {
		t.Fatalf("evaluate 2: %v", err)
	}
	if len(core.resumes) != 1 || core.resumes[0] != "advX" {
		t.Fatalf("resume not fired: %v", core.resumes)
	}
	if a, _ = svc.GetAccount(ctx, OwnerAdvertiser, "advX"); a.Status != StatusActive {
		t.Fatalf("advX after recover = %+v", a)
	}

	// Degraded again while active with positive settled balance: no
	// suspend, no error.
	core.mu.Lock()
	core.failIntraday = true
	core.mu.Unlock()
	if err := settler.EvaluateWallets(ctx); err != nil {
		t.Fatalf("evaluate degraded-active: %v", err)
	}
	if len(core.suspends) != 1 {
		t.Fatalf("degraded pass must not suspend: %v", core.suspends)
	}
}

func TestUnfundedNewAdvertiserSuspendedWithoutAccountRow(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	// advNEW has intraday spend but has NEVER touched the ledger — no
	// billing_accounts row. Prepaid means prepaid: evaluation must still
	// suspend within the tick, not wait a day for the first settlement to
	// create a negative account.
	core := &fakeCore{
		rowsFor:  map[string][]map[string]any{},
		intraday: map[string]string{"advNEW": "0.750000"},
	}
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler := NewSettler(svc, pool, NewHTTPCoreClient(server.URL, "test-key"),
		func(context.Context, time.Time) (int, error) { return 1500, nil })
	settler.now = func() time.Time { return time.Date(2026, 7, 10, 18, 0, 0, 0, time.UTC) }

	if err := settler.EvaluateWallets(ctx); err != nil {
		t.Fatalf("evaluate: %v", err)
	}
	if len(core.suspends) != 1 || core.suspends[0] != "advNEW" {
		t.Fatalf("unfunded new advertiser not suspended: %v", core.suspends)
	}
	acc, err := svc.GetAccount(ctx, OwnerAdvertiser, "advNEW")
	if err != nil || acc.Status != StatusSuspended || acc.BalanceMicros != 0 {
		t.Fatalf("account after suspend = %+v err=%v (row must exist, suspended, zero balance)", acc, err)
	}
}

func TestSettlerRetentionGapAlert(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{rowsFor: map[string][]map[string]any{}}
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler := NewSettler(svc, pool, NewHTTPCoreClient(server.URL, "test-key"),
		func(context.Context, time.Time) (int, error) { return 0, nil })

	// Pretend the last settled day is 60 days ago; catch-up must clamp to
	// the 30-day retention window instead of asking core for expired data.
	now := time.Date(2026, 7, 10, 6, 0, 0, 0, time.UTC)
	settler.now = func() time.Time { return now }
	if _, err := pool.Exec(ctx, `
		INSERT INTO billing_settlement_days (day, rows_settled, rows_skipped, gross_micros)
		VALUES ('2026-05-10', 0, 0, 0)`,
	); err != nil {
		t.Fatalf("seed old marker: %v", err)
	}

	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("run: %v", err)
	}
	var oldest, newest string
	if err := pool.QueryRow(ctx, `
		SELECT MIN(day)::text, MAX(day)::text FROM billing_settlement_days WHERE day > '2026-05-10'`,
	).Scan(&oldest, &newest); err != nil {
		t.Fatalf("markers: %v", err)
	}
	// yesterday = 07-09, lookback 30 → oldest settled day = 06-10.
	if oldest != "2026-06-10" || newest != "2026-07-09" {
		t.Fatalf("settled range = %s..%s, want 2026-06-10..2026-07-09", oldest, newest)
	}
	var count int
	pool.QueryRow(ctx, `SELECT COUNT(*) FROM billing_settlement_days WHERE day > '2026-05-10'`).Scan(&count)
	if count != 30 {
		t.Fatalf("settled %d days, want 30", count)
	}
}

func TestSettlerFirstRunSettlesYesterdayOnly(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{rowsFor: map[string][]map[string]any{
		"2026-07-08": {meteringRow("advOld", "c", "s", "pub", 10, "1.000000")}, // pre-billing history
		"2026-07-09": {meteringRow("advNew", "c", "s", "pub", 10, "1.000000")},
	}}
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler := NewSettler(svc, pool, NewHTTPCoreClient(server.URL, "test-key"),
		func(context.Context, time.Time) (int, error) { return 1000, nil })
	settler.now = func() time.Time { return time.Date(2026, 7, 10, 3, 0, 0, 0, time.UTC) }

	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("run: %v", err)
	}
	// Only yesterday was billed — billing starts at install, no retroactive
	// charges for the 07-08 traffic.
	if _, err := svc.GetAccount(ctx, OwnerAdvertiser, "advOld"); err == nil {
		t.Fatal("pre-billing history was retroactively charged")
	}
	n, _ := svc.GetAccount(ctx, OwnerAdvertiser, "advNew")
	if n.BalanceMicros != -1_000_000 {
		t.Fatalf("advNew balance = %d", n.BalanceMicros)
	}
	var days int
	pool.QueryRow(ctx, `SELECT COUNT(*) FROM billing_settlement_days`).Scan(&days)
	if days != 1 {
		t.Fatalf("markers = %d, want 1", days)
	}
}
