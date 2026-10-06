package handler

// Reach tab of the advertiser report (GH #238). Reach is unique browsers
// that had a viewable impression, counted without viewer identity: exact
// per site for the selected range, so a campaign's total across sites is an
// upper bound ("up to"). The derived figures follow the note.com framework:
// new share, frequency, re-reach coefficient (再到達係数) and cost per new
// reach, which factors exactly as CPM ÷ 1000 × frequency × re-reach.

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"html/template"
	"math"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"time"

	"github.com/hanishi/promovolve/platform/internal/i18n"
	"github.com/hanishi/promovolve/platform/internal/model"
)

// reachResponse mirrors core's AdvertiserReachResponse
// (contracts/reports/advertiser-report-reach.json).
type reachResponse struct {
	Sites []struct {
		CampaignID string `json:"campaignId"`
		SiteID     string `json:"siteId"`
		Host       string `json:"host"`
		Reach      int64  `json:"reach"`
		NewReach   int64  `json:"newReach"`
	} `json:"sites"`
	Daily []struct {
		Day          string `json:"day"`
		CampaignID   string `json:"campaignId"`
		Reach        int64  `json:"reach"`
		NewReach     int64  `json:"newReach"`
		FirstInRange int64  `json:"firstInRange"`
	} `json:"daily"`
	// First day reach was collected anywhere; "" = none yet. Impressions and
	// spend before it have no reach to pair with.
	CoverageFrom string `json:"coverageFrom"`
}

func (h *Handler) fetchReach(rangeQS string, claims *model.Claims) reachResponse {
	var out reachResponse
	body, err := h.coreGet("/v1/advertisers/me/report/reach?"+rangeQS, claims)
	if err != nil {
		return out
	}
	_ = json.Unmarshal(body, &out)
	return out
}

// reachFigures are the inputs; the display strings are derived from them.
type reachFigures struct {
	Impressions int64 // viewable, pinned re-views included (reach counts them too)
	Spend       float64
	Reach       int64
	NewReach    int64

	NewShare   string // new ÷ reach
	Frequency  string // impressions ÷ reach
	ReReach    string // reach ÷ new (再到達係数); "—" when no new reach
	CostPerNew string // spend ÷ new; "—" when no new reach
	SpendDisp  string
}

func (f *reachFigures) derive() {
	f.SpendDisp = fmt.Sprintf("%.2f", f.Spend)
	f.NewShare, f.Frequency, f.ReReach, f.CostPerNew = "—", "—", "—", "—"
	if f.Reach > 0 {
		f.NewShare = fmt.Sprintf("%.0f%%", 100*float64(f.NewReach)/float64(f.Reach))
		f.Frequency = fmt.Sprintf("%.2f", float64(f.Impressions)/float64(f.Reach))
	}
	if f.NewReach > 0 {
		f.ReReach = fmt.Sprintf("%.2f", float64(f.Reach)/float64(f.NewReach))
		f.CostPerNew = fmt.Sprintf("%.2f", f.Spend/float64(f.NewReach))
	}
}

type reachSiteRow struct {
	Label string
	reachFigures
}

type reachCampaign struct {
	Key, Name string
	UpTo      bool // served on more than one site: totals are an upper bound
	reachFigures
	Sites   []reachSiteRow
	Explain string // cost-per-new-reach change vs the previous range; "" when not computable
}

// buildReach joins core's per-site reach with the By Site impressions and
// spend. Campaign totals sum their sites.
func buildReach(resp reachResponse, names map[string]string, siteGroups []reportDimCampaignGroup) []reachCampaign {
	type key struct{ site, camp string }
	delivery := map[key]reportBreakdownRow{}
	for _, g := range siteGroups {
		for _, r := range g.Rows {
			delivery[key{g.Key, r.Key}] = r
		}
	}
	byCamp := map[string]*reachCampaign{}
	var order []string
	for _, s := range resp.Sites {
		c := byCamp[s.CampaignID]
		if c == nil {
			c = &reachCampaign{Key: s.CampaignID, Name: labelOr(names, s.CampaignID)}
			byCamp[s.CampaignID] = c
			order = append(order, s.CampaignID)
		}
		d := delivery[key{s.SiteID, s.CampaignID}]
		row := reachSiteRow{Label: s.Host}
		if row.Label == "" {
			row.Label = s.SiteID
		}
		row.Impressions = d.Impressions + d.DogearedImps
		row.Spend = d.Spend
		row.Reach, row.NewReach = s.Reach, s.NewReach
		row.derive()
		c.Sites = append(c.Sites, row)
		c.Impressions += row.Impressions
		c.Spend += row.Spend
		c.Reach += row.Reach
		c.NewReach += row.NewReach
	}
	out := make([]reachCampaign, 0, len(order))
	for _, k := range order {
		c := byCamp[k]
		c.UpTo = len(c.Sites) > 1
		c.derive()
		out = append(out, *c)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Reach > out[j].Reach })
	return out
}

