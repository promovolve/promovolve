package billing

// Local-day settlement-job integration test: fake core API (httptest), real
// ledger in a throwaway schema. Gated on BILLING_TEST_DATABASE_URL like the
// ledger integration test.
//
// The fake core holds individual EVENTS and aggregates them per requested
// window, exactly like the real /metering/range does — so cross-zone tests
// genuinely slice the same events into different window partitions, and
// clearing hitting zero IS the partition-invariance check.
//
// Clock note: settlement_windows.settled_at is written with the DATABASE's
// NOW() (real wall clock) while the settler's `now` is injected. Tests that
// need the retro replay to fire use fake dates in 2027 — far enough in the
// future that freshly written windows already look "older than one tick".

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

type fakeEvent struct {
	adv, camp, site, pub string
	at                   time.Time
	micros               int64
}

// fakeCore serves the /v1/internal surfaces the settler uses and records
// suspend/resume calls plus the per-advertiser since instants it receives.
type fakeCore struct {
	mu                 sync.Mutex
	events             []fakeEvent
	failUnsettled      bool
	suspends           []string
	resumes            []string
	lastUnsettledSince map[string]time.Time
}

func (f *fakeCore) add(events ...fakeEvent) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.events = append(f.events, events...)
}

// mapSite backfills the publisher mapping for a site — the "operator fixed
// publisher_sites" moment.
func (f *fakeCore) mapSite(site, pub string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for i := range f.events {
		if f.events[i].site == site {
			f.events[i].pub = pub
		}
	}
}

type cellKey struct{ adv, camp, site, pub string }

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
		case r.URL.Path == "/v1/internal/metering/range":
			from, err1 := time.Parse(time.RFC3339, r.URL.Query().Get("from"))
			to, err2 := time.Parse(time.RFC3339, r.URL.Query().Get("to"))
			if err1 != nil || err2 != nil {
				http.Error(w, "bad instants", http.StatusBadRequest)
				return
			}
			advID := r.URL.Query().Get("advertiserId")
			pubID := r.URL.Query().Get("publisherId")
			if (advID == "") == (pubID == "") {
				http.Error(w, "exactly one entity filter", http.StatusBadRequest)
				return
			}
			cells := map[cellKey]*struct {
				imps   int64
				micros int64
			}{}
			for _, e := range f.events {
				if e.at.Before(from) || !e.at.Before(to) {
					continue
				}
				if advID != "" && e.adv != advID {
					continue
				}
				if pubID != "" && e.pub != pubID { // JOIN: unmapped never surfaces publisher-side
					continue
				}
				k := cellKey{e.adv, e.camp, e.site, e.pub}
				c := cells[k]
				if c == nil {
					c = &struct {
						imps   int64
						micros int64
					}{}
					cells[k] = c
				}
				c.imps++
				c.micros += e.micros
			}
			rows := []map[string]any{}
			for k, c := range cells {
				rows = append(rows, map[string]any{
					"advertiserId": k.adv, "campaignId": k.camp, "siteId": k.site,
					"publisherId": k.pub, "impressions": c.imps, "grossMicros": c.micros,
				})
			}
			json.NewEncoder(w).Encode(map[string]any{
				"from": from.Format(time.RFC3339), "to": to.Format(time.RFC3339), "rows": rows,
			})
		case r.URL.Path == "/v1/internal/metering/unsettled":
			if f.failUnsettled {
				http.Error(w, "boom", http.StatusInternalServerError)
				return
			}
			var req struct {
				Rows []struct {
					AdvertiserID string `json:"advertiserId"`
					Since        string `json:"since"`
				} `json:"rows"`
			}
			if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			f.lastUnsettledSince = map[string]time.Time{}
			rows := []map[string]any{}
			for _, rr := range req.Rows {
				since, err := time.Parse(time.RFC3339, rr.Since)
				if err != nil {
					http.Error(w, err.Error(), http.StatusBadRequest)
					return
				}
				f.lastUnsettledSince[rr.AdvertiserID] = since
				var sum int64
				for _, e := range f.events {
					if e.adv == rr.AdvertiserID && !e.at.Before(since) {
						sum += e.micros
					}
				}
				rows = append(rows, map[string]any{"advertiserId": rr.AdvertiserID, "grossMicros": sum})
			}
			json.NewEncoder(w).Encode(map[string]any{"rows": rows})
		case r.URL.Path == "/v1/internal/metering/entities":
			since, err := time.Parse(time.RFC3339, r.URL.Query().Get("since"))
			if err != nil {
				http.Error(w, "bad since", http.StatusBadRequest)
				return
			}
			advs := map[string]time.Time{}
			pubs := map[string]time.Time{}
			for _, e := range f.events {
				if e.at.Before(since) {
					continue
				}
				if first, ok := advs[e.adv]; !ok || e.at.Before(first) {
					advs[e.adv] = e.at
				}
				if e.pub != "" {
					if first, ok := pubs[e.pub]; !ok || e.at.Before(first) {
						pubs[e.pub] = e.at
					}
				}
			}
			toRows := func(m map[string]time.Time) []map[string]any {
				rows := []map[string]any{}
				for id, first := range m {
					rows = append(rows, map[string]any{"id": id, "earliest": first.Format(time.RFC3339)})
				}
				return rows
			}
			json.NewEncoder(w).Encode(map[string]any{
				"advertisers": toRows(advs), "publishers": toRows(pubs),
			})
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

