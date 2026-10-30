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
