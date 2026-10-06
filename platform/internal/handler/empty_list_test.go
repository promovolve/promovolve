package handler

import "testing"

func TestIsEmptyList(t *testing.T) {
	for body, want := range map[string]bool{
		`{"data":[]}`:                     true,
		`{"data":[],"nextCursor":null}`:   true,
		`{"data":[{"id":"c1"}]}`:          false,
		`{"data":null}`:                   false,
		`{"error":"campaign not found"}`:  false,
		`<html>503 Service Unavailable</`: false,
		``:                                false,
	} {
		if got := isEmptyList([]byte(body)); got != want {
			t.Errorf("isEmptyList(%q) = %v, want %v", body, got, want)
		}
	}
}
