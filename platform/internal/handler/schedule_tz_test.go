package handler

// Campaign schedule times are entered and displayed in the advertiser's
// account timezone but stored as UTC instants. These tests pin the
// conversion both ways: a JST wall-clock means that wall-clock in JST,
// explicit-zone values pass through, and display formatting feeds back
// into parsing as the identity (what you see in the edit form is what
// saving it stores).

import (
	"testing"
	"time"
)

func TestParseScheduleInputAccountZone(t *testing.T) {
	jst, err := time.LoadLocation("Asia/Tokyo")
	if err != nil {
		t.Fatalf("load Asia/Tokyo: %v", err)
	}

	cases := []struct {
		name string
		in   string
		loc  *time.Location
		want string
	}{
		// The incident shape: "start July 22 at midnight" typed by a JST
		// advertiser must mean JST midnight (= 15:00 UTC the day before),
		// not UTC midnight (= 09:00 JST).
		{"jst wall clock", "2026-07-22T00:00", jst, "2026-07-21T15:00:00Z"},
		{"jst evening", "2026-07-22T21:30", jst, "2026-07-22T12:30:00Z"},
		{"utc fallback zone", "2026-07-22T00:00", time.UTC, "2026-07-22T00:00:00Z"},
		{"with seconds", "2026-07-22T00:00:30", jst, "2026-07-21T15:00:30Z"},
		// Values already carrying a zone are not reinterpreted.
		{"explicit Z passes through", "2026-07-22T00:00:00Z", jst, "2026-07-22T00:00:00Z"},
		{"explicit offset passes through", "2026-07-22T00:00:00+09:00", jst, "2026-07-22T00:00:00+09:00"},
	}
	for _, c := range cases {
		if got := parseScheduleInput(c.in, c.loc); got != c.want {
			t.Errorf("%s: parseScheduleInput(%q) = %q, want %q", c.name, c.in, got, c.want)
		}
	}
}

func TestScheduleDisplayParseRoundTrip(t *testing.T) {
	jst, err := time.LoadLocation("Asia/Tokyo")
	if err != nil {
		t.Fatalf("load Asia/Tokyo: %v", err)
	}

	// The campaigns page renders StartAtLocal with this exact layout in
	// the account zone; submitting the edit form unchanged runs it back
	// through parseScheduleInput. That loop must be the identity — a
	// no-op edit must not move the schedule.
	stored := time.Date(2026, 7, 21, 15, 0, 0, 0, time.UTC) // = Jul 22 00:00 JST
	formValue := stored.In(jst).Format("2006-01-02T15:04")
	if formValue != "2026-07-22T00:00" {
		t.Fatalf("display format = %q, want 2026-07-22T00:00", formValue)
	}
	back, err := time.Parse(time.RFC3339, parseScheduleInput(formValue, jst))
	if err != nil {
		t.Fatalf("round-trip parse: %v", err)
	}
	if !back.Equal(stored) {
		t.Errorf("round trip moved the instant: stored %v, got back %v", stored, back)
	}
}