// makeSettler wires a settler against the fake core with a zone map and an
// injected clock; the returned *time.Time is the settable fake now.
func makeSettler(t *testing.T, svc *Service, serverURL string, zones map[string]string, marginBps int) (*Settler, *time.Time) {
	t.Helper()
	settler := NewSettler(svc, svc.pool, NewHTTPCoreClient(serverURL, "test-key"),
		func(context.Context, time.Time) (int, error) { return marginBps, nil })
	settler.SetEntityTimezoneLookup(func(_ context.Context, id string) (string, error) {
		return zones[id], nil
	})
	now := new(time.Time)
	settler.now = func() time.Time { return *now }
	return settler, now
}

func utc(y int, mo time.Month, d, h, m int) time.Time {
	return time.Date(y, mo, d, h, m, 0, 0, time.UTC)
}

// TestSettlerCrossZoneFullCycle is the flagship test: a Tokyo advertiser and
// a New York publisher settle the SAME events over different local-day
// windows; clearing must return to exactly zero (this is also the
// partition-invariance check — both sides sum per-event integer micros over
// different slicings), wallets and payables land to the micro, genesis
// cursors anchor before the first event, and a second pass is idempotent.
func TestSettlerCrossZoneFullCycle(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	core.add(
		fakeEvent{"advA", "campX", "site1", "pubP", utc(2026, 7, 9, 6, 0), 4_000_000},
		fakeEvent{"advA", "campX", "site1", "pubP", utc(2026, 7, 9, 7, 0), 6_000_000},
		fakeEvent{"advA", "campY", "site2", "pubQ", utc(2026, 7, 9, 8, 0), 5_500_000},
		fakeEvent{"advB", "campZ", "site1", "pubP", utc(2026, 7, 9, 9, 0), 2_000_000},
		fakeEvent{"advB", "campZ", "siteX", "", utc(2026, 7, 9, 10, 0), 2_000_000}, // unmapped site → skipped
	)
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	zones := map[string]string{
		"advA": "Asia/Tokyo",
		"pubP": "America/New_York",
		// advB, pubQ: unset = UTC
	}
	settler, now := makeSettler(t, svc, server.URL, zones, 1500)
	*now = utc(2026, 7, 11, 12, 0)

	// advA is funded but thinly: 16.00 covers the 15.50 spend with 0.50
	// left, under the 2×-trailing-average watermark → low_balance.
	// advB is never funded → suspended.
	if _, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advA", AmountMicros: 16_000_000, IdempotencyKey: "t-a1",
	}); err != nil {
		t.Fatalf("fund advA: %v", err)
	}

	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("first pass: %v", err)
	}

	// Genesis cursors anchor at the local midnight before each entity's
	// first event, then advance through every closed window.
	assertCursor := func(ownerType OwnerType, id string, want time.Time) {
		t.Helper()
		var until time.Time
		if err := pool.QueryRow(ctx, `
			SELECT settled_until FROM settlement_cursors WHERE owner_type = $1 AND owner_id = $2`,
			ownerType, id).Scan(&until); err != nil {
			t.Fatalf("cursor %s %s: %v", ownerType, id, err)
		}
		if !until.Equal(want) {
			t.Fatalf("cursor %s %s = %s, want %s", ownerType, id, until, want)
		}
	}
	assertCursor(OwnerAdvertiser, "advA", utc(2026, 7, 10, 15, 0)) // through Tokyo midnight 07-11 JST
	assertCursor(OwnerAdvertiser, "advB", utc(2026, 7, 11, 0, 0))
	assertCursor(OwnerPublisher, "pubP", utc(2026, 7, 11, 4, 0)) // through NY midnight 07-11 EDT
	assertCursor(OwnerPublisher, "pubQ", utc(2026, 7, 11, 0, 0))

	// advB's UTC 07-09 window: one cell booked, one skipped (unmapped).
	var skipped int
	if err := pool.QueryRow(ctx, `
		SELECT rows_skipped FROM settlement_windows
		WHERE owner_type = 'advertiser' AND owner_id = 'advB' AND window_from = $1`,
		utc(2026, 7, 9, 0, 0)).Scan(&skipped); err != nil {
		t.Fatalf("advB window: %v", err)
	}
	if skipped != 1 {
		t.Fatalf("advB skipped = %d, want 1", skipped)
	}

	// Wallets: advA charged 15.50 → 0.50 left, low_balance; advB charged
	// only the mapped 2.00 (nobody is billed for the unmapped site) →
	// −2.00, suspended.
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

	// Clearing drained to exactly zero: both sides booked the same 17.50 of
	// events over DIFFERENT window partitions.
	rec, err := svc.Reconcile(ctx)
	if err != nil || !rec.OK {
		t.Fatalf("reconcile: %+v err=%v", rec, err)
	}
	if rec.ClearingMicros != 0 {
		t.Fatalf("clearing = %d, want 0", rec.ClearingMicros)
	}
	if rec.RevenueMicros != 1_500_000+825_000+300_000 {
		t.Fatalf("revenue = %d", rec.RevenueMicros)
	}
	if rec.CashMicros != rec.WalletsMicros+rec.PayableMicros+rec.RevenueMicros+rec.ClearingMicros {
		t.Fatalf("identity broken: %+v", rec)
	}

	// Second pass: fully idempotent — no re-suspend, balances unchanged.
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("second pass: %v", err)
	}
	if len(core.suspends) != 1 {
		t.Fatalf("suspend re-fired: %v", core.suspends)
	}
	if a2, _ := svc.GetAccount(ctx, OwnerAdvertiser, "advA"); a2.BalanceMicros != 500_000 {
		t.Fatalf("advA balance changed on idempotent pass: %d", a2.BalanceMicros)
	}

	// Funding the suspended wallet by adjustment → the next pass resumes.
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
}

