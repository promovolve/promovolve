package handler

// publisher_report.go — the publisher report page (/publisher/report):
// category breakdown per site over a date range of publisher-local days
// (account timezone — the same days as earnings statements), with CSV
// export.
// Data = GET /v1/publishers/me/report/site-categories (one row per
// site x category from campaign_dim_daily_stats). The core returns
// gross advertiser spend; the platform margin is deducted here at
// display — same policy as the Stats and Earnings pages — so every
// revenue figure matches what the publisher will actually receive.

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"time"

	"github.com/hanishi/promovolve/platform/internal/model"
	"github.com/hanishi/promovolve/platform/internal/settings"
)

// publisherSiteCategoryRow is one (site, category) row from the core,
// with revenue already netted down.
type publisherSiteCategoryRow struct {
	SiteID       string
	Host         string
	Category     string // taxonomy id; "" = uncategorized
	Impressions  int64
	Clicks       int64
	CTAClicks    int64
	DogearedImps int64
	Gross        float64
	Net          float64
}

// publisherReportSite groups one site's category rows for the page:
// a time-series chart pair (daily lines per category) + table per site.
type publisherReportSite struct {
	SiteID string
	Host   string // display label; falls back to the site id slug
	Rows   []reportBreakdownRow
	Totals reportTotals
	Series reportSeriesChart
}

type publisherReportPageData struct {
	From, To string
	Today    string // account-zone current day; bounds the range picker
	Preset   string
	Presets  []reportPresetLink
	Totals   reportTotals
	Sites    []publisherReportSite
	// Site-vs-site overview (rendered only when there is more than one
	// site): range totals per site + daily lines per site.
	SiteRows     []reportBreakdownRow
	SiteSeries   reportSeriesChart
	FeePct       string // platform margin as a percent string; "" = zero
	CoverageNote string
	RangeQS      string
	// CSVURL is the complete export path. Built in Go: interpolating a
	// query fragment into an href gets component-escaped by
	// html/template and the server would drop the range.
	CSVURL  string
	HasData bool
}

func (h *Handler) PublisherReport(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	_, loc := h.accountTimeContext(r.Context(), claims.PublisherID)
	from, to, preset := reportRange(r, loc)
	rangeQS := "from=" + url.QueryEscape(from) + "&to=" + url.QueryEscape(to)

	marginBps := h.settingsSvc.CurrentMarginBps(r.Context())
	rows, coverageFrom := h.fetchPublisherSiteCategories(rangeQS, marginBps, claims)
	names := h.taxonomyNames(claims)

	if r.URL.Query().Get("format") == "csv" {
		writePublisherReportCSV(w, from, to, rows, names)
		return
	}

	rep := &publisherReportPageData{
		From: from, To: to, Today: time.Now().In(loc).Format(reportDayLayout), Preset: preset,
		Presets: reportPresets("/publisher/report", loc),
		Sites:   groupPublisherReportSites(rows, names),
		RangeQS: rangeQS,
		CSVURL:  "/publisher/report?" + rangeQS + "&format=csv",
		HasData: len(rows) > 0,
	}
	days := h.fetchPublisherSiteCategoryDays(rangeQS, marginBps, claims)
	attachPublisherSiteSeries(rep.Sites, from, to, days, names)
	var sitePts []seriesDayPoint
	rep.SiteRows, sitePts = publisherSiteOverview(rows, days)
	rep.SiteSeries = buildReportSeriesChart(from, to, sitePts)
	if marginBps > 0 {
		rep.FeePct = formatBps(marginBps)
	}
	for _, s := range rep.Sites {
		rep.Totals.Impressions += s.Totals.Impressions
		rep.Totals.Clicks += s.Totals.Clicks
		rep.Totals.CTAClicks += s.Totals.CTAClicks
		rep.Totals.DogearedImps += s.Totals.DogearedImps
		rep.Totals.Spend += s.Totals.Spend
	}
	rep.Totals.SpendDisp, rep.Totals.CTR, rep.Totals.ECPM =
		funnelDisplay(rep.Totals.Spend, rep.Totals.Impressions, rep.Totals.Clicks)
	if coverageFrom != "" && coverageFrom > from {
		rep.CoverageNote = coverageFrom
	}

	h.render(w, r, "publisher/report.html", pageData{
		Title:     "Report",
		Nav:       "report",
		User:      user,
		PubReport: rep,
	})
}

