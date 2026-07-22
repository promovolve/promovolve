package handler

// Render smoke test for the site-approval-gate + creative-preview templates:
// executes them against the real embedded layout with representative data so
// template/data mismatches fail in CI instead of at first click.

import (
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"html/template"
	"io"
	"strings"
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
				// Integration-health panel with failures present — proves the
				// int64 eq/gt comparisons and the reason loop execute.
				HealthKnown: true, HealthPageviews: 120, HealthOkPct: "97.5",
				HealthRendered: 100, HealthNoFill: 17, HealthFailures: 3,
				HealthTopReasons: []healthReason{{Reason: "mount_failed", Count: 2}, {Reason: "timeout", Count: 1}},
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
		{"advertiser/campaigns.html", pageData{
			Title: "Campaigns", Nav: "campaigns", User: adv,
			// Going-rate hint: exercises the market-rates define with data
			// (nil-guard path runs in the all-pages smoke).
			MarketRates: &marketRatesData{
				Days: 7, OverallMedian: "$9.00", OverallP25: "$6.00", OverallP75: "$14.00",
				ReachLadderJSON: "[4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,20,20]", ScopeLabel: "Cooking, Sports", FloorFrom: "$4.00",
				Sites: []marketRateRow{
					{SiteLabel: "site.example.com", Impressions: 1200, P25: "$6.00", Median: "$9.00", P75: "$14.00", Floor: "$4.00"},
					{SiteLabel: "tiny.example.com", Impressions: 12, Floor: "$2.00"},
				},
			},
			Campaigns: []campaignData{{ID: "camp-1", Name: "Campaign One", Status: "active", MaxCPM: "5.00"}},
			ListNav:   nav,
		}},
		{"advertiser/creatives.html", pageData{
			Title: "Creatives", Nav: "creatives", User: adv, CampaignID: "camp-1",
			Campaigns:       []campaignData{{ID: "camp-1", Name: "Campaign One"}},
			BannerScriptURL: "https://cdn.example.com/banner.js",
			ListNav:         nav,
			// MediaChart exercises the template.JS embedding in the chart
			// script (JS context) and the per-card "By media" table.
			MediaChart: &creativeMediaChart{
				Labels: template.JS(`["Rendered"]`),
				Series: template.JS(`[{"label":"site.example.com","data":[10]}]`),
			},
			Creatives: []creativeData{
				{ID: "cr-1", Name: "Rendered", ActiveStatus: "Active", AssetURL: "https://cdn.example.com/a.png", Confidence: "90%", Impressions: 10, Clicks: 1, CTR: "10.0%",
					Media: []creativeMediaRow{{SiteLabel: "site.example.com", Impressions: 10, Clicks: 1, CTAClicks: 1, CTR: "10.0%", Spend: "$0.12"}}},
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

func TestMarketRatesHintScopedNoTrades(t *testing.T) {
	SetFS(platform.Templates, platform.Static)

	// A scoped context with zero cleared impressions still has an entry
	// price (floorFrom is independent of trade history). The hint must
	// render the scope, say there are no trades yet, and show the floor —
	// not vanish.
	data := &marketRatesData{
		Days: 7, ReachLadderJSON: "null",
		ScopeLabel: "Cooking", FloorFrom: "$10.00",
	}
	var buf strings.Builder
	if err := getPage(i18n.LangEN, "advertiser/campaigns.html").ExecuteTemplate(&buf, "market-rates-hint", data); err != nil {
		t.Fatalf("hint fragment failed to render: %v", err)
	}
	out := buf.String()
	for _, want := range []string{
		"Context: Cooking",
		"No cleared impressions in this context yet",
		"Entry floors from $10.00",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("hint missing %q in:\n%s", want, out)
		}
	}
}

func TestMarketRatesHintInventoryHonesty(t *testing.T) {
	SetFS(platform.Templates, platform.Static)

	render := func(data *marketRatesData) string {
		var buf strings.Builder
		if err := getPage(i18n.LangEN, "advertiser/campaigns.html").ExecuteTemplate(&buf, "market-rates-hint", data); err != nil {
			t.Fatalf("hint fragment failed to render: %v", err)
		}
		return buf.String()
	}

	base := marketRatesData{Days: 7, ReachLadderJSON: "null", ScopeLabel: "Cooking", FloorFrom: "$10.00"}

	// Some selected topics live, some not → the partial-coverage line.
	partial := base
	partial.AvailabilityKnown, partial.LiveTopics, partial.DeclaredTopics, partial.TotalTopics = true, 1, 1, 3
	if out := render(&partial); !strings.Contains(out, "1 of 3 selected topics have live inventory") {
		t.Errorf("partial coverage line missing in:\n%s", out)
	}

	// Nothing live but publishers declare matching content → hopeful-but-
	// honest line.
	declared := base
	declared.AvailabilityKnown, declared.DeclaredTopics, declared.TotalTopics = true, 2, 2
	if out := render(&declared); !strings.Contains(out, "No live trading in these topics yet") {
		t.Errorf("declared-only line missing in:\n%s", out)
	}

	// Nothing live, nothing declared → the blunt line.
	none := base
	none.AvailabilityKnown, none.TotalTopics = true, 2
	if out := render(&none); !strings.Contains(out, "No publisher inventory matches these topics yet") {
		t.Errorf("no-inventory line missing in:\n%s", out)
	}

	// All topics live → no honesty line at all (the rates speak), and
	// unknown availability must also render silence — never guess "none".
	for name, data := range map[string]marketRatesData{
		"all live": func() marketRatesData {
			d := base
			d.AvailabilityKnown, d.LiveTopics, d.TotalTopics = true, 2, 2
			return d
		}(),
		"unknown": base,
	} {
		out := render(&data)
		for _, absent := range []string{"selected topics have live inventory", "No live trading", "No publisher inventory matches"} {
			if strings.Contains(out, absent) {
				t.Errorf("%s: unexpected %q in:\n%s", name, absent, out)
			}
		}
	}
}