// TestClearingInTransit: after the advertiser's local day closes but before
// the publisher's does, the gross sits in clearing; the publisher's window
// closing drains it to zero.
func TestClearingInTransit(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	core.add(fakeEvent{"advT", "c", "s", "pubN", utc(2026, 7, 9, 10, 0), 7_000_000})
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	zones := map[string]string{"advT": "Asia/Tokyo", "pubN": "America/New_York"}
	settler, now := makeSettler(t, svc, server.URL, zones, 1500)

	// 20:00Z on 07-09: Tokyo's 07-09 day ([07-08 15:00Z, 07-09 15:00Z))
	// closed at 16:00Z; New York's 07-09 day runs until 04:00Z on 07-10.
	*now = utc(2026, 7, 9, 20, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("advertiser-side pass: %v", err)
	}
	c, _ := svc.GetAccount(ctx, OwnerPlatform, PlatformClearing)
	if c.BalanceMicros != 7_000_000 {
		t.Fatalf("clearing in transit = %d, want 7000000", c.BalanceMicros)
	}
	if _, err := svc.GetAccount(ctx, OwnerPublisher, "pubN"); err == nil {
		t.Fatal("publisher credited before its window closed")
	}

	// 06:00Z on 07-10: New York's day closed at 05:00Z → drains.
	*now = utc(2026, 7, 10, 6, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("publisher-side pass: %v", err)
	}
	c, _ = svc.GetAccount(ctx, OwnerPlatform, PlatformClearing)
	if c.BalanceMicros != 0 {
		t.Fatalf("clearing after both sides = %d, want 0", c.BalanceMicros)
	}
	p, _ := svc.GetAccount(ctx, OwnerPublisher, "pubN")
	if p.BalanceMicros != 5_950_000 { // net of 7.00 at 15%
		t.Fatalf("payable = %d", p.BalanceMicros)
	}
}