func (h *Handler) fetchPublisherSiteCategories(rangeQS string, marginBps int, claims *model.Claims) (rows []publisherSiteCategoryRow, coverageFrom string) {
	body, err := h.coreGet("/v1/publishers/me/report/site-categories?"+rangeQS, claims)
	if err != nil {
		return nil, ""
	}
	var resp struct {
		Rows []struct {
			SiteId              string `json:"siteId"`
			Host                string `json:"host"`
			Category            string `json:"category"`
			Impressions         int64  `json:"impressions"`
			Clicks              int64  `json:"clicks"`
			CtaClicks           int64  `json:"ctaClicks"`
			GrossRevenue        string `json:"grossRevenue"`
			DogearedImpressions int64  `json:"dogearedImpressions"`
		} `json:"rows"`
		CoverageFrom string `json:"coverageFrom"`
	}
	if json.Unmarshal(body, &resp) != nil {
		return nil, ""
	}
	rows = make([]publisherSiteCategoryRow, 0, len(resp.Rows))
	for _, rr := range resp.Rows {
		gross, _ := strconv.ParseFloat(rr.GrossRevenue, 64)
		net, _ := settings.Net(gross, marginBps)
		rows = append(rows, publisherSiteCategoryRow{
			SiteID:       rr.SiteId,
			Host:         rr.Host,
			Category:     rr.Category,
			Impressions:  rr.Impressions,
			Clicks:       rr.Clicks,
			CTAClicks:    rr.CtaClicks,
			DogearedImps: rr.DogearedImpressions,
			Gross:        gross,
			Net:          net,
		})
	}
	return rows, resp.CoverageFrom
}

// groupPublisherReportSites folds the core's site-ordered row stream into
// per-site groups (category rows stay revenue-DESC within each site) and
// builds each site's chart series from its rows.
func groupPublisherReportSites(rows []publisherSiteCategoryRow, names map[string]string) []publisherReportSite {
	var sites []publisherReportSite
	for _, row := range rows {
		if len(sites) == 0 || sites[len(sites)-1].SiteID != row.SiteID {
			host := row.Host
			if host == "" {
				host = row.SiteID // legacy row with no healed host
			}
			sites = append(sites, publisherReportSite{SiteID: row.SiteID, Host: host})
		}
		s := &sites[len(sites)-1]
		br := reportBreakdownRow{
			Key:          row.Category,
			Label:        categoryName(row.Category, names),
			Impressions:  row.Impressions,
			Clicks:       row.Clicks,
			CTAClicks:    row.CTAClicks,
			DogearedImps: row.DogearedImps,
			Spend:        row.Net,
		}
		br.SpendDisp, br.CTR, br.ECPM = funnelDisplay(row.Net, row.Impressions, row.Clicks)
		s.Rows = append(s.Rows, br)
	}
	for i := range sites {
		s := &sites[i]
		for _, r := range s.Rows {
			s.Totals.Impressions += r.Impressions
			s.Totals.Clicks += r.Clicks
			s.Totals.CTAClicks += r.CTAClicks
			s.Totals.DogearedImps += r.DogearedImps
			s.Totals.Spend += r.Spend
		}
		s.Totals.SpendDisp, s.Totals.CTR, s.Totals.ECPM =
			funnelDisplay(s.Totals.Spend, s.Totals.Impressions, s.Totals.Clicks)
	}
	return sites
}

// fetchPublisherSiteCategoryDays pulls the day-level (site, category)
// rows behind the per-site time-series charts, revenue already netted.
func (h *Handler) fetchPublisherSiteCategoryDays(rangeQS string, marginBps int, claims *model.Claims) []publisherSiteCategoryDay {
	body, err := h.coreGet("/v1/publishers/me/report/site-categories-daily?"+rangeQS, claims)
	if err != nil {
		return nil
	}
	var resp struct {
		Rows []struct {
			Day          string `json:"day"`
			SiteId       string `json:"siteId"`
			Category     string `json:"category"`
			Impressions  int64  `json:"impressions"`
			GrossRevenue string `json:"grossRevenue"`
		} `json:"rows"`
	}
	if json.Unmarshal(body, &resp) != nil {
		return nil
	}
	days := make([]publisherSiteCategoryDay, 0, len(resp.Rows))
	for _, rr := range resp.Rows {
		gross, _ := strconv.ParseFloat(rr.GrossRevenue, 64)
		net, _ := settings.Net(gross, marginBps)
		days = append(days, publisherSiteCategoryDay{
			Day: rr.Day, SiteID: rr.SiteId, Category: rr.Category,
			Impressions: rr.Impressions, Net: net,
		})
	}
	return days
}

