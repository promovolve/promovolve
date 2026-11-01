package handler

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"

	"github.com/hanishi/promovolve/platform/internal/model"
	"github.com/hanishi/promovolve/platform/internal/settings"
)

// publisherTop is the publisher Overview's "last 7 days" pair of tables:
// the site's top pages and top ads by net earnings
// (contracts/reports/publisher-site-top.json).
type publisherTop struct {
	Pages []topPageRow
	Ads   []topAdRow
}

type topPageRow struct {
	Path        string // display: the URL's path; "" = unknown page
	URL         string
	Impressions int64
	Clicks      int64
	Net         float64
}

type topAdRow struct {
	Name, Domain, AssetURL string
	Impressions, Clicks    int64
	CTR                    string
	Net                    float64
}

// fetchSiteTop reads the site's top pages and ads over the publisher's
// last 7 local days. nil when the core can't answer; the tables then
// don't render.
func (h *Handler) fetchSiteTop(siteID, tz string, marginBps int, claims *model.Claims) *publisherTop {
	path := "/v1/publishers/me/sites/" + url.PathEscape(siteID) + "/top"
	if tz != "" {
		path += "?tz=" + url.QueryEscape(tz)
	}
	body, err := h.coreGet(path, claims)
	if err != nil {
		return nil
	}
	var resp struct {
		Pages []struct {
			Page         string `json:"page"`
			Impressions  int64  `json:"impressions"`
			Clicks       int64  `json:"clicks"`
			GrossRevenue string `json:"grossRevenue"`
		} `json:"pages"`
		Creatives []struct {
			CreativeID    string `json:"creativeId"`
			Name          string `json:"name"`
			LandingDomain string `json:"landingDomain"`
			AssetURL      string `json:"assetUrl"`
			Impressions   int64  `json:"impressions"`
			Clicks        int64  `json:"clicks"`
			GrossRevenue  string `json:"grossRevenue"`
		} `json:"creatives"`
	}
	if json.Unmarshal(body, &resp) != nil {
		return nil
	}
	// The core sends gross auction revenue; the publisher earns the net.
	net := func(gross string) float64 {
		g, _ := strconv.ParseFloat(gross, 64)
		n, _ := settings.Net(g, marginBps)
		return n
	}
	top := &publisherTop{}
	for _, p := range resp.Pages {
		row := topPageRow{URL: p.Page, Impressions: p.Impressions, Clicks: p.Clicks, Net: net(p.GrossRevenue)}
		if u, err := url.Parse(p.Page); err == nil && p.Page != "" {
			row.Path = u.Path // decoded: Japanese paths read as text
			if row.Path == "" {
				row.Path = "/"
			}
		}
		top.Pages = append(top.Pages, row)
	}
	for _, c := range resp.Creatives {
		row := topAdRow{Name: c.Name, Domain: c.LandingDomain, AssetURL: c.AssetURL,
			Impressions: c.Impressions, Clicks: c.Clicks, CTR: "0.00%", Net: net(c.GrossRevenue)}
		if row.Name == "" {
			row.Name = c.CreativeID // the creative was deleted
		}
		if c.Impressions > 0 {
			row.CTR = fmt.Sprintf("%.2f%%", float64(c.Clicks)/float64(c.Impressions)*100)
		}
		top.Ads = append(top.Ads, row)
	}
	return top
}