// TestZoneChange: moving an entity's zone produces exactly one short/long
// window (chained on the cursor instant), events are booked exactly once,
// and colliding local_date labels group cleanly in the monthly rollup.
func TestZoneChange(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	// One event per UTC 6-hour block across two days, 1.00 each.
	for d := 9; d <= 10; d++ {
		for _, h := range []int{2, 8, 14, 20} {
			core.add(fakeEvent{"advZ", "c", "s", "pubU", utc(2026, 7, d, h, 0), 1_000_000})
		}
	}
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	zones := map[string]string{"advZ": ""} // starts UTC
	settler, now := makeSettler(t, svc, server.URL, zones, 1500)

	// Settle through UTC midnight 07-10 (covers the four 07-09 events).
	*now = utc(2026, 7, 10, 2, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("pass 1: %v", err)
	}

	// Operator moves the org to New York (west shift). The next window is
	// [07-10 00:00Z, 07-10 04:00Z) — one 4h short window whose label
	// (07-09 in New York) COLLIDES with the previous window's label.
	zones["advZ"] = "America/New_York"
	*now = utc(2026, 7, 10, 5, 30)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("pass 2: %v", err)
	}
	var from, to time.Time
	if err := pool.QueryRow(ctx, `
		SELECT window_from, window_to FROM settlement_windows
		WHERE owner_type = 'advertiser' AND owner_id = 'advZ'
		ORDER BY window_from DESC LIMIT 1`).Scan(&from, &to); err != nil {
		t.Fatalf("latest window: %v", err)
	}
	if !from.Equal(utc(2026, 7, 10, 0, 0)) || !to.Equal(utc(2026, 7, 10, 4, 0)) {
		t.Fatalf("short window = [%s, %s), want [07-10 00:00Z, 07-10 04:00Z)", from, to)
	}

	// Then a full NY day [04:00Z, 07-11 04:00Z) closes.
	*now = utc(2026, 7, 11, 5, 30)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("pass 3: %v", err)
	}

	// Gapless coverage: windows chain exactly from genesis to the cursor.
	rows, err := pool.Query(ctx, `
		SELECT window_from, window_to FROM settlement_windows
		WHERE owner_type = 'advertiser' AND owner_id = 'advZ' ORDER BY window_from`)
	if err != nil {
		t.Fatalf("windows: %v", err)
	}
	var prev time.Time
	first := true
	for rows.Next() {
		var f, tt time.Time
		if err := rows.Scan(&f, &tt); err != nil {
			t.Fatalf("scan: %v", err)
		}
		if !first && !f.Equal(prev) {
			t.Fatalf("gap/overlap: window starts %s, previous ended %s", f, prev)
		}
		prev = tt
		first = false
	}
	rows.Close()

	// Every event booked exactly once: total charged == total event micros
	// through the cursor (8 events × 1.00).
	var totalGross int64
	pool.QueryRow(ctx, `
		SELECT COALESCE(SUM(gross_micros), 0) FROM advertiser_settlements
		WHERE advertiser_id = 'advZ'`).Scan(&totalGross)
	if totalGross != 8_000_000 {
		t.Fatalf("total charged = %d, want 8000000 (each event exactly once)", totalGross)
	}

	// The colliding labels (UTC 07-09 window and the NY-labeled 07-09 short
	// window) group into one monthly row without complaint.
	months, err := svc.MonthlySettled(ctx, OwnerAdvertiser, "advZ", 12)
	if err != nil || len(months) != 1 || months[0].GrossMicros != 8_000_000 {
		t.Fatalf("monthly rollup = %+v err=%v", months, err)
	}
}