type publisherSiteCategoryDay struct {
	Day         string
	SiteID      string
	Category    string
	Impressions int64
	Net         float64
}

// publisherSiteOverview folds the (site, category) rows into per-site
// range totals (revenue-DESC) and per-(day, site) points for the
// site-vs-site overview charts.
func publisherSiteOverview(rows []publisherSiteCategoryRow, days []publisherSiteCategoryDay) ([]reportBreakdownRow, []seriesDayPoint) {
	hosts := map[string]string{}
	byID := map[string]*reportBreakdownRow{}
	var order []*reportBreakdownRow
	for _, r := range rows {
		label := r.Host
		if label == "" {
			label = r.SiteID
		}
		hosts[r.SiteID] = label
		b := byID[r.SiteID]
		if b == nil {
			b = &reportBreakdownRow{Key: r.SiteID, Label: label}
			byID[r.SiteID] = b
			order = append(order, b)
		}
		b.Impressions += r.Impressions
		b.Clicks += r.Clicks
		b.CTAClicks += r.CTAClicks
		b.DogearedImps += r.DogearedImps
		b.Spend += r.Net
	}
	sort.SliceStable(order, func(i, j int) bool { return order[i].Spend > order[j].Spend })
	out := make([]reportBreakdownRow, 0, len(order))
	for _, b := range order {
		b.SpendDisp, b.CTR, b.ECPM = funnelDisplay(b.Spend, b.Impressions, b.Clicks)
		out = append(out, *b)
	}
	pts := make([]seriesDayPoint, 0, len(days))
	for _, d := range days {
		label := hosts[d.SiteID]
		if label == "" {
			label = d.SiteID
		}
		pts = append(pts, seriesDayPoint{Day: d.Day, Key: d.SiteID, Label: label, Spend: d.Net, Imps: d.Impressions})
	}
	return out, pts
}

// attachPublisherSiteSeries builds each site's time-series chart from
// the day-level rows (top 5 categories + "Other", zero-filled calendar).
func attachPublisherSiteSeries(sites []publisherReportSite, from, to string, days []publisherSiteCategoryDay, names map[string]string) {
	bySite := map[string][]seriesDayPoint{}
	for _, d := range days {
		bySite[d.SiteID] = append(bySite[d.SiteID], seriesDayPoint{
			Day:   d.Day,
			Key:   d.Category,
			Label: categoryName(d.Category, names),
			Spend: d.Net,
			Imps:  d.Impressions,
		})
	}
	for i := range sites {
		sites[i].Series = buildReportSeriesChart(from, to, bySite[sites[i].SiteID])
	}
}

func categoryName(id string, names map[string]string) string {
	if id == "" {
		return "Uncategorized"
	}
	if n, ok := names[id]; ok {
		return n
	}
	return id
}

func writePublisherReportCSV(w http.ResponseWriter, from, to string, rows []publisherSiteCategoryRow, names map[string]string) {
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf("attachment; filename=%q", "promovolve-publisher-report-"+from+"-"+to+".csv"))
	cw := csv.NewWriter(w)
	cw.Write([]string{"site", "site_id", "category", "category_id", "impressions", "clicks", "ctr_pct", "cta_clicks", "dogeared_impressions", "gross_revenue", "net_revenue"})
	for _, r := range rows {
		host := r.Host
		if host == "" {
			host = r.SiteID
		}
		cw.Write([]string{
			host, r.SiteID,
			categoryName(r.Category, names), r.Category,
			strconv.FormatInt(r.Impressions, 10),
			strconv.FormatInt(r.Clicks, 10),
			csvRate(r.Clicks, r.Impressions),
			strconv.FormatInt(r.CTAClicks, 10),
			strconv.FormatInt(r.DogearedImps, 10),
			fmt.Sprintf("%.4f", r.Gross),
			fmt.Sprintf("%.4f", r.Net),
		})
	}
	cw.Flush()
}
