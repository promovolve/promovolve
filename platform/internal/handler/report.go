package handler

// report.go — the advertiser report page (/advertiser/report): date-range
// picker, totals tiles, daily spend + impressions charts, day x campaign
// table, and site/category/publisher breakdown tabs with CSV export.
// Data comes from the core report endpoints (campaign_daily_stats +
// campaign_dim_daily_stats).
// All days are UTC buckets (day_bucket is computed at projection-write
// time). Billing statements use the account's LOCAL days, so report days
// and statement days can differ near midnight — the report page labels
// its UTC bucketing.

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"html/template"
	"log/slog"
	"math"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/hanishi/promovolve/platform/internal/model"
)

const reportDayLayout = "2006-01-02"

// reportRow is one (UTC day, campaign) row from the core report.
type reportRow struct {
	Day          string
	CampaignID   string
	CampaignName string
	Impressions  int64
	Clicks       int64
	CTAClicks    int64
	DogearedImps int64
	// Dog-ear engagement — free by design (CPF dropped 2026-07-13):
	// folds/unfolds are reader bookmark actions; dogeared clicks/CTAs
	// happen on $0 pin-honored re-encounters.
	Folds             int64
	Unfolds           int64
	DogearedClicks    int64
	DogearedCTAClicks int64
	Spend             float64
	SpendDisp         string // "X.XX" — "$" added by the template
	CTR               string
	ECPM              string
}

type reportTotals struct {
	Impressions       int64
	Clicks            int64
	CTAClicks         int64
	DogearedImps      int64
	Folds             int64
	Unfolds           int64
	DogearedClicks    int64
	DogearedCTAClicks int64
	FoldRate          string // folds per fresh impression, "" below 1 imp
	Spend             float64
	SpendDisp         string
	CTR               string
	ECPM              string
	// Self-reported conversions rolled up over the whole range (Tier 0).
	Conversions int64
	ConvValue   float64
	CPA         string
	ROAS        string
}

type reportDayGroup struct {
	Day          string
	IsToday      bool // partial day — the template marks it
	Rows         []reportRow
	Subtotal     reportTotals
	ShowSubtotal bool // only when the day has >1 campaign
}

// reportBreakdownRow is one dimension value (site/category/publisher)
// aggregated over the range. Label is display-ready; Key is the raw id
// (site slug / category / publisher ULID) kept for CSV.
type reportBreakdownRow struct {
	Key          string
	Label        string
	BlockDomain  string // site rows with a known host: feeds the block form
	Impressions  int64
	Clicks       int64
	CTAClicks    int64
	DogearedImps int64
	Spend        float64
	SpendDisp    string
	CTR          string
	ECPM         string
	// Self-reported conversions (Tier 0). Populated only for the Campaigns
	// tab and Totals — per-site/creative attribution would require the
	// cross-site tracking Promovolve doesn't do. CPA/ROAS are "—" when
	// there's nothing to divide.
	Conversions int64
	ConvValue   float64
	CPA         string
	ROAS        string
}

type reportPresetLink struct {
	Key   string
	Label string
	URL   string
}

// reportDimCampaignGroup is one dimension value (site/category) with its
// per-campaign rows aggregated over the range.
type reportDimCampaignGroup struct {
	Key          string
	Label        string
	BlockDomain  string               // site groups with a known host
	Rows         []reportBreakdownRow // Label = campaign name
	Subtotal     reportTotals
	ShowSubtotal bool // only when the group has >1 campaign
}

type reportPageData struct {
	From, To   string
	Preset     string // "7d" | "30d" | "month" | "custom"
	Presets    []reportPresetLink
	Totals     reportTotals
	Days       []reportDayGroup
	Campaigns  []reportBreakdownRow
	Publishers []reportBreakdownRow
	// Site and category tables nest per-campaign rows under each
	// dimension value (like the Daily tab nests campaigns under days).
	SiteGroups     []reportDimCampaignGroup
	CategoryGroups []reportDimCampaignGroup
	// Every breakdown tab charts daily lines per dimension value (time
	// series); the tables below carry the range totals.
	CampaignSeries  reportSeriesChart
	SiteSeries      reportSeriesChart
	CategorySeries  reportSeriesChart
	PublisherSeries reportSeriesChart
	CoverageNote    string // set when breakdown data starts after From
	RangeQS         string // "from=...&to=..." for CSV links
	ChartLabels     template.JS
	ChartSpend      template.JS
	ChartImps       template.JS
	HasData         bool
}