// TestRetroReplayAfterZoneChange is the double-pay regression test: retro
// re-settlement must replay RECORDED window boundaries, never recompute
// them from the current zone — recomputing would slice the same events
// differently under fresh idempotency keys and book them twice.
// (2027 dates so the replay's settled_at cutoff sees the windows as old.)
func TestRetroReplayAfterZoneChange(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	for _, h := range []int{3, 9, 15, 21} {
		core.add(fakeEvent{"advR", "c", "s", "pubU", utc(2027, 7, 9, h, 0), 1_000_000})
	}
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	zones := map[string]string{"advR": ""}
	settler, now := makeSettler(t, svc, server.URL, zones, 1500)

	*now = utc(2027, 7, 10, 2, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("pass 1: %v", err)
	}
	var charged int64
	pool.QueryRow(ctx, `SELECT COALESCE(SUM(gross_micros),0) FROM advertiser_settlements WHERE advertiser_id='advR'`).Scan(&charged)
	if charged != 4_000_000 {
		t.Fatalf("initial charge = %d", charged)
	}

	// Zone changes, then a pass whose retro replay revisits the recorded
	// UTC windows. If replay recomputed boundaries in Tokyo terms, the
	// same events would book again under different window_from keys.
	zones["advR"] = "Asia/Tokyo"
	*now = utc(2027, 7, 10, 4, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("replay pass: %v", err)
	}
	pool.QueryRow(ctx, `SELECT COALESCE(SUM(gross_micros),0) FROM advertiser_settlements WHERE advertiser_id='advR'`).Scan(&charged)
	if charged != 4_000_000 {
		t.Fatalf("charge after zone-change replay = %d, want 4000000 (double-pay!)", charged)
	}
	rec, _ := svc.Reconcile(ctx)
	if !rec.OK {
		t.Fatalf("reconcile: %+v", rec)
	}
}

// TestUnmappedSiteSkippedThenRescued: traffic on a site with no publisher
// mapping bills NOBODY (today's rule, user-confirmed) — and once the
// mapping is fixed within the retro span, the replay books the advertiser
// side and discovery+genesis books the publisher side; clearing ends at 0.
// (2027 dates so the retro replay fires.)
func TestUnmappedSiteSkippedThenRescued(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	core.add(fakeEvent{"advU", "c", "siteU", "", utc(2027, 7, 9, 10, 0), 3_000_000})
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler, now := makeSettler(t, svc, server.URL, map[string]string{}, 1500)

	*now = utc(2027, 7, 10, 2, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("pass 1: %v", err)
	}
	// Nobody billed; the skip is counted and clearing stays clean.
	if _, err := svc.GetAccount(ctx, OwnerAdvertiser, "advU"); err == nil {
		t.Fatal("advertiser billed for unmapped-site traffic")
	}
	var skipped int
	pool.QueryRow(ctx, `
		SELECT rows_skipped FROM settlement_windows
		WHERE owner_type='advertiser' AND owner_id='advU' AND window_from=$1`,
		utc(2027, 7, 9, 0, 0)).Scan(&skipped)
	if skipped != 1 {
		t.Fatalf("skipped = %d, want 1", skipped)
	}

	// Operator fixes publisher_sites → the next pass's retro replay books
	// the advertiser cell, and entity discovery gives the publisher a
	// genesis covering the event.
	core.mapSite("siteU", "pubU")
	*now = utc(2027, 7, 10, 4, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("rescue pass: %v", err)
	}
	a, err := svc.GetAccount(ctx, OwnerAdvertiser, "advU")
	if err != nil || a.BalanceMicros != -3_000_000 {
		t.Fatalf("advertiser after rescue = %+v err=%v", a, err)
	}
	p, err := svc.GetAccount(ctx, OwnerPublisher, "pubU")
	if err != nil || p.BalanceMicros != 2_550_000 { // net of 3.00 at 15%
		t.Fatalf("publisher after rescue = %+v err=%v", p, err)
	}
	c, _ := svc.GetAccount(ctx, OwnerPlatform, PlatformClearing)
	if c.BalanceMicros != 0 {
		t.Fatalf("clearing after rescue = %d, want 0", c.BalanceMicros)
	}
}

