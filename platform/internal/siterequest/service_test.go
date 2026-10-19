package siterequest

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/hanishi/promovolve/platform/internal/model"
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

// A site is released only on an explicit "unverified" for THIS domain. Every
// other answer — verified, gone, a placeholder, a core error — keeps it.
func TestSiteUnverified(t *testing.T) {
	cases := []struct {
		name   string
		status int
		body   string
		want   bool
	}{
		{"unverified, same domain", http.StatusOK, `{"domain":"Promovolve.org","verificationStatus":"unverified"}`, true},
		{"verified", http.StatusOK, `{"domain":"promovolve.org","verificationStatus":"verified"}`, false},
		{"empty placeholder", http.StatusOK, `{"domain":"","verificationStatus":"unverified"}`, false},
		{"deleted", http.StatusBadRequest, `{"code":"site_not_found"}`, false},
		{"core error", http.StatusInternalServerError, `{"code":"get_site_failed"}`, false},
		{"unparseable", http.StatusOK, `<html>`, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
				w.Write([]byte(tc.body))
			}))
			defer srv.Close()
			s := &Service{coreAPIURL: srv.URL}
			req := model.SiteRequest{PublisherID: "pub-1", SiteID: "promovolve-org", Domain: "promovolve.org"}
			if got := s.siteUnverified(context.Background(), req); got != tc.want {
				t.Errorf("siteUnverified = %v, want %v", got, tc.want)
			}
		})
	}
}