// explainReach states what moved cost per new reach between two ranges.
// cost/new = (spend/imps) × (imps/reach) × (reach/new), so the change ratio
// is exactly the product of the three factor ratios and each factor's share
// is its log over the log of the total. Names the biggest factor.
func explainReach(lang string, cur, prev reachFigures, days int) string {
	if cur.Impressions == 0 || prev.Impressions == 0 || cur.NewReach == 0 || prev.NewReach == 0 ||
		cur.Spend == 0 || prev.Spend == 0 {
		return ""
	}
	cpm := func(f reachFigures) float64 { return f.Spend / float64(f.Impressions) }
	freq := func(f reachFigures) float64 { return float64(f.Impressions) / float64(f.Reach) }
	rr := func(f reachFigures) float64 { return float64(f.Reach) / float64(f.NewReach) }
	cost := func(f reachFigures) float64 { return f.Spend / float64(f.NewReach) }
	pct := func(r float64) string { return fmt.Sprintf("%+.0f%%", 100*(r-1)) }

	total := cost(cur) / cost(prev)
	parts := []struct {
		ratio  float64
		driver string
	}{
		{cpm(cur) / cpm(prev), i18n.T(lang, "market price (CPM)")},
		{freq(cur) / freq(prev), i18n.T(lang, "frequency")},
		{rr(cur) / rr(prev), i18n.T(lang, "audience saturation")},
	}
	main := parts[0]
	for _, p := range parts[1:] {
		if math.Abs(math.Log(p.ratio)) > math.Abs(math.Log(main.ratio)) {
			main = p
		}
	}
	return i18n.T(lang, "Cost per new reach %s vs the previous %d days: CPM %s, frequency %s, re-reach %s. Mostly %s.",
		pct(total), days, pct(parts[0].ratio), pct(parts[1].ratio), pct(parts[2].ratio), main.driver)
}

// reachChart is per campaign: daily new and returning reach (stacked bars)
// and the cumulative reach curve, zero-filled over the range.
type reachChart struct {
	Labels     []string `json:"labels"`
	New        []int64  `json:"new"`
	Returning  []int64  `json:"returning"`
	Cumulative []int64  `json:"cumulative"`
}

func buildReachCharts(resp reachResponse, from, to string) template.JS {
	start, err1 := time.Parse(reportDayLayout, from)
	end, err2 := time.Parse(reportDayLayout, to)
	if err1 != nil || err2 != nil {
		return template.JS("{}")
	}
	var days []string
	for d := start; !d.After(end); d = d.AddDate(0, 0, 1) {
		days = append(days, d.Format(reportDayLayout))
	}
	idx := map[string]int{}
	for i, d := range days {
		idx[d] = i
	}
	charts := map[string]*reachChart{}
	first := map[string][]int64{}
	for _, r := range resp.Daily {
		i, ok := idx[r.Day]
		if !ok {
			continue
		}
		c := charts[r.CampaignID]
		if c == nil {
			n := len(days)
			c = &reachChart{Labels: days, New: make([]int64, n), Returning: make([]int64, n), Cumulative: make([]int64, n)}
			charts[r.CampaignID] = c
			first[r.CampaignID] = make([]int64, n)
		}
		c.New[i] = r.NewReach
		c.Returning[i] = r.Reach - r.NewReach
		first[r.CampaignID][i] = r.FirstInRange
	}
	for id, c := range charts {
		var run int64
		for i, v := range first[id] {
			run += v
			c.Cumulative[i] = run
		}
	}
	return marshalJS(charts)
}

// previousRange is the same-length range immediately before from..to.
func previousRange(from, to string) (string, string, int, bool) {
	f, err1 := time.Parse(reportDayLayout, from)
	t, err2 := time.Parse(reportDayLayout, to)
	if err1 != nil || err2 != nil {
		return "", "", 0, false
	}
	days := int(t.Sub(f).Hours()/24) + 1
	pt := f.AddDate(0, 0, -1)
	pf := pt.AddDate(0, 0, -(days - 1))
	return pf.Format(reportDayLayout), pt.Format(reportDayLayout), days, true
}

