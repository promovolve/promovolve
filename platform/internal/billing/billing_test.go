package billing

import (
	"testing"
	"time"
)

func TestSplitFee(t *testing.T) {
	cases := []struct {
		gross   int64
		bps     int
		wantNet int64
		wantFee int64
	}{
		{1_000_000, 1500, 850_000, 150_000},     // $1 at 15%
		{1_000_000, 0, 1_000_000, 0},            // zero margin
		{100, 9999, 0, 100},                     // 99.99% of a tiny amount: fee 99.99 rounds to 100
		{333_333, 1500, 283_333, 50_000},        // exact fee 49999.95 rounds half-up to 50000
		{1, 5000, 0, 1},                         // tie 0.5 rounds up
		{8_500_000, 1250, 7_437_500, 1_062_500}, // mirrors the settings.Net test case
	}
	for _, c := range cases {
		net, fee := SplitFee(c.gross, c.bps)
		if net != c.wantNet || fee != c.wantFee {
			t.Errorf("SplitFee(%d, %d) = (%d, %d), want (%d, %d)",
				c.gross, c.bps, net, fee, c.wantNet, c.wantFee)
		}
		if net+fee != c.gross {
			t.Errorf("SplitFee(%d, %d): net+fee = %d, must equal gross exactly",
				c.gross, c.bps, net+fee)
		}
	}
}

func TestDollarsToMicros(t *testing.T) {
	cases := map[float64]int64{
		0:         0,
		1:         1_000_000,
		24.86:     24_860_000,
		0.0001:    100,       // one 4dp unit (cpm/1000 precision)
		1.2345678: 1_234_568, // sub-micro rounds
	}
	for in, want := range cases {
		if got := DollarsToMicros(in); got != want {
			t.Errorf("DollarsToMicros(%v) = %d, want %d", in, got, want)
		}
	}
	if d := Dollars(24_860_000); d != 24.86 {
		t.Errorf("Dollars(24860000) = %v, want 24.86", d)
	}
}

func TestSettlementKey(t *testing.T) {
	day := time.Date(2026, 7, 3, 23, 59, 0, 0, time.UTC)
	got := SettlementKey(day, "advA", "campB", "siteC")
	want := "settle:2026-07-03:advA:campB:siteC"
	if got != want {
		t.Errorf("SettlementKey = %q, want %q", got, want)
	}
	// A non-UTC time for the same instant must produce the same key.
	jst := time.FixedZone("JST", 9*3600)
	if k := SettlementKey(day.In(jst), "advA", "campB", "siteC"); k != want {
		t.Errorf("SettlementKey in JST = %q, want %q", k, want)
	}
}

func TestValidateLegs(t *testing.T) {
	ok := []Leg{
		{OwnerAdvertiser, "a", 100},
		{OwnerPublisher, "p", -85},
		{OwnerPlatform, PlatformRevenue, -15},
	}
	if err := validateLegs(ok); err != nil {
		t.Errorf("balanced legs rejected: %v", err)
	}

	bad := map[string][]Leg{
		"unbalanced": {{OwnerAdvertiser, "a", 100}, {OwnerPublisher, "p", -85}},
		"single leg": {{OwnerAdvertiser, "a", 100}},
		"zero leg":   {{OwnerAdvertiser, "a", 0}, {OwnerPublisher, "p", 0}},
		"missing id": {{OwnerAdvertiser, "", 100}, {OwnerPublisher, "p", -100}},
		"empty":      {},
	}
	for name, legs := range bad {
		if err := validateLegs(legs); err == nil {
			t.Errorf("%s legs accepted, want error", name)
		}
	}
}

func TestDebitNormal(t *testing.T) {
	if !debitNormal(OwnerPlatform, PlatformCash) {
		t.Error("platform cash must be debit-normal (asset)")
	}
	for _, c := range []struct {
		t  OwnerType
		id string
	}{
		{OwnerAdvertiser, "a"},
		{OwnerPublisher, "p"},
		{OwnerPlatform, PlatformRevenue},
	} {
		if debitNormal(c.t, c.id) {
			t.Errorf("%s/%s must be credit-normal", c.t, c.id)
		}
	}
}
