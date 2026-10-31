package handler

import (
	"net/http/httptest"
	"testing"
)

// Overview's "Edit creatives" link sends ?edit=<id>; Campaigns opens on the
// page holding that campaign so its edit panel is there to open.
func TestFocusPage(t *testing.T) {
	cs := make([]campaignData, 25)
	for i := range cs {
		cs[i].ID = string(rune('a' + i))
	}
	for _, c := range []struct{ query, want string }{
		{"edit=a", "1"},
		{"edit=j", "1"}, // index 9: last on page 1
		{"edit=k", "2"}, // index 10: first on page 2
		{"edit=y", "3"},
		{"edit=zz", ""},        // unknown id: page untouched
		{"edit=y&page=1", "1"}, // an explicit page wins
		{"sort=name", ""},
	} {
		r := httptest.NewRequest("GET", "/advertiser/campaigns?"+c.query, nil)
		focusPage(r, cs, 10)
		if got := r.URL.Query().Get("page"); got != c.want {
			t.Errorf("%s: page = %q, want %q", c.query, got, c.want)
		}
	}
}
