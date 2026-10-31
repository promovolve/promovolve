package handler

// Render test for the publisher Overview (publisher/overview.html), for an
// org admin (sees the settled balance) and a member (does not). With
// PREVIEW_OUT set, dumps both renders for the printed-page browser suite.

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	platform "github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"github.com/hanishi/promovolve/platform/internal/model"
)

func TestPublisherOverviewRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	stats := &publisherStats{
		Impressions: 91, Requests: 1, FillRate: "100.0%",
		Revenue: "0.08", GrossRevenue: "0.09", Fee: "0.01", FeePct: "15", ECPM: "0.84",
		CreativesServed: 1, PendingCreatives: 2,
	}
	sites := []site{{ID: "s1", Domain: "site.example.com"}}
	admin := pageData{
		Title: "Overview", Nav: "overview", User: &model.User{Email: "pub@test", Role: model.RolePublisher},
		Sites: sites, SiteID: "s1", Stats: stats,
		Earnings: &earningsPageData{Accrued: "1,204.50", LifetimePaid: "3,880.00"},
	}
	member := pageData{
		Title: "Overview", Nav: "overview", Sites: sites, SiteID: "s1", Stats: stats,
		User: &model.User{Email: "m@test", Role: model.RolePublisher, Org: &model.Org{ID: "o1"}, OrgRole: model.OrgRoleMember},
	}
	for _, c := range []struct {
		name string
		data pageData
	}{{"publisher-overview", admin}, {"publisher-overview-member", member}} {
		for _, tlang := range []string{i18n.LangEN, i18n.LangJA} {
			var sb strings.Builder
			if err := getPage(tlang, "publisher/overview.html").ExecuteTemplate(&sb, "layout", c.data); err != nil {
				t.Fatalf("%s %s: render failed: %v", c.name, tlang, err)
			}
			out := sb.String()
			// Billing users get the settled money beside today's figures,
			// with the way to the full statement; members see neither.
			billing := c.data.User.CanBilling()
			if got := strings.Contains(out, "data-overview-money"); got != billing {
				t.Errorf("%s %s: settled-money figures shown = %v", c.name, tlang, got)
			}
			if got := strings.Contains(out, `href="/publisher/earnings"`); got != billing {
				t.Errorf("%s %s: Earnings link shown = %v", c.name, tlang, got)
			}
			if !strings.Contains(out, `href="/publisher/overview"`) || strings.Contains(out, `href="/publisher/stats"`) {
				t.Errorf("%s %s: nav should link Overview, not Stats", c.name, tlang)
			}
			if dir := os.Getenv("PREVIEW_OUT"); dir != "" {
				p := filepath.Join(dir, c.name+"-"+tlang+".html")
				if err := os.WriteFile(p, []byte(out), 0o644); err != nil {
					t.Fatalf("write %s: %v", p, err)
				}
			}
		}
	}
}
