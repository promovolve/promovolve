package handler

import (
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"io"
	"testing"

	platform "github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/model"
)

func sumBreakdownRows(rows []reportBreakdownRow) reportTotals {
	var t reportTotals
	for _, r := range rows {
		t.Impressions += r.Impressions
		t.Clicks += r.Clicks
		t.CTAClicks += r.CTAClicks
		t.DogearedImps += r.DogearedImps
		t.Spend += r.Spend
	}
	t.SpendDisp, t.CTR, t.ECPM = funnelDisplay(t.Spend, t.Impressions, t.Clicks)
	return t
}

// The report template is parsed lazily on first render; a template bug
// would otherwise surface as a runtime 500. Render it with representative
// data (rows, subtotals, breakdowns, coverage note, block affordance).
func TestReportTemplateRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	rows := []reportRow{
		{Day: "2026-07-06", CampaignID: "C1", CampaignName: "Baseball Gear", Impressions: 120, Clicks: 6, CTAClicks: 2, DogearedImps: 3, Spend: 0.42},
		{Day: "2026-07-06", CampaignID: "C2", CampaignName: "Health Drinks", Impressions: 80, Clicks: 1, Spend: 0.2},
		{Day: "2026-07-05", CampaignID: "C1", CampaignName: "Baseball Gear", Impressions: 300, Clicks: 12, CTAClicks: 5, Spend: 1.05},
	}
	for i := range rows {
		rows[i].SpendDisp, rows[i].CTR, rows[i].ECPM = funnelDisplay(rows[i].Spend, rows[i].Impressions, rows[i].Clicks)
	}
	bd := []reportBreakdownRow{
		{Key: "sports-programmer-llc", Label: "sports.programmer.llc", BlockDomain: "sports.programmer.llc", Impressions: 500, Clicks: 19, Spend: 1.67},
		{Key: "", Label: "Uncategorized", Impressions: 10, Clicks: 0, Spend: 0.01},
	}
	for i := range bd {
		bd[i].SpendDisp, bd[i].CTR, bd[i].ECPM = funnelDisplay(bd[i].Spend, bd[i].Impressions, bd[i].Clicks)
	}
	labels, spend, imps := reportChartSeries("2026-06-30", "2026-07-06", rows)
	campaigns, campPts := campaignBreakdown(rows)
	rep := &reportPageData{
		From: "2026-06-30", To: "2026-07-06", Preset: "7d",
		Presets:    reportPresets("/advertiser/report"),
		Totals:     sumReportTotals(rows),
		Days:       groupReportDays(rows),
		Campaigns:  campaigns,
		Publishers: bd,
		SiteGroups: []reportDimCampaignGroup{{
			Key: "sports-programmer-llc", Label: "sports.programmer.llc",
			BlockDomain: "sports.programmer.llc", Rows: bd,
			Subtotal: sumBreakdownRows(bd), ShowSubtotal: true,
		}},
		CategoryGroups: []reportDimCampaignGroup{{
			Key: "483", Label: "Sports", Rows: bd[:1],
			Subtotal: sumBreakdownRows(bd[:1]),
		}},
		CampaignSeries: buildReportSeriesChart("2026-06-30", "2026-07-06", campPts),
		SiteSeries: buildReportSeriesChart("2026-07-05", "2026-07-06", []seriesDayPoint{
			{Day: "2026-07-06", Key: "sports-programmer-llc", Label: "sports.programmer.llc", Spend: 0.42, Imps: 120},
		}),
		CategorySeries: buildReportSeriesChart("2026-07-05", "2026-07-06", []seriesDayPoint{
			{Day: "2026-07-06", Key: "483", Label: "Sports", Spend: 0.42, Imps: 120},
		}),
		PublisherSeries: buildReportSeriesChart("2026-07-05", "2026-07-06", nil),
		CoverageNote:    "2026-07-03",
		RangeQS:         "from=2026-06-30&to=2026-07-06",
		ChartLabels:     labels, ChartSpend: spend, ChartImps: imps,
		HasData: true,
	}
	data := pageData{Title: "Report", Nav: "report", User: &model.User{Email: "a@b.c", Role: "advertiser"}, Report: rep}
	for _, tlang := range []string{i18n.LangEN, i18n.LangJA} {
		if err := getPage(tlang, "advertiser/report.html").ExecuteTemplate(io.Discard, "layout", data); err != nil {
			t.Fatalf("report template failed to render: %v", err)
		}
	}

	// Empty state renders too (HasData=false skips the tables + chart).
	data.Report = &reportPageData{From: "2026-06-30", To: "2026-07-06", Preset: "custom", Presets: reportPresets("/advertiser/report")}
	for _, tlang := range []string{i18n.LangEN, i18n.LangJA} {
		if err := getPage(tlang, "advertiser/report.html").ExecuteTemplate(io.Discard, "layout", data); err != nil {
			t.Fatalf("report empty state failed to render: %v", err)
		}
	}
}