// TestGenesisNoRetroactiveBilling: a discovered entity's genesis cursor
// anchors at the local midnight before its first billable event WITHIN the
// discovery span — older history is never billed.
func TestGenesisNoRetroactiveBilling(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	core.add(
		fakeEvent{"advG", "c", "s", "pubU", utc(2026, 7, 1, 12, 0), 9_000_000}, // ancient, outside discovery span
		fakeEvent{"advG", "c", "s", "pubU", utc(2026, 7, 9, 12, 0), 1_000_000},
	)
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler, now := makeSettler(t, svc, server.URL, map[string]string{}, 1500)
	*now = utc(2026, 7, 10, 2, 0)
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("run: %v", err)
	}
	// Only the recent event billed: genesis = 07-09 00:00Z.
	a, err := svc.GetAccount(ctx, OwnerAdvertiser, "advG")
	if err != nil || a.BalanceMicros != -1_000_000 {
		t.Fatalf("advG = %+v err=%v (pre-genesis history must stay unbilled)", a, err)
	}
	var until time.Time
	pool.QueryRow(ctx, `
		SELECT settled_until FROM settlement_cursors WHERE owner_type='advertiser' AND owner_id='advG'`).Scan(&until)
	if !until.Equal(utc(2026, 7, 10, 0, 0)) {
		t.Fatalf("cursor = %s", until)
	}
}

// TestRetentionClampPerEntity: a cursor stalled past the metering retention
// jumps forward (alerting) instead of asking core for expired data.
func TestRetentionClampPerEntity(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler, now := makeSettler(t, svc, server.URL, map[string]string{}, 0)
	*now = utc(2026, 7, 10, 6, 0)

	if _, err := pool.Exec(ctx, `
		INSERT INTO settlement_cursors (owner_type, owner_id, settled_until, timezone)
		VALUES ('advertiser', 'advStale', $1, '')`, utc(2026, 5, 10, 0, 0),
	); err != nil {
		t.Fatalf("seed stale cursor: %v", err)
	}
	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("run: %v", err)
	}
	var until time.Time
	var windows int
	pool.QueryRow(ctx, `
		SELECT settled_until FROM settlement_cursors WHERE owner_id = 'advStale'`).Scan(&until)
	pool.QueryRow(ctx, `
		SELECT COUNT(*) FROM settlement_windows WHERE owner_id = 'advStale'`).Scan(&windows)
	// Clamped to now−29d (06:00Z on 06-11), then settled through 07-10
	// midnight: an 18h partial window plus 28 full days (06-12 … 07-09).
	if !until.Equal(utc(2026, 7, 10, 0, 0)) {
		t.Fatalf("cursor after clamp = %s, want 2026-07-10T00:00Z", until)
	}
	if windows != 29 {
		t.Fatalf("windows = %d, want 29 (partial + 28 full)", windows)
	}
	var oldest time.Time
	pool.QueryRow(ctx, `
		SELECT MIN(window_from) FROM settlement_windows WHERE owner_id = 'advStale'`).Scan(&oldest)
	if !oldest.Equal(utc(2026, 6, 11, 6, 0)) {
		t.Fatalf("oldest window from = %s, want 2026-06-11T06:00Z (the clamp point)", oldest)
	}
}

