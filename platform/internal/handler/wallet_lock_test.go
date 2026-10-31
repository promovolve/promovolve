package handler

import (
	"context"
	"errors"
	"testing"

	"github.com/hanishi/promovolve/platform/internal/model"
)

// Until the operator credits the wallet, an advertiser is pinned to
// Overview: only these pages (and what they post to) stay open.
func TestOpenWhileUnfunded(t *testing.T) {
	for path, want := range map[string]bool{
		"/advertiser/stats":                true,
		"/advertiser/account":              true,
		"/advertiser/budget":               true,
		"/advertiser/wallet":               true,
		"/advertiser/help":                 true,
		"/advertiser/help/getting-started": true,
		"/advertiser/campaigns":            false,
		"/advertiser/campaigns/create":     false,
		"/advertiser/creatives":            false,
		"/advertiser/creatives/editor":     false,
		"/advertiser/report":               false,
		"/advertiser/assets":               false,
		"/advertiser/statsx":               false, // a prefix is not a match
	} {
		if got := openWhileUnfunded(path); got != want {
			t.Errorf("openWhileUnfunded(%q) = %v, want %v", path, got, want)
		}
	}
}

func TestWalletLocked(t *testing.T) {
	stub := func(ok bool, err error) func(context.Context, string) (bool, error) {
		return func(context.Context, string) (bool, error) { return ok, err }
	}
	ctx := context.Background()
	for _, c := range []struct {
		name     string
		endorsed func(context.Context, string) (bool, error)
		id       string
		want     bool
	}{
		{"never funded", stub(false, nil), "adv1", true},
		{"funded", stub(true, nil), "adv1", false},
		// Fail open: a billing outage must not lock paying advertisers out.
		{"billing error", stub(false, errors.New("db down")), "adv1", false},
		{"no billing service", nil, "adv1", false},
		{"no advertiser id", stub(false, nil), "", false},
	} {
		h := &Handler{walletEndorsed: c.endorsed}
		if got := h.walletLocked(ctx, c.id); got != c.want {
			t.Errorf("%s: walletLocked = %v, want %v", c.name, got, c.want)
		}
	}
}

// Advertisers land on Overview: it is the one page open to every
// advertiser, funded or not.
func TestAdvertiserHomeIsOverview(t *testing.T) {
	if got := homeFor(model.RoleAdvertiser); got != "/advertiser/stats" {
		t.Errorf("homeFor(advertiser) = %q, want /advertiser/stats", got)
	}
}
