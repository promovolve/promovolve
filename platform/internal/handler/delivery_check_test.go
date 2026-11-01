package handler

import "testing"

func TestSuspensionReason(t *testing.T) {
	cases := []struct {
		orgSuspended  bool
		balanceMicros int64
		want          string
	}{
		{true, 500, "org"},
		{false, 0, "wallet"},
		{false, -1, "wallet"},
		{false, 500, "other"},
	}
	for _, c := range cases {
		if got := suspensionReason(c.orgSuspended, c.balanceMicros); got != c.want {
			t.Errorf("suspensionReason(%v, %d) = %q, want %q", c.orgSuspended, c.balanceMicros, got, c.want)
		}
	}
}