// TestCrashMidWindowRetries: a window where some cells already posted (a
// prior pass crashed between cells) retries cleanly — existing cells dedupe
// and the journal totals re-read from the table, not pass-local counters.
func TestCrashMidWindowRetries(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	core.add(
		fakeEvent{"advC", "camp1", "s1", "pubU", utc(2026, 7, 9, 8, 0), 2_000_000},
		fakeEvent{"advC", "camp2", "s2", "pubU", utc(2026, 7, 9, 9, 0), 3_000_000},
	)
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	settler, now := makeSettler(t, svc, server.URL, map[string]string{}, 1500)
	*now = utc(2026, 7, 10, 2, 0)

	// Simulate the crashed prior pass: one cell of the 07-09 UTC window
	// already posted, no window marker, no cursor advance.
	window := SettlementWindow{
		From: utc(2026, 7, 9, 0, 0), To: utc(2026, 7, 10, 0, 0),
		LocalDate: utc(2026, 7, 9, 0, 0), Timezone: "",
	}
	if _, err := svc.RecordAdvertiserSettlement(ctx, AdvSettlementParams{
		Window: window, AdvertiserID: "advC", CampaignID: "camp1", SiteID: "s1",
		PublisherID: "pubU", Impressions: 1, GrossMicros: 2_000_000,
	}); err != nil {
		t.Fatalf("pre-post cell: %v", err)
	}
	if _, err := pool.Exec(ctx, `
		INSERT INTO settlement_cursors (owner_type, owner_id, settled_until, timezone)
		VALUES ('advertiser', 'advC', $1, '')`, window.From,
	); err != nil {
		t.Fatalf("seed cursor: %v", err)
	}

	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("retry pass: %v", err)
	}
	// Exactly one transaction per cell (the pre-posted one deduped), and
	// the window journal counts BOTH cells.
	var txns int
	pool.QueryRow(ctx, `
		SELECT COUNT(*) FROM ledger_transactions WHERE kind = 'settlement'
		AND idempotency_key LIKE 'settle:adv:advC:%'`).Scan(&txns)
	if txns != 2 {
		t.Fatalf("settlement txns = %d, want 2", txns)
	}
	var rowsSettled int
	var gross int64
	pool.QueryRow(ctx, `
		SELECT rows_settled, gross_micros FROM settlement_windows
		WHERE owner_id = 'advC' AND window_from = $1`, window.From).Scan(&rowsSettled, &gross)
	if rowsSettled != 2 || gross != 5_000_000 {
		t.Fatalf("window journal = %d rows %d gross, want 2 rows 5000000", rowsSettled, gross)
	}
	a, _ := svc.GetAccount(ctx, OwnerAdvertiser, "advC")
	if a.BalanceMicros != -5_000_000 {
		t.Fatalf("charged = %d, want -5000000 (no double charge)", a.BalanceMicros)
	}
}