// campaignBreakdown: per-campaign range totals sorted spend-DESC, plus
// one day point per (day, campaign) row.
func TestCampaignBreakdown(t *testing.T) {
	rows := []reportRow{
		{Day: "2026-07-06", CampaignID: "C1", CampaignName: "Baseball Gear", Impressions: 120, Clicks: 6, Spend: 0.42},
		{Day: "2026-07-06", CampaignID: "C2", CampaignName: "Health Drinks", Impressions: 80, Clicks: 1, Spend: 2.0},
		{Day: "2026-07-05", CampaignID: "C1", CampaignName: "Baseball Gear", Impressions: 300, Clicks: 12, Spend: 1.05},
	}
	camps, pts := campaignBreakdown(rows)
	if len(camps) != 2 || len(pts) != 3 {
		t.Fatalf("camps=%d pts=%d", len(camps), len(pts))
	}
	// C2 ($2.00) outranks C1 ($1.47)
	if camps[0].Key != "C2" || camps[1].Key != "C1" {
		t.Fatalf("order = %s, %s", camps[0].Key, camps[1].Key)
	}
	if camps[1].Impressions != 420 || camps[1].SpendDisp != "1.47" {
		t.Fatalf("C1 totals = %+v", camps[1])
	}
	if pts[0].Label != "Baseball Gear" || pts[0].Day != "2026-07-06" {
		t.Fatalf("pt = %+v", pts[0])
	}
}

// Time-series builder: top 5 keys by range spend stay named series, the
// tail folds into one "Other" series, days zero-fill.
func TestBuildReportSeriesChartFoldsTail(t *testing.T) {
	var pts []seriesDayPoint
	for i := 0; i < 7; i++ {
		pts = append(pts, seriesDayPoint{
			Day: "2026-07-06", Key: string(rune('a' + i)), Label: string(rune('A' + i)),
			Spend: float64(7 - i), Imps: int64(10 + i),
		})
	}
	c := buildReportSeriesChart("2026-07-05", "2026-07-06", pts)
	if string(c.Labels) != `["Jul 5","Jul 6"]` {
		t.Fatalf("labels = %s", c.Labels)
	}
	want := `[{"name":"A","other":false,"spend":[0,7],"imps":[0,10]},` +
		`{"name":"B","other":false,"spend":[0,6],"imps":[0,11]},` +
		`{"name":"C","other":false,"spend":[0,5],"imps":[0,12]},` +
		`{"name":"D","other":false,"spend":[0,4],"imps":[0,13]},` +
		`{"name":"E","other":false,"spend":[0,3],"imps":[0,14]},` +
		`{"name":"Other (2)","other":true,"spend":[0,3],"imps":[0,31]}]`
	if string(c.Series) != want {
		t.Fatalf("series = %s\nwant     %s", c.Series, want)
	}
	if e := buildReportSeriesChart("2026-07-05", "2026-07-06", nil); string(e.Series) != "null" {
		t.Fatalf("empty series = %s", e.Series)
	}
}
