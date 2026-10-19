package siterequest

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

// siteLive turns a publisher_sites projection hit into a block only when the
// core API confirms the site still exists. The projection outlives deletion
// (revenue-attribution mapping), so not_found must read as stale, and every
// ambiguous outcome must stay conservative ("live").
func TestSiteLive(t *testing.T) {
	cases := []struct {
		name   string
		status int
		body   string
		want   bool
	}{
		{"live site", http.StatusOK, `{"id":"travel-example-com","domain":"travel.example.com"}`, true},
		// requireOwnedSite answers the explicit-publisher path with
		// site_not_found (HTTP 400) for a deleted site — the code observed
		// live on GKE prod 2026-08-01. Plain not_found covers getSite's own
		// branch.
		{"deleted site (requireOwnedSite)", http.StatusBadRequest, `{"code":"site_not_found","message":"Site travel-example-com not found"}`, false},
		{"deleted site", http.StatusNotFound, `{"code":"not_found","message":"Site travel-example-com not found"}`, false},
		{"not_found regardless of status", http.StatusOK, `{"code":"not_found","message":"gone"}`, false},
		{"unparseable body stays live", http.StatusOK, `<html>proxy error</html>`, true},
		{"unexpected error stays live", http.StatusInternalServerError, `{"code":"boom","message":"x"}`, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path != "/v1/publishers/pub-1/sites/travel-example-com" {
					t.Errorf("unexpected path %s", r.URL.Path)
				}
				w.WriteHeader(tc.status)
				w.Write([]byte(tc.body))
			}))
			defer srv.Close()
			s := &Service{coreAPIURL: srv.URL}
			if got := s.siteLive(context.Background(), "pub-1", "travel-example-com"); got != tc.want {
				t.Errorf("siteLive = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestSiteLiveUnreachableCoreStaysLive(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	srv.Close() // connection refused from here on
	s := &Service{coreAPIURL: srv.URL}
	if !s.siteLive(context.Background(), "pub-1", "travel-example-com") {
		t.Error("unreachable core must count as live (conservative)")
	}
}

// The deployment's own hosts and their subdomains are refused; sibling hosts
// on the same registrable domain are real publisher sites and stay open.
func TestReservedHost(t *testing.T) {
	hosts := []string{"promovolve.programmer.llc", "ads.programmer.llc"}
	for domain, want := range map[string]bool{
		"ads.programmer.llc":        true,
		"ADS.programmer.llc.":       true,
		"x.ads.programmer.llc":      true,
		"promovolve.programmer.llc": true,
		"programmer.llc":            false,
		"blog.programmer.llc":       false,
		"demo.promovolve.org":       false,
		"badads.programmer.llc":     false,
	} {
		if got := reservedHost(domain, hosts); got != want {
			t.Errorf("reservedHost(%q) = %v, want %v", domain, got, want)
		}
	}
}
