package handler

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/hanishi/promovolve/platform/internal/model"
)

// Consumer half of the Scala ⇄ Go reporting contract (GH #24). The producer
// half is modules/api/src/test/scala/promovolve/api/ReportContractSpec.scala;
// both read the same canonical bodies from contracts/reports, so the two
// sides cannot drift without one of them going red. See that directory's
// README for the update procedure.
//
// These tests exercise the real fetch* helpers (decode + money parsing +
// derived display), not a copy of their structs — a renamed JSON field
// silently zeroes the value here and the assertions catch it.

const contractDir = "../../../contracts/reports"

// coreServing answers every request with the named fixture and hands back a
// Handler pointed at it.
func coreServing(t *testing.T, fixture string) *Handler {
	t.Helper()
	body, err := os.ReadFile(filepath.Join(contractDir, fixture))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// The helpers hit /v1/advertisers/{id}/... after rewriteMePath; a
		// blank id would mean the claims plumbing broke.
		if r.URL.Path == "" || r.URL.Path == "/" {
			t.Errorf("unexpected core path %q", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)
	return New(Deps{CoreAPIURL: srv.URL})
}

func advertiserClaims() *model.Claims {
	return &model.Claims{UserID: "u1", AdvertiserID: "adv_7f3a", PublisherID: "pub_22c1"}
}

func TestAdvertiserReportContract(t *testing.T) {
	h := coreServing(t, "advertiser-report.json")
	rows := h.fetchReportRows("from=2026-08-01&to=2026-08-03", map[string]string{"cmp_kinosaki": "Kinosaki spring"}, advertiserClaims())

	if len(rows) != 2 {
		t.Fatalf("rows = %d, want 2", len(rows))
	}
	first := rows[0]
	if first.Day != "2026-08-01" || first.CampaignID != "cmp_kinosaki" {
		t.Errorf("row identity = %q/%q", first.Day, first.CampaignID)
	}
	if first.CampaignName != "Kinosaki spring" {
		t.Errorf("CampaignName = %q, want the looked-up name", first.CampaignName)
	}
	if first.Impressions != 120345 || first.Clicks != 981 || first.CTAClicks != 77 {
		t.Errorf("funnel counts = %d/%d/%d", first.Impressions, first.Clicks, first.CTAClicks)
	}
	// Dog-ear engagement: four separate counters. Crossing any two of them
	// (folds vs unfolds, clicks vs CTA clicks) is invisible in aggregate but
	// wrong on the Engagement tile.
	if first.DogearedImps != 4102 || first.Folds != 512 || first.Unfolds != 488 {
		t.Errorf("dogear counts = %d/%d/%d", first.DogearedImps, first.Folds, first.Unfolds)
	}
	if first.DogearedClicks != 61 || first.DogearedCTAClicks != 9 {
		t.Errorf("dogeared click counts = %d/%d", first.DogearedClicks, first.DogearedCTAClicks)
	}
	// "1234.5678" is dollars, not cents and not micros: a unit slip shows up
	// as 123456.78 or 0.1234 here.
	if first.Spend != 1234.5678 {
		t.Errorf("Spend = %v, want 1234.5678", first.Spend)
	}
	if first.SpendDisp != "1234.57" {
		t.Errorf("SpendDisp = %q, want 2-decimal display", first.SpendDisp)
	}
	// Sub-cent spend must parse, not round to zero.
	if rows[1].Spend != 0.0001 {
		t.Errorf("sub-cent Spend = %v, want 0.0001", rows[1].Spend)
	}
}

func TestAdvertiserReportEmptyContract(t *testing.T) {
	h := coreServing(t, "advertiser-report-empty.json")
	if rows := h.fetchReportRows("from=2026-08-01&to=2026-08-03", nil, advertiserClaims()); len(rows) != 0 {
		t.Fatalf("rows = %d, want 0", len(rows))
	}
}

func TestAdvertiserBreakdownContract(t *testing.T) {
	h := coreServing(t, "advertiser-report-breakdown.json")
	rows, coverageFrom := h.fetchReportBreakdown("from=2026-08-01&to=2026-08-31", "site", advertiserClaims())

	if len(rows) != 2 {
		t.Fatalf("rows = %d, want 2", len(rows))
	}
	// coverageFrom dates the "data starts" note; dropping it silently claims
	// full coverage for a range the rollups never had.
	if coverageFrom != "2026-07-15" {
		t.Errorf("coverageFrom = %q, want 2026-07-15", coverageFrom)
	}
	if rows[0].Key != "site_8821" || rows[0].Label != "onsen.example.jp" {
		t.Errorf("row 0 = %q/%q", rows[0].Key, rows[0].Label)
	}
	if rows[0].Spend != 987.6543 || rows[0].Impressions != 90210 {
		t.Errorf("row 0 spend/imps = %v/%d", rows[0].Spend, rows[0].Impressions)
	}
	if rows[1].Spend != 0.01 {
		t.Errorf("row 1 spend = %v, want 0.01", rows[1].Spend)
	}
}

func TestPublisherSiteCategoryContract(t *testing.T) {
	h := coreServing(t, "publisher-site-categories.json")
	// 3000 bps = 30% platform margin.
	rows, coverageFrom := h.fetchPublisherSiteCategories("from=2026-08-01&to=2026-08-31", 3000, advertiserClaims())

	if len(rows) != 2 {
		t.Fatalf("rows = %d, want 2", len(rows))
	}
	if coverageFrom != "2026-07-15" {
		t.Errorf("coverageFrom = %q, want 2026-07-15", coverageFrom)
	}
	first := rows[0]
	if first.SiteID != "site_8821" || first.Host != "onsen.example.jp" || first.Category != "52" {
		t.Errorf("row 0 identity = %q/%q/%q", first.SiteID, first.Host, first.Category)
	}
	// grossRevenue arrives BEFORE margin; the publisher is owed the net.
	// Netting an already-netted number would pay 490 instead of 700.
	if first.Gross != 1000 {
		t.Errorf("Gross = %v, want 1000", first.Gross)
	}
	if first.Net != 700 {
		t.Errorf("Net = %v, want 700 at 30%% margin", first.Net)
	}
	if first.Impressions != 90210 || first.DogearedImps != 3011 {
		t.Errorf("row 0 counts = %d/%d", first.Impressions, first.DogearedImps)
	}
	// Blank host and category are legitimate ('' = unhealed host /
	// uncategorized), and must stay blank rather than decode as "null".
	if rows[1].Host != "" || rows[1].Category != "" {
		t.Errorf("row 1 blanks = %q/%q", rows[1].Host, rows[1].Category)
	}
	if rows[1].Gross != 0.0001 {
		t.Errorf("row 1 Gross = %v, want 0.0001", rows[1].Gross)
	}
}

func TestPublisherSiteCategoryEmptyContract(t *testing.T) {
	h := coreServing(t, "publisher-site-categories-empty.json")
	rows, coverageFrom := h.fetchPublisherSiteCategories("from=2026-08-01&to=2026-08-31", 3000, advertiserClaims())
	if len(rows) != 0 {
		t.Fatalf("rows = %d, want 0", len(rows))
	}
	// '' = no rollups yet for this publisher, which the page renders
	// differently from a real date.
	if coverageFrom != "" {
		t.Errorf("coverageFrom = %q, want empty", coverageFrom)
	}
}
