package handler

import (
	"bytes"
	"strings"
	"testing"
	"time"

	platform "github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"github.com/hanishi/promovolve/platform/internal/model"
)

// Reach tab (GH #238).

func TestReachContract(t *testing.T) {
	h := coreServing(t, "advertiser-report-reach.json")
	r := h.fetchReach("from=2026-10-01&to=2026-10-07", advertiserClaims())
	if len(r.Sites) != 3 || r.Sites[0].Reach != 1200 || r.Sites[0].NewReach != 900 || r.Sites[1].Host != "" {
		t.Fatalf("sites decoded wrong: %+v", r.Sites)
	}
	if len(r.Daily) != 2 || r.Daily[1].NewReach != 210 || r.Daily[1].FirstInRange != 260 {
		t.Fatalf("daily decoded wrong: %+v", r.Daily)
	}
}

func sampleReach() reachResponse {
	var r reachResponse
	r.Sites = append(r.Sites,
		struct {
			CampaignID string `json:"campaignId"`
			SiteID     string `json:"siteId"`
			Host       string `json:"host"`
			Reach      int64  `json:"reach"`
			NewReach   int64  `json:"newReach"`
		}{"C1", "s1", "news.example.jp", 400, 100},
		struct {
			CampaignID string `json:"campaignId"`
			SiteID     string `json:"siteId"`
			Host       string `json:"host"`
			Reach      int64  `json:"reach"`
			NewReach   int64  `json:"newReach"`
		}{"C1", "s2", "", 100, 100},
	)
	return r
}

func TestBuildReachJoinsDeliveryAndDerives(t *testing.T) {
	groups := []reportDimCampaignGroup{
		{Key: "s1", Rows: []reportBreakdownRow{{Key: "C1", Impressions: 900, DogearedImps: 100, Spend: 20}}},
		{Key: "s2", Rows: []reportBreakdownRow{{Key: "C1", Impressions: 150, Spend: 5}}},
	}
	camps := buildReach(sampleReach(), map[string]string{"C1": "Autumn"}, groups)
	if len(camps) != 1 {
		t.Fatalf("want 1 campaign, got %d", len(camps))
	}
	c := camps[0]
	if c.Name != "Autumn" || !c.UpTo || c.Reach != 500 || c.NewReach != 200 || c.Impressions != 1150 {
		t.Fatalf("campaign totals wrong: %+v", c)
	}
	// Site 1: pinned re-views count as impressions (reach counts them too).
	s1 := c.Sites[0]
	if s1.Label != "news.example.jp" || s1.Impressions != 1000 || s1.Frequency != "2.50" ||
		s1.NewShare != "25%" || s1.ReReach != "4.00" || s1.CostPerNew != "0.20" {
		t.Fatalf("site 1 figures wrong: %+v", s1)
	}
	if c.Sites[1].Label != "s2" { // no host → site id
		t.Fatalf("blank host should fall back to the site id, got %q", c.Sites[1].Label)
	}
}

func TestExplainReachNamesTheDriver(t *testing.T) {
	prev := reachFigures{Impressions: 1000, Spend: 10, Reach: 500, NewReach: 400}
	// Same CPM and frequency, far fewer new browsers: saturation.
	cur := reachFigures{Impressions: 1000, Spend: 10, Reach: 500, NewReach: 200}
	got := explainReach(i18n.LangEN, cur, prev, 7)
	want := "Cost per new reach +100% vs the previous 7 days: CPM +0%, frequency +0%, re-reach +100%. Mostly audience saturation."
	if got != want {
		t.Fatalf("got  %q\nwant %q", got, want)
	}
	if explainReach(i18n.LangEN, cur, reachFigures{}, 7) != "" {
		t.Fatal("no previous data should give no sentence")
	}
}

func TestReachRangeLimitsTo91Days(t *testing.T) {
	from, qs := reachRange("2026-07-01", "2026-10-01")
	if from != "2026-07-03" || qs != "from=2026-07-03&to=2026-10-01" {
		t.Fatalf("got %s %s", from, qs)
	}
	if from, _ := reachRange("2026-09-25", "2026-10-01"); from != "2026-09-25" {
		t.Fatalf("short range changed: %s", from)
	}
}

func TestReachTabRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	groups := []reportDimCampaignGroup{{Key: "s1", Rows: []reportBreakdownRow{{Key: "C1", Impressions: 900, Spend: 20}}}}
	camps := buildReach(sampleReach(), map[string]string{"C1": "Autumn"}, groups)
	camps[0].Explain = "Cost per new reach +5% vs the previous 7 days"
	rep := &reportPageData{
		From: "2026-10-01", To: "2026-10-07", Preset: "custom",
		Presets:     reportPresets("/advertiser/report", time.UTC),
		RangeQS:     "from=2026-10-01&to=2026-10-07",
		Reach:       camps,
		ReachCharts: buildReachCharts(reachResponse{}, "2026-10-01", "2026-10-07"),
		ReachFrom:   "2026-10-02",
		HasData:     true, // the tab bar only renders with delivery data
	}
	data := pageData{Title: "Report", Nav: "report", User: &model.User{Email: "a@b.c", Role: "advertiser"}, Report: rep}
	for _, tc := range []struct{ lang, reach, upTo string }{
		{i18n.LangEN, "Reach", "up to"},
		{i18n.LangJA, "リーチ", "最大"},
	} {
		var buf bytes.Buffer
		if err := getPage(tc.lang, "advertiser/report.html").ExecuteTemplate(&buf, "layout", data); err != nil {
			t.Fatalf("%s: reach tab failed to render: %v", tc.lang, err)
		}
		out := buf.String()
		for _, want := range []string{tc.reach, tc.upTo, "news.example.jp", "report-chart-reach", "2026-10-02"} {
			if !strings.Contains(out, want) {
				t.Fatalf("%s: rendered page lacks %q", tc.lang, want)
			}
		}
	}
}

// A report rendered without reach data (every advertiser at launch, or any
// render path that never fills ReachCharts) must still leave the chart
// script valid: an empty value inlined there was a syntax error that broke
// every chart on the page.
func TestReportWithoutReachKeepsChartScriptValid(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	from, to := "2026-10-01", "2026-10-07"
	rep := &reportPageData{From: from, To: to, Preset: "custom", HasData: true,
		Presets: reportPresets("/advertiser/report", time.UTC), RangeQS: "from=" + from + "&to=" + to}
	rep.ChartLabels, rep.ChartSpend, rep.ChartImps = reportChartSeries(from, to, nil)
	for _, s := range []*reportSeriesChart{&rep.CampaignSeries, &rep.SiteSeries, &rep.CategorySeries, &rep.PublisherSeries} {
		*s = buildReportSeriesChart(from, to, nil)
	}
	var buf bytes.Buffer
	data := pageData{Title: "Report", Nav: "report", User: &model.User{Email: "a@b.c", Role: "advertiser"}, Report: rep}
	if err := getPage(i18n.LangEN, "advertiser/report.html").ExecuteTemplate(&buf, "layout", data); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "const reachCharts = {};") {
		t.Fatal("reachCharts must be inlined as a valid object when there is no reach data")
	}
}