func writeReachCSV(w http.ResponseWriter, from, to string, camps []reachCampaign) {
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="reach_%s_%s.csv"`, from, to))
	cw := csv.NewWriter(w)
	_ = cw.Write([]string{"unit=browsers; per-site figures are exact, campaign totals across sites are upper bounds"})
	_ = cw.Write([]string{"campaign_id", "campaign", "site", "impressions", "reach", "new_reach",
		"new_share", "frequency", "re_reach", "spend", "cost_per_new_reach", "upper_bound"})
	row := func(c reachCampaign, site string, f reachFigures, upTo bool) {
		_ = cw.Write([]string{c.Key, c.Name, site, strconv.FormatInt(f.Impressions, 10),
			strconv.FormatInt(f.Reach, 10), strconv.FormatInt(f.NewReach, 10),
			f.NewShare, f.Frequency, f.ReReach, f.SpendDisp, f.CostPerNew, strconv.FormatBool(upTo)})
	}
	for _, c := range camps {
		row(c, "", c.reachFigures, c.UpTo)
		for _, s := range c.Sites {
			row(c, s.Label, s.reachFigures, false)
		}
	}
	cw.Flush()
}

// reachRange limits a report range to the 91 days reach is exact for
// (core rejects longer), keeping the end date.
func reachRange(from, to string) (string, string) {
	if t, err := time.Parse(reportDayLayout, to); err == nil {
		if earliest := t.AddDate(0, 0, -90).Format(reportDayLayout); from < earliest {
			from = earliest
		}
	}
	return from, "from=" + url.QueryEscape(from) + "&to=" + url.QueryEscape(to)
}

// loadReach fetches reach for from..to and pairs it with impressions and
// spend over the SAME days. Those days start at the later of the 91-day
// limit and the day reach collection began: pairing a week of impressions
// with a day of reach would inflate frequency and cost per new reach. The
// reach figures themselves need no trimming (nothing was recorded earlier).
// start is the first day the figures cover; sinceLaunch says collection's
// start is what limited it.
func (h *Handler) loadReach(from, to string, names map[string]string, siteGroups []reportDimCampaignGroup,
	claims *model.Claims) (resp reachResponse, camps []reachCampaign, start string, sinceLaunch bool) {
	start, rQS := reachRange(from, to)
	resp = h.fetchReach(rQS, claims)
	if resp.CoverageFrom > start {
		start, sinceLaunch = resp.CoverageFrom, true
	}
	if len(resp.Sites) == 0 {
		return resp, nil, start, sinceLaunch
	}
	if start != from || siteGroups == nil {
		siteGroups = h.fetchBreakdownByCampaign("from="+url.QueryEscape(start)+"&to="+url.QueryEscape(to),
			"site", names, nil, claims)
	}
	return resp, buildReach(resp, names, siteGroups), start, sinceLaunch
}

// addReach fills the Reach tab: this range's figures per campaign and site,
// the daily charts, and each campaign's cost-per-new-reach explanation
// against the previous range of the same length.
func (h *Handler) addReach(rep *reportPageData, from, to string, names map[string]string, lang string, claims *model.Claims) {
	resp, camps, start, sinceLaunch := h.loadReach(from, to, names, rep.SiteGroups, claims)
	if start != from {
		rep.ReachFrom, rep.ReachSinceLaunch = start, sinceLaunch
	}
	if len(camps) == 0 {
		return
	}
	rep.Reach = camps
	rep.ReachCharts = buildReachCharts(resp, start, to)

	pf, pt, days, ok := previousRange(start, to)
	if !ok {
		return
	}
	pQS := "from=" + url.QueryEscape(pf) + "&to=" + url.QueryEscape(pt)
	prev := map[string]reachFigures{}
	for _, c := range buildReach(h.fetchReach(pQS, claims), names, h.fetchBreakdownByCampaign(pQS, "site", names, nil, claims)) {
		prev[c.Key] = c.reachFigures
	}
	for i := range rep.Reach {
		if p, ok := prev[rep.Reach[i].Key]; ok {
			rep.Reach[i].Explain = explainReach(lang, rep.Reach[i].reachFigures, p, days)
		}
	}
}
