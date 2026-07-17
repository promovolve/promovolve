package handler

import (
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"io"
	"strings"
	"testing"

	platform "github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/model"
)

func publisherReportFixtureRows() []publisherSiteCategoryRow {
	return []publisherSiteCategoryRow{
		{SiteID: "sports-programmer-llc", Host: "sports.programmer.llc", Category: "483", Impressions: 500, Clicks: 19, CTAClicks: 4, DogearedImps: 3, Gross: 1.67, Net: 1.503},
		{SiteID: "sports-programmer-llc", Host: "sports.programmer.llc", Category: "", Impressions: 10, Clicks: 0, Gross: 0.01, Net: 0.009},
		{SiteID: "legacy-site", Host: "", Category: "682", Impressions: 40, Clicks: 1, Gross: 0.2, Net: 0.18},
	}
}

func TestPublisherReportTemplateRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	names := map[string]string{"483": "Sports", "682": "Healthy Living"}
	rep := &publisherReportPageData{
		From: "2026-06-30", To: "2026-07-06", Preset: "7d",
		Presets:      reportPresets("/publisher/report"),
		Sites:        groupPublisherReportSites(publisherReportFixtureRows(), names),
		FeePct:       "15",
		CoverageNote: "2026-07-03",
		RangeQS:      "from=2026-06-30&to=2026-07-06",
		CSVURL:       "/publisher/report?from=2026-06-30&to=2026-07-06&format=csv",
		HasData:      true,
	}
	data := pageData{Title: "Report", Nav: "report", User: &model.User{Email: "p@b.c", Role: "publisher"}, PubReport: rep}
	var sb strings.Builder
	if err := getPage(i18n.LangEN, "publisher/report.html").ExecuteTemplate(&sb, "layout", data); err != nil {
		t.Fatalf("publisher report template failed to render: %v", err)
	}
	// The CSV href must survive URL-context escaping intact: a query
	// FRAGMENT interpolated into an href gets component-escaped
	// (= → %3d, & → %26) and the server then drops the range. A full
	// pre-built path passes through the normalizer unchanged.
	if strings.Contains(sb.String(), "%3d") || strings.Contains(sb.String(), "%26") {
		t.Fatal("CSV href was component-escaped — range params would be dropped")
	}
	if !strings.Contains(sb.String(), `href="/publisher/report?from=2026-06-30&amp;to=2026-07-06&amp;format=csv"`) {
		t.Fatal("CSV href missing or malformed")
	}

	// Empty state renders too.
	data.PubReport = &publisherReportPageData{From: "2026-06-30", To: "2026-07-06", Preset: "custom", Presets: reportPresets("/publisher/report")}
	if err := getPage(i18n.LangEN, "publisher/report.html").ExecuteTemplate(io.Discard, "layout", data); err != nil {
		t.Fatalf("publisher report empty state failed to render: %v", err)
	}
}

// Site grouping preserves core order, resolves labels, and totals per site.
func TestGroupPublisherReportSites(t *testing.T) {
	names := map[string]string{"483": "Sports", "682": "Healthy Living"}
	sites := groupPublisherReportSites(publisherReportFixtureRows(), names)
	if len(sites) != 2 {
		t.Fatalf("sites = %d, want 2", len(sites))
	}
	s := sites[0]
	if s.Host != "sports.programmer.llc" || len(s.Rows) != 2 {
		t.Fatalf("first site = %+v", s)
	}
	if s.Rows[0].Label != "Sports" || s.Rows[1].Label != "Uncategorized" {
		t.Fatalf("labels = %q, %q", s.Rows[0].Label, s.Rows[1].Label)
	}
	if s.Totals.Impressions != 510 || s.Totals.SpendDisp != "1.51" {
		t.Fatalf("totals = %+v", s.Totals)
	}
	// Legacy site with no healed host falls back to the slug.
	if sites[1].Host != "legacy-site" {
		t.Fatalf("legacy host = %q", sites[1].Host)
	}

	// Day-level rows become per-site zero-filled time series.
	days := []publisherSiteCategoryDay{
		{Day: "2026-07-05", SiteID: "sports-programmer-llc", Category: "483", Impressions: 200, Net: 0.8},
		{Day: "2026-07-06", SiteID: "sports-programmer-llc", Category: "483", Impressions: 300, Net: 0.7},
		{Day: "2026-07-06", SiteID: "sports-programmer-llc", Category: "", Impressions: 10, Net: 0.009},
	}
	attachPublisherSiteSeries(sites, "2026-07-04", "2026-07-06", days, names)
	if string(sites[0].Series.Labels) != `["Jul 4","Jul 5","Jul 6"]` {
		t.Fatalf("series labels = %s", sites[0].Series.Labels)
	}
	want := `[{"name":"Sports","other":false,"spend":[0,0.8,0.7],"imps":[0,200,300]},` +
		`{"name":"Uncategorized","other":false,"spend":[0,0,0.009],"imps":[0,0,10]}]`
	if string(sites[0].Series.Series) != want {
		t.Fatalf("series = %s\nwant     %s", sites[0].Series.Series, want)
	}
	// A site with no day rows gets null series; the page JS skips it.
	if string(sites[1].Series.Labels) != "null" {
		t.Fatalf("legacy series labels = %s", sites[1].Series.Labels)
	}

	// Site overview: per-site totals revenue-DESC, day points labeled
	// with the site host (slug fallback).
	siteRows, sitePts := publisherSiteOverview(publisherReportFixtureRows(), days)
	if len(siteRows) != 2 || siteRows[0].Key != "sports-programmer-llc" {
		t.Fatalf("siteRows = %+v", siteRows)
	}
	if siteRows[0].Label != "sports.programmer.llc" || siteRows[1].Label != "legacy-site" {
		t.Fatalf("labels = %q, %q", siteRows[0].Label, siteRows[1].Label)
	}
	if siteRows[0].Impressions != 510 || siteRows[0].SpendDisp != "1.51" {
		t.Fatalf("overview totals = %+v", siteRows[0])
	}
	if len(sitePts) != 3 || sitePts[0].Label != "sports.programmer.llc" || sitePts[0].Key != "sports-programmer-llc" {
		t.Fatalf("sitePts = %+v", sitePts)
	}
}