func (h *Handler) AdvertiserReport(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	from, to, preset := reportRange(r)
	rangeQS := "from=" + url.QueryEscape(from) + "&to=" + url.QueryEscape(to)

	// CSV export — same handler, same range; dim picks the tab.
	if r.URL.Query().Get("format") == "csv" {
		switch dim := r.URL.Query().Get("dim"); dim {
		case "site", "category":
			names := h.campaignNames("/v1/advertisers/me/campaigns?limit=100", claims)
			groups := h.fetchBreakdownByCampaign(rangeQS, dim, names, h.taxonomyNames(claims), claims)
			writeGroupedBreakdownCSV(w, dim, from, to, groups)
		case "publisher":
			rows, _ := h.fetchReportBreakdown(rangeQS, dim, claims)
			writeBreakdownCSV(w, dim, from, to, rows)
		case "campaign":
			names := h.campaignNames("/v1/advertisers/me/campaigns?limit=100", claims)
			camps, _ := campaignBreakdown(h.fetchReportRows(rangeQS, names, claims))
			writeBreakdownCSV(w, dim, from, to, camps)
		default:
			names := h.campaignNames("/v1/advertisers/me/campaigns?limit=100", claims)
			writeDailyCSV(w, from, to, h.fetchReportRows(rangeQS, names, claims))
		}
		return
	}

	names := h.campaignNames("/v1/advertisers/me/campaigns?limit=100", claims)
	taxonomy := h.taxonomyNames(claims)
	rows := h.fetchReportRows(rangeQS, names, claims)
	// The site range breakdown is fetched only for coverageFrom — the
	// tables themselves come from the by-campaign split below.
	_, coverageFrom := h.fetchReportBreakdown(rangeQS, "site", claims)
	publishers, _ := h.fetchReportBreakdown(rangeQS, "publisher", claims)

	rep := &reportPageData{
		From: from, To: to, Preset: preset,
		Presets:        reportPresets("/advertiser/report"),
		Days:           groupReportDays(rows),
		Publishers:     publishers,
		SiteGroups:     h.fetchBreakdownByCampaign(rangeQS, "site", names, taxonomy, claims),
		CategoryGroups: h.fetchBreakdownByCampaign(rangeQS, "category", names, taxonomy, claims),
		RangeQS:        rangeQS,
		HasData:        len(rows) > 0,
	}
	rep.Totals = sumReportTotals(rows)
	rep.ChartLabels, rep.ChartSpend, rep.ChartImps = reportChartSeries(from, to, rows)
	var campPts []seriesDayPoint
	rep.Campaigns, campPts = campaignBreakdown(rows)
	// Self-reported conversions → CPA/ROAS (Tier 0). Campaign-level and
	// Totals only; in-policy (no cross-site tracking). Non-fatal — the page
	// renders without them if the service is absent or the query fails.
	if h.billingSvc != nil && claims != nil && claims.AdvertiserID != "" {
		if conv, err := h.billingSvc.ByCampaign(r.Context(), claims.AdvertiserID, from, to); err == nil && len(conv) > 0 {
			var totC, totV int64
			for i := range rep.Campaigns {
				c := conv[rep.Campaigns[i].Key]
				rep.Campaigns[i].Conversions = c.Count
				rep.Campaigns[i].ConvValue = float64(c.ValueMicros) / 1e6
				rep.Campaigns[i].CPA, rep.Campaigns[i].ROAS =
					cpaRoas(rep.Campaigns[i].Spend, c.Count, c.ValueMicros)
			}
			for _, c := range conv {
				totC += c.Count
				totV += c.ValueMicros
			}
			rep.Totals.Conversions = totC
			rep.Totals.ConvValue = float64(totV) / 1e6
			rep.Totals.CPA, rep.Totals.ROAS = cpaRoas(rep.Totals.Spend, totC, totV)
		}
	}
	rep.CampaignSeries = buildReportSeriesChart(from, to, campPts)
	rep.SiteSeries = buildReportSeriesChart(from, to,
		h.fetchBreakdownDayPoints(rangeQS, "site", taxonomy, claims))
	rep.CategorySeries = buildReportSeriesChart(from, to,
		h.fetchBreakdownDayPoints(rangeQS, "category", taxonomy, claims))
	rep.PublisherSeries = buildReportSeriesChart(from, to,
		h.fetchBreakdownDayPoints(rangeQS, "publisher", taxonomy, claims))
	// The dimensional rollup accrues from its ship date (+ ~30-day
	// backfill); flag ranges that reach behind its horizon.
	if coverageFrom != "" && coverageFrom > from {
		rep.CoverageNote = coverageFrom
	}

	h.render(w, "advertiser/report.html", pageData{
		Title:  "Report",
		Nav:    "report",
		User:   user,
		Report: rep,
	})
}

