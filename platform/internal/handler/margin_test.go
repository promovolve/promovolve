package handler

import "testing"

func TestParseMarginPercent(t *testing.T) {
	valid := map[string]int{
		"15":    1500,
		"15.5":  1550,
		"15.25": 1525,
		"0":     0,
		"99.99": 9999,
		" 12 ":  1200,
	}
	for in, want := range valid {
		got, err := parseMarginPercent(in)
		if err != nil {
			t.Errorf("parseMarginPercent(%q) unexpected error: %v", in, err)
			continue
		}
		if got != want {
			t.Errorf("parseMarginPercent(%q) = %d, want %d", in, got, want)
		}
	}

	for _, in := range []string{"", "-1", "100", "150", "abc", "NaN"} {
		if _, err := parseMarginPercent(in); err == nil {
			t.Errorf("parseMarginPercent(%q) expected error, got none", in)
		}
	}
}

func TestFormatBps(t *testing.T) {
	cases := map[int]string{
		1500: "15",
		1550: "15.5",
		1525: "15.25",
		0:    "0",
		9999: "99.99",
	}
	for bps, want := range cases {
		if got := formatBps(bps); got != want {
			t.Errorf("formatBps(%d) = %q, want %q", bps, got, want)
		}
	}
}