// TestPerAdvertiserUnsettledAndDegraded ports the intraday wallet tests:
// each advertiser's unsettled spend is queried from its OWN cursor, an
// unsettled-metering outage never resumes anyone, and a brand-new unfunded
// advertiser with traffic is suspended within one tick.
func TestPerAdvertiserUnsettledAndDegraded(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	core := &fakeCore{}
	// advX: settled cursor mid-history; 12.00 of unsettled spend today.
	// advNEW: never touched the ledger, 0.75 unsettled — must suspend.
	core.add(
		fakeEvent{"advX", "c", "s", "pubU", utc(2026, 7, 10, 8, 0), 5_000_000},
		fakeEvent{"advX", "c", "s", "pubU", utc(2026, 7, 10, 9, 0), 7_000_000},
		fakeEvent{"advNEW", "c", "s", "pubU", utc(2026, 7, 10, 10, 0), 750_000},
	)
	server := httptest.NewServer(core.handler(t))
	defer server.Close()

	zones := map[string]string{"advX": "Asia/Tokyo"}
	settler, now := makeSettler(t, svc, server.URL, zones, 1500)
	*now = utc(2026, 7, 10, 18, 0)

	if _, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advX", AmountMicros: 10_000_000, IdempotencyKey: "x-t1",
	}); err != nil {
		t.Fatalf("fund advX: %v", err)
	}

	if err := settler.RunOnce(ctx); err != nil {
		t.Fatalf("run: %v", err)
	}

	// The fake must have received DIFFERENT since instants: advX's Tokyo
	// cursor (07-10 15:00Z after settling its closed local day) vs
	// advNEW's UTC genesis (07-10 00:00Z).
	core.mu.Lock()
	sinceX, okX := core.lastUnsettledSince["advX"]
	sinceNew, okNew := core.lastUnsettledSince["advNEW"]
	core.mu.Unlock()
	if !okX || !okNew {
		t.Fatalf("unsettled since map incomplete: %v", core.lastUnsettledSince)
	}
	if !sinceX.Equal(utc(2026, 7, 10, 15, 0)) || !sinceNew.Equal(utc(2026, 7, 10, 0, 0)) {
		t.Fatalf("per-advertiser since: advX=%s advNEW=%s", sinceX, sinceNew)
	}

	// advX: settled balance = 10 − 12 (its Tokyo day [07-09 15:00Z,
	// 07-10 15:00Z) covered both events) → wallet −2, suspended at
	// settlement. advNEW: unsettled 0.75, no wallet → suspended too.
	x, _ := svc.GetAccount(ctx, OwnerAdvertiser, "advX")
	if x.Status != StatusSuspended || x.BalanceMicros != -2_000_000 {
		t.Fatalf("advX = %+v", x)
	}
	nw, err := svc.GetAccount(ctx, OwnerAdvertiser, "advNEW")
	if err != nil || nw.Status != StatusSuspended || nw.BalanceMicros != 0 {
		t.Fatalf("advNEW = %+v err=%v (row must exist, suspended, zero balance)", nw, err)
	}

	// Fund advX enough to go positive, but break unsettled metering: the
	// degraded pass must NOT resume on stale data.
	if _, err := svc.RecordTopup(ctx, TopupParams{
		AdvertiserID: "advX", AmountMicros: 5_000_000, IdempotencyKey: "x-t2",
	}); err != nil {
		t.Fatalf("fund advX 2: %v", err)
	}
	// (Top-up reactivates the wallet status inline; force it back to
	// suspended to isolate the degraded-resume path, as if the top-up had
	// not covered the projection.)
	if err := svc.SetAccountStatus(ctx, OwnerAdvertiser, "advX", StatusSuspended); err != nil {
		t.Fatalf("re-suspend: %v", err)
	}
	core.mu.Lock()
	core.failUnsettled = true
	core.mu.Unlock()
	if err := settler.EvaluateWallets(ctx); err != nil {
		t.Fatalf("degraded evaluate: %v", err)
	}
	if len(core.resumes) != 0 {
		t.Fatalf("degraded pass resumed on stale data: %v", core.resumes)
	}

	// Outage over → positive projection resumes.
	core.mu.Lock()
	core.failUnsettled = false
	core.mu.Unlock()
	if err := settler.EvaluateWallets(ctx); err != nil {
		t.Fatalf("evaluate: %v", err)
	}
	if len(core.resumes) != 1 || core.resumes[0] != "advX" {
		t.Fatalf("resume not fired: %v", core.resumes)
	}
}

// TestZeroLegSettlements: extreme margins collapse a leg to zero — the
// posting must drop that leg instead of tripping leg validation (the old
// combined RecordSettlement had this latent bug at net==0).
func TestZeroLegSettlements(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()

	window := SettlementWindow{
		From: utc(2026, 7, 9, 0, 0), To: utc(2026, 7, 10, 0, 0),
		LocalDate: utc(2026, 7, 9, 0, 0), Timezone: "",
	}
	// 1µ gross at 99.99%: fee rounds to 1µ, net == 0 → publisher leg dropped.
	res, err := svc.RecordPublisherSettlement(ctx, PubSettlementParams{
		Window: window, PublisherID: "pubZ", SiteID: "s", CampaignID: "c",
		AdvertiserID: "advZ", Impressions: 1, GrossMicros: 1, MarginBps: 9999,
	})
	if err != nil {
		t.Fatalf("net==0 settlement: %v", err)
	}
	if res.NetMicros != 0 || res.FeeMicros != 1 {
		t.Fatalf("split = net %d fee %d", res.NetMicros, res.FeeMicros)
	}
	// Zero margin: fee == 0 → fee leg dropped.
	window2 := window
	window2.From = window.To
	window2.To = window.To.Add(24 * time.Hour)
	if _, err := svc.RecordPublisherSettlement(ctx, PubSettlementParams{
		Window: window2, PublisherID: "pubZ", SiteID: "s", CampaignID: "c",
		AdvertiserID: "advZ", Impressions: 1, GrossMicros: 1_000_000, MarginBps: 0,
	}); err != nil {
		t.Fatalf("fee==0 settlement: %v", err)
	}
	rec, err := svc.Reconcile(ctx)
	if err != nil || !rec.OK {
		t.Fatalf("reconcile: %+v err=%v", rec, err)
	}
}