// cpaRoas renders CPA (spend per conversion) and ROAS (value / spend) for the
// report. Both are "—" when there's nothing to divide, so the columns read
// cleanly before any conversions are reported.
func cpaRoas(spend float64, conv, valueMicros int64) (cpa, roas string) {
	cpa, roas = "—", "—"
	if conv > 0 {
		cpa = fmt.Sprintf("$%.2f", spend/float64(conv))
	}
	if spend > 0 && valueMicros > 0 {
		roas = fmt.Sprintf("%.1fx", (float64(valueMicros)/1e6)/spend)
	}
	return
}

// AdvertiserReportConversions records self-reported conversions for one
// campaign+day (Tier 0). It's the advertiser's own aggregate number — never
// billed on, never a tracking signal — so validation is light and any bad
// input just bounces back to the report without an error page.
func (h *Handler) AdvertiserReportConversions(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil || claims.AdvertiserID == "" {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	campID := r.FormValue("campaignId")
	date := r.FormValue("date")
	back := "/advertiser/report"
	if from, to := r.FormValue("from"), r.FormValue("to"); from != "" && to != "" {
		back += "?from=" + url.QueryEscape(from) + "&to=" + url.QueryEscape(to)
	}
	conv, err := strconv.ParseInt(strings.TrimSpace(r.FormValue("conversions")), 10, 64)
	if campID == "" || date == "" || err != nil || conv < 0 {
		http.Redirect(w, r, back, http.StatusSeeOther)
		return
	}
	var valueMicros int64
	if v := strings.TrimSpace(r.FormValue("value")); v != "" {
		if f, perr := strconv.ParseFloat(v, 64); perr == nil && f >= 0 {
			valueMicros = int64(math.Round(f * 1e6))
		}
	}
	if h.billingSvc != nil {
		if err := h.billingSvc.UpsertConversions(r.Context(), claims.AdvertiserID, campID, date,
			conv, valueMicros, strings.TrimSpace(r.FormValue("note"))); err != nil {
			slog.Error("upsert conversions failed", "advertiser", claims.AdvertiserID, "campaign", campID, "error", err)
		}
	}
	http.Redirect(w, r, back, http.StatusSeeOther)
}

// reportRange resolves ?from/?to with the same semantics as the core
// endpoint (default last 7 days including today, UTC, 92-day cap) so the
// page never renders a range the API would reject.
func reportRange(r *http.Request) (from, to, preset string) {
	today := time.Now().UTC().Truncate(24 * time.Hour)
	toD := today
	if t, err := time.Parse(reportDayLayout, r.URL.Query().Get("to")); err == nil {
		toD = t
	}
	fromD := toD.AddDate(0, 0, -6)
	if f, err := time.Parse(reportDayLayout, r.URL.Query().Get("from")); err == nil {
		fromD = f
	}
	if fromD.After(toD) {
		fromD = toD
	}
	if toD.Sub(fromD) > 91*24*time.Hour {
		fromD = toD.AddDate(0, 0, -91)
	}
	from, to = fromD.Format(reportDayLayout), toD.Format(reportDayLayout)

	todayS := today.Format(reportDayLayout)
	switch {
	case to == todayS && from == today.AddDate(0, 0, -6).Format(reportDayLayout):
		preset = "7d"
	case to == todayS && from == today.AddDate(0, 0, -29).Format(reportDayLayout):
		preset = "30d"
	case to == todayS && from == time.Date(today.Year(), today.Month(), 1, 0, 0, 0, 0, time.UTC).Format(reportDayLayout):
		preset = "month"
	default:
		preset = "custom"
	}
	return
}

func reportPresets(basePath string) []reportPresetLink {
	today := time.Now().UTC()
	link := func(from time.Time) string {
		return basePath + "?from=" + from.Format(reportDayLayout) +
			"&to=" + today.Format(reportDayLayout)
	}
	return []reportPresetLink{
		{Key: "7d", Label: "7d", URL: link(today.AddDate(0, 0, -6))},
		{Key: "30d", Label: "30d", URL: link(today.AddDate(0, 0, -29))},
		{Key: "month", Label: "This month", URL: link(time.Date(today.Year(), today.Month(), 1, 0, 0, 0, 0, time.UTC))},
	}
}

func (h *Handler) fetchReportRows(rangeQS string, names map[string]string, claims *model.Claims) []reportRow {
	body, err := h.coreGet("/v1/advertisers/me/report?"+rangeQS, claims)
	if err != nil {
		return nil
	}
	var resp struct {
		Rows []struct {
			Day                 string `json:"day"`
			CampaignID          string `json:"campaignId"`
			Impressions         int64  `json:"impressions"`
			Clicks              int64  `json:"clicks"`
			CtaClicks           int64  `json:"ctaClicks"`
			Spend               string `json:"spend"`
			DogearedImpressions int64  `json:"dogearedImpressions"`
			Folds               int64  `json:"folds"`
			Unfolds             int64  `json:"unfolds"`
			DogearedClicks      int64  `json:"dogearedClicks"`
			DogearedCtaClicks   int64  `json:"dogearedCtaClicks"`
		} `json:"rows"`
	}
	if json.Unmarshal(body, &resp) != nil {
		return nil
	}
	rows := make([]reportRow, 0, len(resp.Rows))
	for _, rr := range resp.Rows {
		spend, _ := strconv.ParseFloat(rr.Spend, 64)
		row := reportRow{
			Day:               rr.Day,
			CampaignID:        rr.CampaignID,
			CampaignName:      labelOr(names, rr.CampaignID),
			Impressions:       rr.Impressions,
			Clicks:            rr.Clicks,
			CTAClicks:         rr.CtaClicks,
			DogearedImps:      rr.DogearedImpressions,
			Folds:             rr.Folds,
			Unfolds:           rr.Unfolds,
			DogearedClicks:    rr.DogearedClicks,
			DogearedCTAClicks: rr.DogearedCtaClicks,
			Spend:             spend,
		}
		row.SpendDisp, row.CTR, row.ECPM = funnelDisplay(spend, rr.Impressions, rr.Clicks)
		rows = append(rows, row)
	}
	return rows
}

func (h *Handler) fetchReportBreakdown(rangeQS, dim string, claims *model.Claims) (rows []reportBreakdownRow, coverageFrom string) {
	body, err := h.coreGet("/v1/advertisers/me/report/breakdown?"+rangeQS+"&dim="+dim, claims)
	if err != nil {
		return nil, ""
	}
	var resp struct {
		Rows []struct {
			Key                 string `json:"key"`
			Label               string `json:"label"`
			Impressions         int64  `json:"impressions"`
			Clicks              int64  `json:"clicks"`
			CtaClicks           int64  `json:"ctaClicks"`
			Spend               string `json:"spend"`
			DogearedImpressions int64  `json:"dogearedImpressions"`
		} `json:"rows"`
		CoverageFrom string `json:"coverageFrom"`
	}
	if json.Unmarshal(body, &resp) != nil {
		return nil, ""
	}
	rows = make([]reportBreakdownRow, 0, len(resp.Rows))
	for _, rr := range resp.Rows {
		spend, _ := strconv.ParseFloat(rr.Spend, 64)
		row := reportBreakdownRow{
			Key:          rr.Key,
			Label:        rr.Label,
			Impressions:  rr.Impressions,
			Clicks:       rr.Clicks,
			CTAClicks:    rr.CtaClicks,
			DogearedImps: rr.DogearedImpressions,
			Spend:        spend,
		}
		switch {
		case dim == "category" && rr.Key == "":
			row.Label = "Uncategorized"
		case dim == "category":
			row.Label = rr.Key
		case dim == "publisher" && rr.Key == "":
			row.Label = "(unattributed)"
		case row.Label == "":
			// site with no healed host, or publisher with no known hosts —
			// fall back to the raw id rather than an empty cell.
			row.Label = rr.Key
		}
		if dim == "site" && rr.Label != "" {
			row.BlockDomain = rr.Label // real host → block-domain affordance
		}
		row.SpendDisp, row.CTR, row.ECPM = funnelDisplay(spend, rr.Impressions, rr.Clicks)
		rows = append(rows, row)
	}
	return rows, resp.CoverageFrom
}

// campaignBreakdown folds the daily (day, campaign) rows into range
// totals per campaign (spend-DESC, like the core's breakdowns) plus the
// day points feeding the By Campaign time-series charts. No core call —
// campaign_daily_stats is already per campaign per day.
func campaignBreakdown(rows []reportRow) ([]reportBreakdownRow, []seriesDayPoint) {
	byID := map[string]*reportBreakdownRow{}
	var order []*reportBreakdownRow
	pts := make([]seriesDayPoint, 0, len(rows))
	for _, r := range rows {
		b := byID[r.CampaignID]
		if b == nil {
			b = &reportBreakdownRow{Key: r.CampaignID, Label: r.CampaignName}
			byID[r.CampaignID] = b
			order = append(order, b)
		}
		b.Impressions += r.Impressions
		b.Clicks += r.Clicks
		b.CTAClicks += r.CTAClicks
		b.DogearedImps += r.DogearedImps
		b.Spend += r.Spend
		pts = append(pts, seriesDayPoint{
			Day:   r.Day,
			Key:   r.CampaignID,
			Label: r.CampaignName,
			Spend: r.Spend,
			Imps:  r.Impressions,
		})
	}
	sort.SliceStable(order, func(i, j int) bool { return order[i].Spend > order[j].Spend })
	out := make([]reportBreakdownRow, 0, len(order))
	for _, b := range order {
		b.SpendDisp, b.CTR, b.ECPM = funnelDisplay(b.Spend, b.Impressions, b.Clicks)
		out = append(out, *b)
	}
	return out, pts
}

// fetchBreakdownByCampaign pulls (dimension value, campaign) rows and
// folds them into per-value groups — the core orders groups by total
// dimension spend and campaigns within a group by their own spend, so
// one sequential pass groups them.
func (h *Handler) fetchBreakdownByCampaign(rangeQS, dim string, names, taxonomy map[string]string, claims *model.Claims) []reportDimCampaignGroup {
	body, err := h.coreGet("/v1/advertisers/me/report/breakdown-by-campaign?"+rangeQS+"&dim="+dim, claims)
	if err != nil {
		return nil
	}
	var resp struct {
		Rows []struct {
			Key                 string `json:"key"`
			Label               string `json:"label"`
			CampaignId          string `json:"campaignId"`
			Impressions         int64  `json:"impressions"`
			Clicks              int64  `json:"clicks"`
			CtaClicks           int64  `json:"ctaClicks"`
			Spend               string `json:"spend"`
			DogearedImpressions int64  `json:"dogearedImpressions"`
		} `json:"rows"`
	}
	if json.Unmarshal(body, &resp) != nil {
		return nil
	}
	var groups []reportDimCampaignGroup
	for _, rr := range resp.Rows {
		if len(groups) == 0 || groups[len(groups)-1].Key != rr.Key {
			g := reportDimCampaignGroup{Key: rr.Key, Label: rr.Label}
			switch {
			case dim == "category":
				g.Label = categoryName(rr.Key, taxonomy)
			case g.Label == "":
				g.Label = rr.Key // site with no healed host
			}
			if dim == "site" && rr.Label != "" {
				g.BlockDomain = rr.Label
			}
			groups = append(groups, g)
		}
		g := &groups[len(groups)-1]
		spend, _ := strconv.ParseFloat(rr.Spend, 64)
		row := reportBreakdownRow{
			Key:          rr.CampaignId,
			Label:        labelOr(names, rr.CampaignId),
			Impressions:  rr.Impressions,
			Clicks:       rr.Clicks,
			CTAClicks:    rr.CtaClicks,
			DogearedImps: rr.DogearedImpressions,
			Spend:        spend,
		}
		row.SpendDisp, row.CTR, row.ECPM = funnelDisplay(spend, rr.Impressions, rr.Clicks)
		g.Rows = append(g.Rows, row)
	}
	for i := range groups {
		g := &groups[i]
		for _, r := range g.Rows {
			g.Subtotal.Impressions += r.Impressions
			g.Subtotal.Clicks += r.Clicks
			g.Subtotal.CTAClicks += r.CTAClicks
			g.Subtotal.DogearedImps += r.DogearedImps
			g.Subtotal.Spend += r.Spend
		}
		g.Subtotal.SpendDisp, g.Subtotal.CTR, g.Subtotal.ECPM =
			funnelDisplay(g.Subtotal.Spend, g.Subtotal.Impressions, g.Subtotal.Clicks)
		g.ShowSubtotal = len(g.Rows) > 1
	}
	return groups
}

// fetchBreakdownDayPoints pulls day-level rows for a breakdown tab's
// time-series charts. Labels resolve the same way as the range tables:
// category → taxonomy name, site/publisher → host(s), with the raw key
// as the fallback.
func (h *Handler) fetchBreakdownDayPoints(rangeQS, dim string, taxonomy map[string]string, claims *model.Claims) []seriesDayPoint {
	body, err := h.coreGet("/v1/advertisers/me/report/breakdown-daily?"+rangeQS+"&dim="+dim, claims)
	if err != nil {
		return nil
	}
	var resp struct {
		Rows []struct {
			Day         string `json:"day"`
			Key         string `json:"key"`
			Label       string `json:"label"`
			Impressions int64  `json:"impressions"`
			Spend       string `json:"spend"`
		} `json:"rows"`
	}
	if json.Unmarshal(body, &resp) != nil {
		return nil
	}
	pts := make([]seriesDayPoint, 0, len(resp.Rows))
	for _, rr := range resp.Rows {
		spend, _ := strconv.ParseFloat(rr.Spend, 64)
		label := rr.Label
		switch {
		case dim == "category":
			label = categoryName(rr.Key, taxonomy)
		case dim == "publisher" && rr.Key == "":
			label = "(unattributed)"
		case label == "":
			label = rr.Key
		}
		pts = append(pts, seriesDayPoint{
			Day:   rr.Day,
			Key:   rr.Key,
			Label: label,
			Spend: spend,
			Imps:  rr.Impressions,
		})
	}
	return pts
}

// taxonomyNames maps IAB taxonomy category ids to display names — the
// serve pipeline stores category IDs (e.g. "682"), which mean nothing to
// an advertiser. Degrades to the raw id on failure.
func (h *Handler) taxonomyNames(claims *model.Claims) map[string]string {
	names := map[string]string{}
	body, err := h.coreGet("/v1/taxonomy/categories?limit=2000", claims)
	if err != nil {
		return names
	}
	var resp struct {
		Data []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"data"`
	}
	if json.Unmarshal(body, &resp) == nil {
		for _, c := range resp.Data {
			if c.Name != "" {
				names[c.ID] = c.Name
			}
		}
	}
	return names
}

func funnelDisplay(spend float64, imps, clicks int64) (spendDisp, ctr, ecpm string) {
	spendDisp = fmt.Sprintf("%.2f", spend)
	if imps > 0 {
		ctr = fmt.Sprintf("%.1f%%", float64(clicks)/float64(imps)*100)
		ecpm = fmt.Sprintf("%.2f", spend/float64(imps)*1000)
	} else {
		ctr, ecpm = "—", "—"
	}
	return
}

// groupReportDays folds the core's day-DESC row stream into per-day groups
// with a subtotal when a day spans more than one campaign.
func groupReportDays(rows []reportRow) []reportDayGroup {
	todayS := time.Now().UTC().Format(reportDayLayout)
	var groups []reportDayGroup
	for _, row := range rows {
		if len(groups) == 0 || groups[len(groups)-1].Day != row.Day {
			groups = append(groups, reportDayGroup{Day: row.Day, IsToday: row.Day == todayS})
		}
		g := &groups[len(groups)-1]
		g.Rows = append(g.Rows, row)
	}
	for i := range groups {
		groups[i].Subtotal = sumReportTotals(groups[i].Rows)
		groups[i].ShowSubtotal = len(groups[i].Rows) > 1
	}
	return groups
}

func sumReportTotals(rows []reportRow) reportTotals {
	var t reportTotals
	for _, r := range rows {
		t.Impressions += r.Impressions
		t.Clicks += r.Clicks
		t.CTAClicks += r.CTAClicks
		t.DogearedImps += r.DogearedImps
		t.Folds += r.Folds
		t.Unfolds += r.Unfolds
		t.DogearedClicks += r.DogearedClicks
		t.DogearedCTAClicks += r.DogearedCTAClicks
		t.Spend += r.Spend
	}
	t.SpendDisp, t.CTR, t.ECPM = funnelDisplay(t.Spend, t.Impressions, t.Clicks)
	if t.Impressions > 0 && t.Folds > 0 {
		t.FoldRate = fmt.Sprintf("%.2f%%", float64(t.Folds)/float64(t.Impressions)*100)
	}
	return t
}

// reportChartSeries materializes every day in [from, to] (delivery gaps
// render as zero, keeping the x-axis a continuous calendar) as JSON for
// the page's Chart.js pair.
func reportChartSeries(from, to string, rows []reportRow) (labels, spend, imps template.JS) {
	byDay := map[string]*struct {
		Spend float64
		Imps  int64
	}{}
	for i := range rows {
		d := byDay[rows[i].Day]
		if d == nil {
			d = &struct {
				Spend float64
				Imps  int64
			}{}
			byDay[rows[i].Day] = d
		}
		d.Spend += rows[i].Spend
		d.Imps += rows[i].Impressions
	}
	fromD, err1 := time.Parse(reportDayLayout, from)
	toD, err2 := time.Parse(reportDayLayout, to)
	if err1 != nil || err2 != nil {
		return "[]", "[]", "[]"
	}
	var ls []string
	var ss []float64
	var is []int64
	for d := fromD; !d.After(toD); d = d.AddDate(0, 0, 1) {
		key := d.Format(reportDayLayout)
		ls = append(ls, d.Format("Jan 2"))
		if v, ok := byDay[key]; ok {
			ss = append(ss, v.Spend)
			is = append(is, v.Imps)
		} else {
			ss = append(ss, 0)
			is = append(is, 0)
		}
	}
	return marshalJS(ls), marshalJS(ss), marshalJS(is)
}

// reportSeriesChart is a multi-line time-series chart: shared day labels
// plus one entry per series, pre-marshaled for the template. Series JSON
// shape: [{name, other, spend:[...], imps:[...]}] — "other" marks the
// folded tail so the page paints it gray instead of a series color.
type reportSeriesChart struct {
	Labels template.JS
	Series template.JS
}

// seriesDayPoint is one (day, series key) observation feeding a
// time-series chart. Label is display-ready (category name, host, …).
type seriesDayPoint struct {
	Day   string
	Key   string
	Label string
	Spend float64
	Imps  int64
}

// buildReportSeriesChart zero-fills every day in [from, to] for the top
// 5 series by range spend and folds the rest into one "Other" series —
// past ~6 lines a chart stops being readable and the table below always
// has the full list.
func buildReportSeriesChart(from, to string, pts []seriesDayPoint) reportSeriesChart {
	const maxSeries = 5
	empty := reportSeriesChart{Labels: "null", Series: "null"}
	if len(pts) == 0 {
		return empty
	}
	fromD, err1 := time.Parse(reportDayLayout, from)
	toD, err2 := time.Parse(reportDayLayout, to)
	if err1 != nil || err2 != nil {
		return empty
	}

	// Rank series keys by range spend (order of first appearance breaks
	// ties deterministically).
	type agg struct {
		key   string
		label string
		spend float64
	}
	var order []*agg
	byKey := map[string]*agg{}
	for _, p := range pts {
		a := byKey[p.Key]
		if a == nil {
			a = &agg{key: p.Key, label: p.Label}
			byKey[p.Key] = a
			order = append(order, a)
		}
		a.spend += p.Spend
	}
	sort.SliceStable(order, func(i, j int) bool { return order[i].spend > order[j].spend })

	type jsSeries struct {
		Name  string    `json:"name"`
		Other bool      `json:"other"`
		Spend []float64 `json:"spend"`
		Imps  []int64   `json:"imps"`
	}
	var days []string
	var labels []string
	for d := fromD; !d.After(toD); d = d.AddDate(0, 0, 1) {
		days = append(days, d.Format(reportDayLayout))
		labels = append(labels, d.Format("Jan 2"))
	}
	dayIdx := make(map[string]int, len(days))
	for i, d := range days {
		days[i] = d
		dayIdx[d] = i
	}

	n := len(order)
	seriesIdx := map[string]int{} // key → series slot
	var series []jsSeries
	for i, a := range order {
		if i < maxSeries {
			seriesIdx[a.key] = len(series)
			series = append(series, jsSeries{Name: a.label, Spend: make([]float64, len(days)), Imps: make([]int64, len(days))})
			continue
		}
		if len(series) == maxSeries && n > maxSeries {
			series = append(series, jsSeries{Name: fmt.Sprintf("Other (%d)", n-maxSeries), Other: true, Spend: make([]float64, len(days)), Imps: make([]int64, len(days))})
		}
		seriesIdx[a.key] = maxSeries
	}
	for _, p := range pts {
		di, ok := dayIdx[p.Day]
		if !ok {
			continue // outside the range (shouldn't happen)
		}
		s := &series[seriesIdx[p.Key]]
		s.Spend[di] += p.Spend
		s.Imps[di] += p.Imps
	}
	return reportSeriesChart{Labels: marshalJS(labels), Series: marshalJS(series)}
}

func marshalJS(v any) template.JS {
	b, err := json.Marshal(v)
	if err != nil {
		return "[]"
	}
	return template.JS(b)
}

func writeDailyCSV(w http.ResponseWriter, from, to string, rows []reportRow) {
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf("attachment; filename=%q", "promovolve-report-"+from+"-"+to+".csv"))
	cw := csv.NewWriter(w)
	cw.Write([]string{"day", "campaign", "impressions", "clicks", "ctr_pct", "cta_clicks", "dogeared_impressions", "folds", "unfolds", "dogeared_clicks", "dogeared_cta_clicks", "spend", "ecpm"})
	for _, r := range rows {
		cw.Write([]string{
			r.Day, r.CampaignName,
			strconv.FormatInt(r.Impressions, 10),
			strconv.FormatInt(r.Clicks, 10),
			csvRate(r.Clicks, r.Impressions),
			strconv.FormatInt(r.CTAClicks, 10),
			strconv.FormatInt(r.DogearedImps, 10),
			strconv.FormatInt(r.Folds, 10),
			strconv.FormatInt(r.Unfolds, 10),
			strconv.FormatInt(r.DogearedClicks, 10),
			strconv.FormatInt(r.DogearedCTAClicks, 10),
			fmt.Sprintf("%.4f", r.Spend),
			csvECPM(r.Spend, r.Impressions),
		})
	}
	cw.Flush()
}

func writeBreakdownCSV(w http.ResponseWriter, dim, from, to string, rows []reportBreakdownRow) {
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf("attachment; filename=%q", "promovolve-report-"+dim+"-"+from+"-"+to+".csv"))
	cw := csv.NewWriter(w)
	cw.Write([]string{dim, dim + "_id", "impressions", "clicks", "ctr_pct", "cta_clicks", "dogeared_impressions", "spend", "ecpm"})
	for _, r := range rows {
		cw.Write([]string{
			r.Label, r.Key,
			strconv.FormatInt(r.Impressions, 10),
			strconv.FormatInt(r.Clicks, 10),
			csvRate(r.Clicks, r.Impressions),
			strconv.FormatInt(r.CTAClicks, 10),
			strconv.FormatInt(r.DogearedImps, 10),
			fmt.Sprintf("%.4f", r.Spend),
			csvECPM(r.Spend, r.Impressions),
		})
	}
	cw.Flush()
}

// writeGroupedBreakdownCSV exports the site/category tabs' nested
// per-campaign rows — one CSV row per (dimension value, campaign).
func writeGroupedBreakdownCSV(w http.ResponseWriter, dim, from, to string, groups []reportDimCampaignGroup) {
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf("attachment; filename=%q", "promovolve-report-"+dim+"-by-campaign-"+from+"-"+to+".csv"))
	cw := csv.NewWriter(w)
	cw.Write([]string{dim, dim + "_id", "campaign", "campaign_id", "impressions", "clicks", "ctr_pct", "cta_clicks", "dogeared_impressions", "spend", "ecpm"})
	for _, g := range groups {
		for _, r := range g.Rows {
			cw.Write([]string{
				g.Label, g.Key,
				r.Label, r.Key,
				strconv.FormatInt(r.Impressions, 10),
				strconv.FormatInt(r.Clicks, 10),
				csvRate(r.Clicks, r.Impressions),
				strconv.FormatInt(r.CTAClicks, 10),
				strconv.FormatInt(r.DogearedImps, 10),
				fmt.Sprintf("%.4f", r.Spend),
				csvECPM(r.Spend, r.Impressions),
			})
		}
	}
	cw.Flush()
}

func csvRate(num, den int64) string {
	if den == 0 {
		return ""
	}
	return fmt.Sprintf("%.2f", float64(num)/float64(den)*100)
}

func csvECPM(spend float64, imps int64) string {
	if imps == 0 {
		return ""
	}
	return fmt.Sprintf("%.2f", spend/float64(imps)*1000)
}
