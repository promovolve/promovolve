package handler

import (
	"testing"
	"time"
)

func TestDaysLeft(t *testing.T) {
	now := time.Date(2026, 10, 20, 12, 0, 0, 0, time.UTC)
	for _, tc := range []struct {
		release time.Time
		want    int
	}{
		{now.Add(14 * 24 * time.Hour), 14},
		{now.Add(13*24*time.Hour + time.Hour), 14},
		{now.Add(time.Minute), 1},
		{now, 0},
		{now.Add(-time.Hour), 0},
	} {
		if got := daysLeft(tc.release, now); got != tc.want {
			t.Errorf("daysLeft(%v) = %d, want %d", tc.release.Sub(now), got, tc.want)
		}
	}
}
