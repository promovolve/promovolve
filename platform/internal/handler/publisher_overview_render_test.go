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
		PubTop: &publisherTop{
			Pages: []topPageRow{
				{Path: "/stays/箱根", URL: "https://site.example.com/stays/箱根", Impressions: 4210, Clicks: 61, Net: 700},
				{Path: "/guides/autumn-leaves-in-kyoto-a-very-long-article-slug-that-should-truncate", URL: "https://site.example.com/guides/autumn-leaves-in-kyoto-a-very-long-article-slug-that-should-truncate", Impressions: 980, Clicks: 9, Net: 88.2},
				{Impressions: 3, Net: 0.01},
			},
			Ads: []topAdRow{
				{Name: "Autumn ryokan", Domain: "ryokan.example.jp", AssetURL: "https://cdn.example.com/assets/ab12.png", Impressions: 3900, Clicks: 72, CTR: "1.85%", Net: 568.75},
				{Name: "01JC8R2W6K4R", Impressions: 12, CTR: "0.00%"},
			},
		},
	}
	member := pageData{
		Title: "Overview", Nav: "overview", Sites: sites, SiteID: "s1", Stats: stats, PubTop: &publisherTop{},
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
			// The last 7 days' top pages and ads show for everyone; with no
			// rows, the tables say so instead of vanishing.
			if !strings.Contains(out, "data-top-pages") || !strings.Contains(out, "data-top-ads") {
				t.Errorf("%s %s: top pages/ads tables missing", c.name, tlang)
			}
			if len(c.data.PubTop.Pages) > 0 {
				for _, want := range []string{"/stays/箱根", `href="https://site.example.com/stays/`, "ryokan.example.jp", "1.85%"} {
					if !strings.Contains(out, want) {
						t.Errorf("%s %s: top tables missing %q", c.name, tlang, want)
					}
				}
			} else if !strings.Contains(out, i18n.T(tlang, "No impressions in the last 7 days.")) {
				t.Errorf("%s %s: no empty-state line", c.name, tlang)
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
