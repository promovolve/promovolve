package platform

// The embedded help pages must be byte-identical copies of the canonical
// user guides in docs/guides/ — the single source of truth for both the
// repo docs and the dashboards' Help pages. On drift: scripts/sync-help.sh.

import (
	"bytes"
	"os"
	"testing"
)

func TestHelpPagesMatchCanonicalGuides(t *testing.T) {
	pairs := map[string]string{
		"help/advertiser.md": "../docs/guides/advertiser-quickstart.md",
		"help/publisher.md":  "../docs/guides/publisher-integration.md",
	}
	for embedded, canonical := range pairs {
		want, err := os.ReadFile(canonical)
		if err != nil {
			t.Skipf("canonical guide %s not readable (vendored build?): %v", canonical, err)
		}
		got, err := Help.ReadFile(embedded)
		if err != nil {
			t.Fatalf("embedded %s missing: %v", embedded, err)
		}
		if !bytes.Equal(got, want) {
			t.Errorf("%s has drifted from %s — run scripts/sync-help.sh and commit", embedded, canonical)
		}
	}
}
