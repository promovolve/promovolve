package billing

import (
	"context"
	"testing"
	"time"
)

// TestDailyCharged sums settlements per local day for one advertiser and
// drops days outside the window.
func TestDailyCharged(t *testing.T) {
	pool := testPool(t)
	svc := NewService(pool)
	ctx := context.Background()
	today := time.Now().UTC().Truncate(24 * time.Hour)
	ins := func(adv, camp string, day time.Time, gross int64) {
		t.Helper()
		if _, err := pool.Exec(ctx, `
			INSERT INTO advertiser_settlements (advertiser_id, campaign_id, site_id, window_from, window_to, local_date, impressions, gross_micros)
			VALUES ($1, $2, 's', $3, $3, $5::date, 1, $4)`, adv, camp, day, gross, day.Format("2006-01-02")); err != nil {
			t.Fatal(err)
		}
	}
	yesterday := today.AddDate(0, 0, -1)
	ins("a1", "c1", yesterday, 2_000_000)
	ins("a1", "c2", yesterday, 3_000_000)
	ins("a1", "c1", today.AddDate(0, 0, -3), 1_000_000)
	ins("a1", "c1", today.AddDate(0, 0, -40), 9_000_000) // outside the window
	ins("a2", "c9", yesterday, 7_000_000)                // another advertiser

	got, err := svc.DailyCharged(ctx, "a1", 15)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]int64{
		yesterday.Format("2006-01-02"):               5_000_000,
		today.AddDate(0, 0, -3).Format("2006-01-02"): 1_000_000,
	}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for d, v := range want {
		if got[d] != v {
			t.Errorf("%s = %d, want %d", d, got[d], v)
		}
	}
}
