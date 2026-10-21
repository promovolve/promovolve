package handler

import (
	"strings"
	"testing"
	"time"

	"github.com/hanishi/promovolve/platform/internal/i18n"
)

func TestBuildForecast(t *testing.T) {
	now := time.Date(2026, 10, 21, 12, 0, 0, 0, time.UTC) // 12h elapsed
	flat := func(perHour float64, upTo int) (h [24]float64) {
		for i := 0; i < upTo; i++ {
			h[i] = perHour
		}
		return
	}
	cases := []struct {
		name      string
		hours     [24]float64
		budget    float64
		active    bool
		outlook   string
		alert     string
		projected int // number of projected cells
	}{
		{"budget reached", flat(10, 12), 100, true, "Budget reached during the 09:00 hour", "warning", 0},
		{"runs out exactly at midnight", flat(1, 12), 24, true, "About $24.00 by midnight", "", 11},
		{"reaches budget at 18:00", flat(1, 12), 18, true, "Spends its full budget around 18:00", "", 5},
		{"under-delivers", flat(1, 12), 100, true, "About", "", 11},
		{"no delivery", flat(0, 0), 100, true, "No delivery yet today", "", 0},
		{"paused", flat(1, 12), 100, false, "Paused", "", 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := buildForecast(i18n.LangEN, tc.hours, tc.budget, now, tc.active)
			if !strings.HasPrefix(f.Outlook, tc.outlook) || f.Alert != tc.alert {
				t.Errorf("outlook %q alert %q, want %q %q", f.Outlook, f.Alert, tc.outlook, tc.alert)
			}
			n := 0
			for _, c := range f.Cells {
				if c.Projected {
					n++
				}
			}
			if n != tc.projected {
				t.Errorf("projected cells = %d, want %d", n, tc.projected)
			}
		})
	}
	if got := intensity(1, 1); got != 3 {
		t.Errorf("intensity at the even pace = %d, want 3", got)
	}
}

func TestWalletForecast(t *testing.T) {
	today := time.Date(2026, 10, 21, 12, 0, 0, 0, time.UTC)
	daily := func(v float64) map[string]float64 {
		m := map[string]float64{}
		for i := 1; i <= walletDays; i++ {
			m[today.AddDate(0, 0, -i).Format("2006-01-02")] = v
		}
		return m
	}
	cases := []struct {
		name             string
		charged          map[string]float64
		balance, pending float64
		runway           int
		alert, outlook   string
	}{
		{"healthy", daily(10), 400, 10, 39, "", "Lasts about 39 days"},
		{"low", daily(10), 60, 10, 5, "advisory", "Lasts about 5 days"},
		{"tomorrow", daily(10), 15, 10, 0, "warning", "Runs out tomorrow"},
		{"used up", daily(10), 8, 10, 0, "warning", "The balance is used up"},
		{"no spend", daily(0), 100, 0, -1, "", "No charges in the last 14 days"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f, runway := walletForecast(i18n.LangEN, tc.charged, tc.balance, tc.pending, today)
			if runway != tc.runway || f.Alert != tc.alert || !strings.HasPrefix(f.Outlook, tc.outlook) {
				t.Errorf("runway %d alert %q outlook %q, want %d %q %q", runway, f.Alert, f.Outlook, tc.runway, tc.alert, tc.outlook)
			}
			if len(f.Cells) != 2*walletDays+1 {
				t.Errorf("%d cells, want %d", len(f.Cells), 2*walletDays+1)
			}
		})
	}
}
