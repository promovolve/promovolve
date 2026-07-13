package handler

import "testing"

// stripQuery/queryCount back the campaigns-list Landing display: UTM-laden
// URLs render trimmed, with the full URL kept in href/title and a "+N
// params" hint — the advertiser still verifies the real URL by click.
func TestStripQueryAndQueryCount(t *testing.T) {
	strip := funcMap["stripQuery"].(func(string) string)
	count := funcMap["queryCount"].(func(string) int)

	full := "https://sakura-pilates.jp/column/chiba/nagareyama/?utm_source=promovolve&utm_medium=display&utm_campaign=spring"
	if got := strip(full); got != "https://sakura-pilates.jp/column/chiba/nagareyama/" {
		t.Errorf("stripQuery(%q) = %q", full, got)
	}
	if got := count(full); got != 3 {
		t.Errorf("queryCount(%q) = %d, want 3", full, got)
	}

	bare := "https://example.com/page"
	if got := strip(bare); got != bare {
		t.Errorf("stripQuery(%q) = %q, want unchanged", bare, got)
	}
	if got := count(bare); got != 0 {
		t.Errorf("queryCount(%q) = %d, want 0", bare, got)
	}

	// Fragments are display noise too.
	if got := strip("https://example.com/page#section"); got != "https://example.com/page" {
		t.Errorf("fragment not stripped: %q", got)
	}

	// Unparseable input falls through untouched rather than erroring.
	if got := strip("::not a url::"); got != "::not a url::" {
		t.Errorf("unparseable input mangled: %q", got)
	}
}
