package handler

// Render smoke test for the site-approval-gate + creative-preview templates:
// executes them against the real embedded layout with representative data so
// template/data mismatches fail in CI instead of at first click.

import (
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"io"
	"testing"

	platform "github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/model"
)

func TestSitesAndCreativesTemplatesRender(t *testing.T) {
	SetFS(platform.Templates, platform.Static)

	admin := &model.User{Email: "admin@test", Role: model.RoleAdmin}
	adv := &model.User{Email: "adv@test", Role: model.RoleAdvertiser}
	pub := &model.User{Email: "pub@test", Role: model.RolePublisher}
	nav := &listNav{Page: 1, TotalPages: 1, Total: 2, From: 1, To: 2}

	cases := []struct {
		name string
		data pageData
	}{
		{"publisher/sites.html", pageData{
			Title: "Sites", Nav: "sites", User: pub, Error: "an error banner",
			SiteRequests: []siteRequestRow{
				{ID: "r1", Domain: "pending.example.com", SiteID: "pending-example-com", Status: "pending", RequestedAt: "2026-07-07 10:00"},
				{ID: "r2", Domain: "denied.example.com", SiteID: "denied-example-com", Status: "rejected", RejectReason: "not a real site", RequestedAt: "2026-07-07 09:00"},
			},
			SitesData: []siteData{{
				ID: "site-1", Domain: "site.example.com", VerificationStatus: "verified",
				FloorCpm: "0.50", MinFloorCpm: "0.10", BidWeight: "0.50", BidWeightLabel: "Balanced",
				Slots: []slotData{{SlotID: "hero", Width: 300, Height: 250}},
			}},
		}},
		{"admin/sites.html", pageData{
			Title: "Site Requests", Nav: "admin-sites", User: admin, Error: "an error banner",
			AdminSiteRequests: []adminSiteRequestRow{{
				ID: "r1", Domain: "pending.example.com", SiteID: "pending-example-com",
				PageURL: "https://pending.example.com/x", Publisher: "pub@test", Company: "Pub Co",
				RequestedAt: "2026-07-07 10:00",
			}},
		}},
		{"advertiser/creatives.html", pageData{
			Title: "Creatives", Nav: "creatives", User: adv, CampaignID: "camp-1",
			Campaigns:       []campaignData{{ID: "camp-1", Name: "Campaign One"}},
			BannerScriptURL: "https://cdn.example.com/banner.js",
			ListNav:         nav,
			Creatives: []creativeData{
				{ID: "cr-1", Name: "Rendered", ActiveStatus: "Active", AssetURL: "https://cdn.example.com/a.png", Confidence: "90%", Impressions: 10, Clicks: 1, CTR: "10.0%"},
				{ID: "cr-2", Name: "Rendering", ActiveStatus: "Draft"},
			},
		}},
		{"publisher/stats.html", pageData{
			Title: "Stats", Nav: "stats", User: pub,
			Sites:  []site{{ID: "s1", Domain: "site.example.com"}},
			SiteID: "s1",
			Stats: &publisherStats{
				Impressions: 91, Requests: 1, FillRate: "100.0%",
				Revenue: "0.08", GrossRevenue: "0.09", Fee: "0.01", FeePct: "15", ECPM: "0.84",
				CreativesServed: 1, PendingCreatives: 2,
			},
		}},
	}

	for _, c := range cases {
		for _, tlang := range []string{i18n.LangEN, i18n.LangJA} {
			if err := getPage(tlang, c.name).ExecuteTemplate(io.Discard, "layout", c.data); err != nil {
				t.Errorf("%s failed to render: %v", c.name, err)
			}
		}
	}
}
