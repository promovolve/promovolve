package handler

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"image"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"log/slog"
	"math"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/hanishi/promovolve/platform/internal/i18n"
	"github.com/hanishi/promovolve/platform/internal/model"
	"github.com/hanishi/promovolve/platform/internal/settings"
	"github.com/hanishi/promovolve/platform/internal/siterequest"
)

// --- Publisher Sites ---

// slotData is the per-slot row rendered under each site card. Mirrors
// the SiteSlotConfig JSON returned by the core API.
type slotData struct {
	SlotID            string
	Width             int
	Height            int
	FloorOverride     string  // empty when no admin override is set
	PriorQualityScore float64 // 0 when no prior available
	PriorRegion       string  // empty when no prior available
	PriorAboveFold    bool
	HasPrior          bool
	MatchedCategory   string // top IAB content category for the page hosting this slot; "" when not yet classified
}

type siteData struct {
	ID                 string
	Domain             string
	Status             string
	SlotCount          int
	Categories         []string
	VerificationStatus string // "unverified" or "verified"
	VerificationToken  string
	VerifiedHost       string
	FloorCpm           string
	MinFloorCpm        string
	BidWeight          string // "0.30", "0.50", "0.70"
	BidWeightLabel     string // "Discovery", "Balanced", "Revenue"
	BlockedCategories  []string
	Slots              []slotData
	// True when the site opts in to filler-auction traffic (pages
	// with no contextual match). Drives the checkbox on sites.html.
	AcceptsFillerTraffic bool
	// Sweep-optimizer summary. Shown next to "Floor CPM" so publishers
	// can see what the optimizer actually chose vs the manually-set
	// value. Empty strings until the first cycle produces an argmax.
	OptimizedFloor      string // "$X.XX" or "" if no argmax yet
	OptimizedFloorDelta string // "+$0.50" / "-$0.20" / "" / "$0.00"
	OptimizedFloorTrend string // "▲" / "▼" / "=" / ""
	// Integration health from the ad tag's mount heartbeat, trailing 7
	// days. HealthKnown=false (no heartbeats or endpoint unavailable)
	// hides the panel — a site whose tag was never embedded shouldn't
	// show a scary 0%.
	HealthKnown      bool
	HealthPageviews  int64
	HealthOkPct      string // "98.2" — (rendered + noFill) / pageviews
	HealthRendered   int64
	HealthNoFill     int64
	HealthFailures   int64
	HealthTopReasons []healthReason
}

type healthReason struct {
	Reason string
	Count  int64
}

// siteRequestRow is a pending/denied site-request card on /publisher/sites —
// a platform-DB row with no core entity behind it yet.
type siteRequestRow struct {
	ID           string // request UUID (delete form target)
	Domain       string
	SiteID       string
	Status       string // "pending" | "rejected"
	RejectReason string
	RequestedAt  string
}

func (h *Handler) PublisherSites(w http.ResponseWriter, r *http.Request) {
	h.renderPublisherSites(w, r, "")
}

func (h *Handler) renderPublisherSites(w http.ResponseWriter, r *http.Request, errMsg string) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	var sites []siteData
	body, _ := h.coreGet("/v1/publishers/me/sites?limit=50", claims)
	var resp struct {
		Data []struct {
			ID                 string `json:"id"`
			Domain             string `json:"domain"`
			Status             string `json:"status"`
			VerificationStatus string `json:"verificationStatus"`
			Slots              []struct {
				SlotID            string   `json:"slotId"`
				Width             int      `json:"width"`
				Height            int      `json:"height"`
				FloorOverride     *string  `json:"floorOverride"`
				PriorQualityScore *float64 `json:"priorQualityScore"`
				PriorRegion       *string  `json:"priorRegion"`
				PriorAboveFold    *bool    `json:"priorAboveFold"`
				MatchedCategory   *string  `json:"matchedCategory"`
			} `json:"slots"`
			TaxonomyIds          []string `json:"taxonomyIds"`
			FloorCpm             string   `json:"floorCpm"`
			MinFloorCpm          string   `json:"minFloorCpm"`
			BidWeight            string   `json:"bidWeight"`
			AcceptsFillerTraffic *bool    `json:"acceptsFillerTraffic"`
		} `json:"data"`
	}
	json.Unmarshal(body, &resp)
	for _, s := range resp.Data {
		vs := s.VerificationStatus
		if vs == "" {
			vs = "unverified"
		}
		fc := s.FloorCpm
		if fc == "" {
			fc = "0.50"
		}
		if fcf, err := strconv.ParseFloat(fc, 64); err == nil {
			fc = fmt.Sprintf("%.2f", fcf)
		}
		bw := s.BidWeight
		if bw == "" {
			bw = "0.50"
		}
		bwLabel := "Balanced"
		if bwf, err := strconv.ParseFloat(bw, 64); err == nil {
			if bwf <= 0.35 {
				bwLabel = "Discovery"
			} else if bwf >= 0.65 {
				bwLabel = "Revenue"
			}
		}
		mfc := s.MinFloorCpm
		if mfc == "" {
			mfc = "0.10"
		}
		if mfcf, err := strconv.ParseFloat(mfc, 64); err == nil {
			mfc = fmt.Sprintf("%.2f", mfcf)
		}
		// Fetch ad product blocklist for this site
		var blockedCats []string
		blBody, _ := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/ad-product-blocklist", s.ID), claims)
		var blResp struct {
			Categories []string `json:"categories"`
		}
		if json.Unmarshal(blBody, &blResp) == nil {
			blockedCats = blResp.Categories
		}
		acceptsFiller := true
		if s.AcceptsFillerTraffic != nil {
			acceptsFiller = *s.AcceptsFillerTraffic
		}
		slots := make([]slotData, 0, len(s.Slots))
		for _, sl := range s.Slots {
			row := slotData{
				SlotID: sl.SlotID,
				Width:  sl.Width,
				Height: sl.Height,
			}
			if sl.FloorOverride != nil {
				if fv, err := strconv.ParseFloat(*sl.FloorOverride, 64); err == nil {
					row.FloorOverride = fmt.Sprintf("%.2f", fv)
				} else {
					row.FloorOverride = *sl.FloorOverride
				}
			}
			if sl.PriorRegion != nil {
				row.HasPrior = true
				row.PriorRegion = *sl.PriorRegion
			}
			if sl.PriorQualityScore != nil {
				row.PriorQualityScore = *sl.PriorQualityScore
			}
			if sl.PriorAboveFold != nil {
				row.PriorAboveFold = *sl.PriorAboveFold
			}
			if sl.MatchedCategory != nil {
				row.MatchedCategory = *sl.MatchedCategory
			}
			slots = append(slots, row)
		}
		sd := siteData{ID: s.ID, Domain: s.Domain, Status: s.Status, SlotCount: len(s.Slots), Categories: s.TaxonomyIds, VerificationStatus: vs, FloorCpm: fc, MinFloorCpm: mfc, BidWeight: bw, BidWeightLabel: bwLabel, BlockedCategories: blockedCats, Slots: slots, AcceptsFillerTraffic: acceptsFiller}
		sites = append(sites, sd)
	}

	// Fetch per-site sweep-evidence to populate the "Optimized: $X ▲/▼
	// vs last" widget. This is what the publisher actually cares about —
	// what the optimizer chose, not the manually-set "Floor CPM" which
	// only matters as a startup default.
	for i := range sites {
		evBody, _ := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/sweep-evidence", sites[i].ID), claims)
		var siteEv struct {
			BestFloor         string `json:"bestFloor,omitempty"`
			PreviousBestFloor string `json:"previousBestFloor,omitempty"`
		}
		json.Unmarshal(evBody, &siteEv)
		if siteEv.BestFloor == "" {
			continue
		}
		bestVal, _ := strconv.ParseFloat(siteEv.BestFloor, 64)
		sites[i].OptimizedFloor = fmt.Sprintf("$%.2f", bestVal)
		if siteEv.PreviousBestFloor != "" {
			prevVal, _ := strconv.ParseFloat(siteEv.PreviousBestFloor, 64)
			diff := bestVal - prevVal
			switch {
			case diff > 0.005:
				sites[i].OptimizedFloorDelta = fmt.Sprintf("+$%.2f", diff)
				sites[i].OptimizedFloorTrend = "▲"
			case diff < -0.005:
				sites[i].OptimizedFloorDelta = fmt.Sprintf("-$%.2f", -diff)
				sites[i].OptimizedFloorTrend = "▼"
			default:
				sites[i].OptimizedFloorDelta = "$0.00"
				sites[i].OptimizedFloorTrend = "="
			}
		}
	}

	// Integration health from the ad tag's mount heartbeat (trailing 7
	// days). Zero pageviews means the tag never phoned home — panel
	// stays hidden rather than implying the integration is broken.
	for i := range sites {
		mhBody, _ := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/mount-health?days=7", sites[i].ID), claims)
		var mh struct {
			Pageviews      int64 `json:"pageviews"`
			Rendered       int64 `json:"rendered"`
			NoFill         int64 `json:"noFill"`
			Failures       int64 `json:"failures"`
			FailureReasons []struct {
				Reason string `json:"reason"`
				Count  int64  `json:"count"`
			} `json:"failureReasons"`
		}
		if json.Unmarshal(mhBody, &mh) != nil || mh.Pageviews == 0 {
			continue
		}
		sites[i].HealthKnown = true
		sites[i].HealthPageviews = mh.Pageviews
		sites[i].HealthRendered = mh.Rendered
		sites[i].HealthNoFill = mh.NoFill
		sites[i].HealthFailures = mh.Failures
		sites[i].HealthOkPct = fmt.Sprintf("%.1f", float64(mh.Rendered+mh.NoFill)/float64(mh.Pageviews)*100)
		for _, fr := range mh.FailureReasons {
			sites[i].HealthTopReasons = append(sites[i].HealthTopReasons, healthReason{Reason: fr.Reason, Count: fr.Count})
		}
	}

	// Current advertiser-domain blocklist for this publisher (shown in the
	// block widget). Sourced from the publisher entity's domainBlocklist.
	var blockedAdvDomains []string
	pubBody, _ := h.coreGet("/v1/publishers/me", claims)
	var pubResp struct {
		DomainBlocklist []string `json:"domainBlocklist"`
	}
	if json.Unmarshal(pubBody, &pubResp) == nil {
		blockedAdvDomains = pubResp.DomainBlocklist
	}

	// Pending/denied site requests render as action-limited cards above the
	// real sites (approved requests are represented by the core site itself).
	var reqRows []siteRequestRow
	reqs, err := h.siteReqSvc.ListForPublisher(r.Context(), claims.PublisherID)
	if err != nil {
		slog.Error("list site requests failed", "error", err)
		if errMsg == "" {
			errMsg = i18n.T(h.lang(r, user), "could not load pending site requests")
		}
	}
	for _, sr := range reqs {
		reqRows = append(reqRows, siteRequestRow{
			ID:           sr.ID,
			Domain:       sr.Domain,
			SiteID:       sr.SiteID,
			Status:       sr.Status,
			RejectReason: sr.RejectReason,
			RequestedAt:  sr.CreatedAt.In(user.Location()).Format("2006-01-02 15:04"),
		})
	}

	h.render(w, r, "publisher/sites.html", pageData{
		Title:                    "Sites",
		Nav:                      "sites",
		User:                     user,
		Error:                    errMsg,
		SitesData:                sites,
		SiteRequests:             reqRows,
		BlockedAdvertiserDomains: blockedAdvDomains,
	})
}

func (h *Handler) CreateSite(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	pageURL := r.FormValue("pageUrl")

	parsed, err := url.Parse(pageURL)
	if err != nil || parsed.Host == "" {
		http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
		return
	}
	domain := parsed.Hostname()
	siteID := sanitizeID(domain)
	if parsed.Port() != "" {
		siteID = siteID + "-" + parsed.Port()
	}

	// The core SiteEntity is NOT created here — adding a site only records a
	// pending request that an admin approves on /admin/sites (mirrors the
	// account-request lifecycle). Guard against re-adding a site the
	// publisher already owns: core createSite would silently re-Initialize
	// it and wipe its slots/config.
	sitesBody, _ := h.coreGet("/v1/publishers/me/sites?limit=50", claims)
	var owned struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	json.Unmarshal(sitesBody, &owned)
	for _, s := range owned.Data {
		if s.ID == siteID {
			h.renderPublisherSites(w, r, i18n.T(h.lang(r, user), "%s is already registered", domain))
			return
		}
	}

	err = h.siteReqSvc.Request(r.Context(), claims.PublisherID, claims.UserID, siteID, domain, pageURL)
	if errors.Is(err, siterequest.ErrDuplicatePending) {
		h.renderPublisherSites(w, r, i18n.T(h.lang(r, user), "a request for %s is already awaiting approval", domain))
		return
	}
	if err != nil {
		slog.Error("create site request failed", "siteId", siteID, "error", err)
		h.renderPublisherSites(w, r, "could not submit the site request")
		return
	}
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

// DeleteSite removes a live (approved) site: core clears the SiteEntity
// state and its verified-host DData entry, so serving stops immediately and
// the id is re-creatable through the approval flow. Delivery history and
// settlements are keyed by site_id in their own tables and are untouched.
func (h *Handler) DeleteSite(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	siteID := r.PathValue("siteId")
	if _, err := h.coreDelete(fmt.Sprintf("/v1/publishers/me/sites/%s", siteID), claims, ""); err != nil {
		slog.Error("delete site failed", "siteId", siteID, "error", err)
		h.renderPublisherSites(w, r, i18n.T(h.lang(r, user), "could not remove the site"))
		return
	}
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

// DeleteSiteRequest removes a pending (cancel) or denied site-request card.
// Ownership and the pending/rejected-only rule are enforced in the delete
// predicate; approved rows are audit history and stay.
func (h *Handler) DeleteSiteRequest(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	err := h.siteReqSvc.Delete(r.Context(), r.PathValue("id"), claims.PublisherID)
	if errors.Is(err, siterequest.ErrNotFound) {
		h.renderPublisherSites(w, r, i18n.T(h.lang(r, user), "site request not found"))
		return
	}
	if err != nil {
		slog.Error("delete site request failed", "error", err)
		h.renderPublisherSites(w, r, "could not delete the site request")
		return
	}
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

func (h *Handler) VerifySite(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	siteID := r.PathValue("siteId")
	resp, err := h.corePost(fmt.Sprintf("/v1/publishers/me/sites/%s/verify", siteID), claims, "{}")
	if err != nil {
		slog.Error("verify site failed", "error", err)
	} else {
		slog.Info("verify site response", "body", string(resp))
	}
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

func (h *Handler) GetVerificationToken(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	siteID := r.PathValue("siteId")
	body, err := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/verification-token", siteID), claims)
	if err != nil {
		http.Error(w, "failed to get token", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// --- Publisher Stats ---

type publisherStats struct {
	Impressions int    // selected (served)
	Requests    int    // total requests
	FillRate    string // selected / total
	// Revenue is the publisher's NET earnings (gross auction revenue minus
	// the platform margin). Gross/Fee/FeePct itemize the deduction so the
	// tile stays transparent; FeePct is empty when the margin is zero.
	Revenue      string
	GrossRevenue string
	Fee          string
	FeePct       string
	// ECPM is net earnings per 1000 impressions today (net / impressions *
	// 1000) — same persistent, UTC-midnight window as Impressions and
	// Revenue, so the three headline tiles are always consistent. Empty
	// when there are no impressions yet.
	ECPM string
	// Distinct creatives that have served at least one impression on
	// this site. Sourced from AdServer's per-creative stats, which
	// only tracks creatives that have actually won at least once.
	CreativesServed  int
	PendingCreatives int
}

func (h *Handler) PublisherStats(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	siteID := r.URL.Query().Get("siteId")
	var sites []site
	sitesBody, _ := h.coreGet("/v1/publishers/me/sites?limit=50", claims)
	var sitesResp struct {
		Data []struct{ ID, Domain string } `json:"data"`
	}
	json.Unmarshal(sitesBody, &sitesResp)
	for _, s := range sitesResp.Data {
		sites = append(sites, site{ID: s.ID, Domain: s.Domain})
	}
	if siteID == "" && len(sites) > 0 {
		siteID = sites[0].ID
	}

	var stats *publisherStats
	if siteID != "" {
		statsBody, _ := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/stats", siteID), claims)
		var s struct {
			Total         int     `json:"total"`
			Selected      int     `json:"selected"`
			PacingSkipped int     `json:"pacingSkipped"`
			NoCandidates  int     `json:"noCandidates"`
			TotalSpend    float64 `json:"totalSpend"`
		}
		if json.Unmarshal(statsBody, &s) == nil {
			// Both Impressions and Revenue come from the /revenue-today
			// endpoint (tracking_events, since the publisher org's local
			// midnight — matching the local-day earnings statements). This
			// keeps the two tiles consistent — same source, same window,
			// same reset boundary.
			//
			// Requests counter stays from in-memory serveStats because
			// `tracking_events` doesn't log no-candidate batches. Fill
			// Rate is therefore computed as "today's-impressions /
			// session-requests" which is an apples-to-oranges ratio;
			// we relabel the tile to "Session Fill Rate" so the
			// scope-mismatch is honest.
			impressions := s.Selected
			gross := s.TotalSpend
			revURL := fmt.Sprintf("/v1/publishers/me/sites/%s/revenue-today", siteID)
			if tz, err := h.orgRepo.TimezoneByEntity(r.Context(), claims.PublisherID); err == nil && tz != "" {
				revURL += "?tz=" + url.QueryEscape(tz)
			}
			revBody, _ := h.coreGet(revURL, claims)
			var revResp struct {
				Revenue     string `json:"revenue"`
				Impressions int64  `json:"impressions"`
			}
			if json.Unmarshal(revBody, &revResp) == nil && revResp.Revenue != "" {
				if rv, err := strconv.ParseFloat(revResp.Revenue, 64); err == nil {
					gross = rv
				}
				if revResp.Impressions > 0 {
					impressions = int(revResp.Impressions)
				}
			}

			fillRate := "0.00%"
			if s.Total > 0 {
				fillRate = fmt.Sprintf("%.1f%%", float64(s.Selected)/float64(s.Total)*100)
			}

			// The core records gross auction revenue; the platform margin is
			// deducted here at display (and at future payouts) so the tile
			// matches what the publisher will actually receive.
			marginBps := h.settingsSvc.CurrentMarginBps(r.Context())
			net, fee := settings.Net(gross, marginBps)

			stats = &publisherStats{
				Impressions:  impressions,
				Requests:     s.Total,
				FillRate:     fillRate,
				Revenue:      fmt.Sprintf("%.2f", net),
				GrossRevenue: fmt.Sprintf("%.2f", gross),
				Fee:          fmt.Sprintf("%.2f", fee),
			}
			// eCPM shares the persistent day-scoped window with Impressions
			// and Revenue (net), so all three headline tiles reconcile.
			if impressions > 0 {
				stats.ECPM = fmt.Sprintf("%.2f", net/float64(impressions)*1000)
			}
			if marginBps > 0 {
				stats.FeePct = formatBps(marginBps)
			}

			// Fetch active creatives (from adserver creative stats)
			adStatsBody, _ := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/adserver-stats", siteID), claims)
			var adStats struct {
				Creatives []struct {
					CreativeID  string `json:"creativeId"`
					Impressions int    `json:"impressions"`
					Clicks      int    `json:"clicks"`
					Folds       int    `json:"folds"`
				} `json:"creatives"`
			}
			if json.Unmarshal(adStatsBody, &adStats) == nil {
				// "Served" = creatives that have ACTUALLY been served. There's
				// no raw delivered counter — "impressions" here is the 50%-
				// viewable beacon, which a served creative can miss. A click or
				// fold can't happen without a serve, so count any creative with
				// impressions OR clicks OR folds > 0 (excludes eligible-but-
				// never-served creatives that sit at all-zero).
				served := 0
				for _, c := range adStats.Creatives {
					if c.Impressions > 0 || c.Clicks > 0 || c.Folds > 0 {
						served++
					}
				}
				stats.CreativesServed = served
			}

			// Fetch pending creatives count
			pendingBody, _ := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/approval/pending?limit=100", siteID), claims)
			var pending struct {
				Data []any `json:"data"`
			}
			if json.Unmarshal(pendingBody, &pending) == nil {
				stats.PendingCreatives = len(pending.Data)
			}
		}
	}

	h.render(w, r, "publisher/stats.html", pageData{
		Title:  "Stats",
		Nav:    "stats",
		User:   user,
		SiteID: siteID,
		Sites:  sites,
		Stats:  stats,
	})
}

// --- Advertiser Campaigns ---

// chip is an id+label pair for the edit form's pre-filled type-ahead
// chips — the label is shown, the id is submitted. Inv is the inventory
// availability mark ("live" | "declared" | "none"); empty = unknown
// (availability lookup failed or wasn't attempted) and MUST render as
// no mark at all — an unmarked chip is honest, a wrong "none" is not.
type chip struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Inv  string `json:"inv,omitempty"`
}

// catAvail is one category's inventory availability as reported by core:
// whether the category (or any taxonomy descendant — the auction fans
// pages out to ancestors, so descendants are reachable inventory) has
// cleared impressions, or is at least declared by publisher sites.
type catAvail struct {
	Status      string `json:"status"` // "live" | "declared" | "none"
	Impressions int64  `json:"impressions"`
	Publishers  int    `json:"publishers"`
}

// fetchCategoryAvailability resolves inventory availability for the
// given category ids. Returns nil on any failure so callers fall back
// to unmarked chips rather than lying.
func (h *Handler) fetchCategoryAvailability(claims *model.Claims, ids []string) map[string]catAvail {
	var clean []string
	seen := map[string]bool{}
	for _, id := range ids {
		id = strings.TrimSpace(id)
		if id == "" || seen[id] {
			continue
		}
		seen[id] = true
		clean = append(clean, id)
	}
	if len(clean) == 0 {
		return nil
	}
	body, err := h.coreGet("/v1/advertisers/me/category-availability?categories="+url.QueryEscape(strings.Join(clean, ",")), claims)
	if err != nil {
		return nil
	}
	var resp struct {
		Categories []struct {
			CategoryID  string `json:"categoryId"`
			Status      string `json:"status"`
			Impressions int64  `json:"impressions"`
			Publishers  int    `json:"publishers"`
		} `json:"categories"`
	}
	if json.Unmarshal(body, &resp) != nil || len(resp.Categories) == 0 {
		return nil
	}
	out := make(map[string]catAvail, len(resp.Categories))
	for _, c := range resp.Categories {
		out[c.CategoryID] = catAvail{Status: c.Status, Impressions: c.Impressions, Publishers: c.Publishers}
	}
	return out
}

// CategoryAvailability proxies core's inventory-availability lookup for
// the topic pickers (create form + edit panels), which mark search
// results and freshly added chips client-side.
func (h *Handler) CategoryAvailability(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	avail := h.fetchCategoryAvailability(claims, strings.Split(r.URL.Query().Get("categories"), ","))
	w.Header().Set("Content-Type", "application/json")
	if avail == nil {
		// Unknown ≠ none: an empty data object tells the client to
		// leave chips unmarked.
		w.Write([]byte(`{"data":{}}`))
		return
	}
	json.NewEncoder(w).Encode(map[string]any{"data": avail})
}

type campaignData struct {
	ID                string
	Name              string
	Status            string
	AdProductCategory string
	DailyBudget       string
	MaxCPM            string
	LandingURL        string
	SpendToday        string
	BudgetPct         float64
	Impressions       int
	Clicks            int
	CTR               string
	ECPM              string // effective CPM: spend / impressions * 1000
	LifetimeSpend     string // projection totalSpend, formatted "$X.XX"
	BidsToday         int
	WinRate           string // e.g. "45.2%"
	WinRateClass      string // CSS class: text-red-600, text-amber-600, text-green-600
	// Opted in to bid on pages with no contextual match (filler
	// auction). Drives the checkbox on campaigns.html.
	BidOnUnmatchedContext bool
	// True when the campaign has no targetCategories AND won't accept
	// filler. Almost always indicates Gemini auto-categorisation
	// silently returned no usable categories. Drives a warning chip
	// on campaigns.html telling the advertiser to fix it.
	Untargeted bool
	// IAB 3.0 content category ids the campaign targets. Surfaced so
	// the advertiser can see what was auto-derived from the LP.
	TargetCategories []string
	// Human name for the ad-product category (resolved server-side), so
	// the row + edit form show a label, not the raw IAB number.
	AdProductCategoryName string
	// id+name pairs for the edit form's pre-filled topic chips (so chips
	// show the name while the form submits the id). The JSON form is what
	// the template embeds (via a data- attribute → JSON.parse) so names
	// with apostrophes/quotes survive HTML-attribute escaping intact.
	TargetCategoryChips     []chip
	TargetCategoryChipsJSON string
	// Gemini-derived fallback topics (id+name pairs). The edit form shows
	// these when explicit targeting is empty, so "emptying" the field
	// restores the auto-derived set instead of going blank. JSON form is
	// embedded via a data- attribute for the same escaping reasons as above.
	SuggestedCategoryChips     []chip
	SuggestedCategoryChipsJSON string
	// Publisher siteIds this campaign is restricted to (media targeting).
	// Empty = no restriction. Pre-fills the edit form's media picker.
	SiteAllowlist []string
	// Schedule. *Local fields are the value to put in a datetime-local
	// input. *Display are human-readable strings for the row header.
	// EndAtLocal = "" means open-ended. EndAtPassed = end date is in
	// the past (used to show a banner regardless of status). StartAt
	// is always populated (defaults to creation time on the entity
	// side); StartAtFuture = startAt is after now (campaign scheduled
	// but not yet live).
	StartAtLocal   string
	StartAtDisplay string
	StartAtFuture  bool
	EndAtLocal     string
	EndAtDisplay   string
	EndAtPassed    bool
}

type advertiserBudget struct {
	DailyBudget string
	Remaining   string
	SpendToday  string
	IsZero      bool
}

type servedSite struct {
	SiteID      string
	Domain      string
	Impressions int64
}

func (h *Handler) AdvertiserCampaigns(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	// Load advertiser budget
	var advBudget *advertiserBudget
	var blockedDomains []string
	advBody, _ := h.coreGet("/v1/advertisers/me", claims)
	var advResp struct {
		SiteDomainBlocklist []string `json:"siteDomainBlocklist"`
		Budget              struct {
			DailyBudget string `json:"dailyBudget"`
			Remaining   string `json:"remaining"`
			SpendToday  string `json:"spendToday"`
		} `json:"budget"`
	}
	var servedSites []servedSite
	servedBody, _ := h.coreGet("/v1/advertisers/me/served-sites?limit=50", claims)
	var servedResp struct {
		Data []struct {
			SiteID      string `json:"siteId"`
			Domain      string `json:"domain"`
			Impressions int64  `json:"impressions"`
		} `json:"data"`
	}
	if json.Unmarshal(servedBody, &servedResp) == nil {
		// Deduplicate by domain: if an advertiser served on two siteIds sharing a
		// domain, the dropdown only needs the domain once. Keep the highest impression
		// count as a sort-stable proxy.
		seen := make(map[string]bool, len(servedResp.Data))
		for _, s := range servedResp.Data {
			if s.Domain == "" || seen[s.Domain] {
				continue
			}
			seen[s.Domain] = true
			servedSites = append(servedSites, servedSite{
				SiteID:      s.SiteID,
				Domain:      s.Domain,
				Impressions: s.Impressions,
			})
		}
	}

	if json.Unmarshal(advBody, &advResp) == nil {
		blockedDomains = advResp.SiteDomainBlocklist
		parsed, _ := strconv.ParseFloat(advResp.Budget.DailyBudget, 64)
		// Budget summary reads the entity's reserved-spend counter — the SAME
		// values the serve-time gate benches on (AdvertiserEntity.spendToday /
		// remaining). So "spent today + remaining == dailyBudget" always holds
		// and the line can never contradict the "Benched — out of budget" badge.
		// Delivered spend (tracking_events) is the billing/performance figure and
		// lives on the Stats/Report pages, not this enforcement view.
		advBudget = &advertiserBudget{
			DailyBudget: money(advResp.Budget.DailyBudget),
			Remaining:   money(advResp.Budget.Remaining),
			SpendToday:  money(advResp.Budget.SpendToday),
			IsZero:      parsed <= 0,
		}
		if advBudget.IsZero {
			http.Redirect(w, r, "/advertiser/account", http.StatusSeeOther)
			return
		}
	}

	// Load campaigns (enriched with RL stats + budget inline)
	var campaigns []campaignData
	campBody, _ := h.coreGet("/v1/advertisers/me/campaigns?limit=50", claims)
	var campResp struct {
		Data []struct {
			ID                string                 `json:"id"`
			Name              string                 `json:"name"`
			Status            string                 `json:"status"`
			AdProductCategory string                 `json:"adProductCategory"`
			Budget            struct{ Daily string } `json:"budget"`
			Bidding           struct {
				MaxCPM string `json:"maxCpm"`
			} `json:"bidding"`
			LandingURL             string   `json:"landingUrl"`
			Spent                  *string  `json:"spent"`
			Remaining              *string  `json:"remaining"`
			BidOnUnmatchedContext  bool     `json:"bidOnUnmatchedContext"`
			Untargeted             bool     `json:"untargeted"`
			TargetCategories       []string `json:"targetCategories"`
			TargetCategoryNames    []string `json:"targetCategoryNames"`
			SuggestedCategories    []string `json:"suggestedCategories"`
			SuggestedCategoryNames []string `json:"suggestedCategoryNames"`
			AdProductCategoryName  string   `json:"adProductCategoryName"`
			SiteAllowlist          []string `json:"siteAllowlist"`
			Schedule               struct {
				StartAt string  `json:"startAt"`
				EndAt   *string `json:"endAt"`
			} `json:"schedule"`
		} `json:"data"`
	}
	json.Unmarshal(campBody, &campResp)

	// Load projection stats for all campaigns (single call)
	projStats := make(map[string]struct {
		Impressions int
		Clicks      int
		TotalSpend  float64
		CTR         float64
	})
	if claims != nil {
		projBody, _ := h.coreGet(fmt.Sprintf("/v1/dashboard/advertisers/%s/campaigns", claims.AdvertiserID), claims)
		var projResp []struct {
			CampaignID  string  `json:"campaignId"`
			Impressions int     `json:"impressions"`
			Clicks      int     `json:"clicks"`
			TotalSpend  float64 `json:"totalSpend"`
			CTR         float64 `json:"ctr"`
		}
		if json.Unmarshal(projBody, &projResp) == nil {
			for _, p := range projResp {
				projStats[p.CampaignID] = struct {
					Impressions int
					Clicks      int
					TotalSpend  float64
					CTR         float64
				}{p.Impressions, p.Clicks, p.TotalSpend, p.CTR}
			}
		}
	}

	// Load win rates for all campaigns — one batch call (this used to
	// hit /campaigns/{id}/win-rate once per campaign, sequentially:
	// 50 campaigns = 50 round-trips before the page rendered).
	winRates := make(map[string]struct {
		BidsToday int
		WinRate   float64
	})
	wrBody, _ := h.coreGet("/v1/advertisers/me/win-rates", claims)
	var wrResp struct {
		Rates []struct {
			CampaignID string  `json:"campaignId"`
			BidsToday  int     `json:"bidsToday"`
			WinRate    float64 `json:"winRate"`
		} `json:"rates"`
	}
	if json.Unmarshal(wrBody, &wrResp) == nil {
		for _, wr := range wrResp.Rates {
			winRates[wr.CampaignID] = struct {
				BidsToday int
				WinRate   float64
			}{wr.BidsToday, wr.WinRate}
		}
	}

	// Per-campaign spend today from tracking_events — the same source
	// and UTC window as the account-level tile above, so the rows sum
	// to the header. (The entity's in-memory `spent` is kept as the
	// fallback when the projection endpoint is unavailable; it can
	// disagree after restarts.)
	campSpendToday := make(map[string]string)
	campSpendOK := false
	csBody, _ := h.coreGet("/v1/advertisers/me/campaign-spend-today", claims)
	var csResp struct {
		Campaigns []struct {
			CampaignID string `json:"campaignId"`
			Spend      string `json:"spend"`
		} `json:"campaigns"`
	}
	if json.Unmarshal(csBody, &csResp) == nil {
		campSpendOK = true
		for _, cs := range csResp.Campaigns {
			campSpendToday[cs.CampaignID] = cs.Spend
		}
	}

	// Aggregates for the summary tiles. CTR is impression-weighted
	// (total clicks / total impressions), win rate is bid-weighted —
	// a flat average of per-campaign ratios would let a 3-impression
	// campaign swing the account number.
	var sumImps, sumClicks, sumBids int
	var sumWins float64

	// Schedule wall-clock rendering follows the account timezone — the
	// same boundary the budget day rolls on. Stored instants are
	// unchanged; only their interpretation and display shift.
	schedTz, schedLoc := h.accountTimeContext(r.Context(), claims.AdvertiserID)
	for _, c := range campResp.Data {
		var startLocal, startDisplay string
		var startFuture bool
		if t, err := time.Parse(time.RFC3339, c.Schedule.StartAt); err == nil {
			// datetime-local needs "YYYY-MM-DDTHH:MM" with no timezone
			// suffix; rendered in the account zone so the round trip
			// through parseScheduleInput is the identity.
			startLocal = t.In(schedLoc).Format("2006-01-02T15:04")
			startDisplay = t.In(schedLoc).Format("Jan 2, 2006 15:04 MST")
			startFuture = time.Now().Before(t)
		}
		var endLocal, endDisplay string
		var endPassed bool
		if c.Schedule.EndAt != nil {
			if t, err := time.Parse(time.RFC3339, *c.Schedule.EndAt); err == nil {
				endLocal = t.In(schedLoc).Format("2006-01-02T15:04")
				endDisplay = t.In(schedLoc).Format("Jan 2, 2006 15:04 MST")
				endPassed = time.Now().After(t)
			}
		}
		cd := campaignData{
			ID:                    c.ID,
			Name:                  c.Name,
			Status:                c.Status,
			AdProductCategory:     c.AdProductCategory,
			DailyBudget:           money(c.Budget.Daily),
			MaxCPM:                money(c.Bidding.MaxCPM),
			LandingURL:            c.LandingURL,
			BidOnUnmatchedContext: c.BidOnUnmatchedContext,
			Untargeted:            c.Untargeted,
			TargetCategories:      c.TargetCategories,
			AdProductCategoryName: c.AdProductCategoryName,
			SiteAllowlist:         c.SiteAllowlist,
			StartAtLocal:          startLocal,
			StartAtDisplay:        startDisplay,
			StartAtFuture:         startFuture,
			EndAtLocal:            endLocal,
			EndAtDisplay:          endDisplay,
			EndAtPassed:           endPassed,
		}
		if campSpendOK {
			// Tracking-events source: campaigns with no impressions
			// today have no row → genuinely $0 today.
			if s, ok := campSpendToday[c.ID]; ok {
				cd.SpendToday = money(s)
			} else {
				cd.SpendToday = "0.00"
			}
		} else if c.Spent != nil {
			cd.SpendToday = money(*c.Spent)
		}
		// Budget bar: today's spend as a fraction of the daily budget.
		// (BudgetPct existed in the struct but was never assigned — the
		// bars rendered 0% regardless of spend.)
		if spend, err := strconv.ParseFloat(cd.SpendToday, 64); err == nil {
			if budget, err := strconv.ParseFloat(cd.DailyBudget, 64); err == nil && budget > 0 {
				cd.BudgetPct = math.Round(math.Min(100, spend/budget*100)*10) / 10
			}
		}
		// Pair each target-category id with its resolved name (index-aligned;
		// fall back to the id) for the edit form's pre-filled chips.
		for i, id := range c.TargetCategories {
			name := id
			if i < len(c.TargetCategoryNames) && c.TargetCategoryNames[i] != "" {
				name = c.TargetCategoryNames[i]
			}
			cd.TargetCategoryChips = append(cd.TargetCategoryChips, chip{ID: id, Name: name})
		}
		cd.TargetCategoryChipsJSON = "[]"
		if len(cd.TargetCategoryChips) > 0 {
			if b, err := json.Marshal(cd.TargetCategoryChips); err == nil {
				cd.TargetCategoryChipsJSON = string(b)
			}
		}
		// Same pairing for the Gemini-suggested fallback set, so the edit
		// form can restore these chips when the explicit set is cleared.
		for i, id := range c.SuggestedCategories {
			name := id
			if i < len(c.SuggestedCategoryNames) && c.SuggestedCategoryNames[i] != "" {
				name = c.SuggestedCategoryNames[i]
			}
			cd.SuggestedCategoryChips = append(cd.SuggestedCategoryChips, chip{ID: id, Name: name})
		}
		cd.SuggestedCategoryChipsJSON = "[]"
		if len(cd.SuggestedCategoryChips) > 0 {
			if b, err := json.Marshal(cd.SuggestedCategoryChips); err == nil {
				cd.SuggestedCategoryChipsJSON = string(b)
			}
		}
		// Projection stats (historical, from read-side DB)
		if ps, ok := projStats[c.ID]; ok {
			cd.Impressions = ps.Impressions
			cd.Clicks = ps.Clicks
			sumImps += ps.Impressions
			sumClicks += ps.Clicks
			if ps.Impressions > 0 {
				cd.CTR = fmt.Sprintf("%.1f%%", ps.CTR*100)
				cd.ECPM = fmt.Sprintf("$%.2f", ps.TotalSpend/float64(ps.Impressions)*1000)
			}
		}
		// Win rate
		if wr, ok := winRates[c.ID]; ok {
			cd.BidsToday = wr.BidsToday
			cd.WinRate = fmt.Sprintf("%.1f%%", wr.WinRate*100)
			sumBids += wr.BidsToday
			sumWins += wr.WinRate * float64(wr.BidsToday)
			if wr.WinRate < 0.1 {
				cd.WinRateClass = "text-red-600"
			} else if wr.WinRate < 0.3 {
				cd.WinRateClass = "text-yellow-600"
			} else {
				cd.WinRateClass = "text-green-600"
			}
		}
		campaigns = append(campaigns, cd)
	}

	var avgCTR, avgWinRate string
	if sumImps > 0 {
		avgCTR = fmt.Sprintf("%.1f%%", float64(sumClicks)/float64(sumImps)*100)
	}
	if sumBids > 0 {
		avgWinRate = fmt.Sprintf("%.1f%%", sumWins/float64(sumBids)*100)
	}

	// Sort + paginate AFTER the aggregates: the summary tiles describe
	// the whole account, not the visible page.
	totalCampaigns := len(campaigns)
	sortCampaigns(campaigns, r.URL.Query().Get("sort"))
	start, end, nav := buildListNav(r, len(campaigns), 10)
	campaigns = campaigns[start:end]

	// Honest chip marking: resolve inventory availability for every topic
	// chip on the visible page in one lookup, so the edit panels show
	// which targeted/auto-derived topics publisher inventory actually
	// backs. Lookup failure leaves chips unmarked (unknown ≠ none).
	var chipIDs []string
	for i := range campaigns {
		for _, c := range campaigns[i].TargetCategoryChips {
			chipIDs = append(chipIDs, c.ID)
		}
		for _, c := range campaigns[i].SuggestedCategoryChips {
			chipIDs = append(chipIDs, c.ID)
		}
	}
	if avail := h.fetchCategoryAvailability(claims, chipIDs); avail != nil {
		mark := func(chips []chip) ([]chip, string) {
			for j := range chips {
				if a, ok := avail[chips[j].ID]; ok {
					chips[j].Inv = a.Status
				}
			}
			b, err := json.Marshal(chips)
			if err != nil {
				return chips, "[]"
			}
			return chips, string(b)
		}
		for i := range campaigns {
			if len(campaigns[i].TargetCategoryChips) > 0 {
				campaigns[i].TargetCategoryChips, campaigns[i].TargetCategoryChipsJSON = mark(campaigns[i].TargetCategoryChips)
			}
			if len(campaigns[i].SuggestedCategoryChips) > 0 {
				campaigns[i].SuggestedCategoryChips, campaigns[i].SuggestedCategoryChipsJSON = mark(campaigns[i].SuggestedCategoryChips)
			}
		}
	}

	// Going rates beside the Max CPM inputs — without market visibility
	// the Max CPM field is a guess, not a decision. Unfiltered here (the
	// whole network); the topic picker and edit panels re-fetch the hint
	// scoped to their categories — context is this network's unit of
	// value, so health and soccer inventory never blend silently.
	marketRates := h.fetchMarketRates(claims, "")

	h.render(w, r, "advertiser/campaigns.html", pageData{
		Title:          "Campaigns",
		Nav:            "campaigns",
		User:           user,
		Campaigns:      campaigns,
		AdvBudget:      advBudget,
		AdvAvgCTR:      avgCTR,
		AdvAvgWinRate:  avgWinRate,
		BlockedDomains: blockedDomains,
		ServedSites:    servedSites,
		NoCampaigns:    totalCampaigns == 0,
		ListNav:        nav,
		MarketRates:    marketRates,
		ScheduleTz:     schedTz,
	})
}

// fetchMarketRates loads the going-rate view, optionally scoped to a
// comma-separated set of demand category ids. Context is the unit of
// value on this network: a blended health+soccer number describes
// neither market, so callers pass the categories they mean and the
// hint names its scope.
func (h *Handler) fetchMarketRates(claims *model.Claims, categories string) *marketRatesData {
	u := "/v1/advertisers/me/market-rates?days=7"
	if categories != "" {
		u += "&categories=" + url.QueryEscape(categories)
	}
	mrBody, _ := h.coreGet(u, claims)
	var mr struct {
		Days    int `json:"days"`
		Overall *struct {
			P25    *string `json:"p25"`
			Median *string `json:"median"`
			P75    *string `json:"p75"`
		} `json:"overall"`
		Sites []struct {
			SiteLabel   string  `json:"siteLabel"`
			Impressions int64   `json:"impressions"`
			P25         *string `json:"p25"`
			Median      *string `json:"median"`
			P75         *string `json:"p75"`
			Floor       *string `json:"floor"`
		} `json:"sites"`
		ReachLadder []float64 `json:"reachLadder"`
		FloorFrom   *string   `json:"floorFrom"`
	}
	// A scope with no cleared impressions still has an entry price —
	// floorFrom is independent of trade history, so its presence alone
	// keeps the hint alive (rendered as "no trades yet" + the floor).
	// A scoped view stays alive even with NO market data at all: the
	// inventory-availability line ("no publisher inventory matches
	// these topics") is the most important thing to say right then.
	marketEmpty := json.Unmarshal(mrBody, &mr) != nil ||
		(mr.Overall == nil && len(mr.Sites) == 0 && mr.FloorFrom == nil)
	if marketEmpty && categories == "" {
		return nil
	}
	deref := func(p *string) string {
		if p == nil {
			return ""
		}
		return *p
	}
	out := &marketRatesData{Days: mr.Days, ReachLadderJSON: "null"}
	if len(mr.ReachLadder) > 0 {
		if lj, err := json.Marshal(mr.ReachLadder); err == nil {
			out.ReachLadderJSON = string(lj)
		}
	}
	if mr.Overall != nil {
		out.OverallP25 = deref(mr.Overall.P25)
		out.OverallMedian = deref(mr.Overall.Median)
		out.OverallP75 = deref(mr.Overall.P75)
	}
	for _, srow := range mr.Sites {
		out.Sites = append(out.Sites, marketRateRow{
			SiteLabel:   srow.SiteLabel,
			Impressions: srow.Impressions,
			P25:         deref(srow.P25),
			Median:      deref(srow.Median),
			P75:         deref(srow.P75),
			Floor:       deref(srow.Floor),
		})
	}
	if mr.FloorFrom != nil {
		out.FloorFrom = *mr.FloorFrom
	} else {
		// Older core without floorFrom: fall back to the cheapest
		// per-site floor riding on the trade rows.
		for _, srow := range out.Sites {
			if srow.Floor == "" {
				continue
			}
			if out.FloorFrom == "" || srow.Floor < out.FloorFrom {
				out.FloorFrom = srow.Floor
			}
		}
	}
	if categories != "" {
		names := h.taxonomyNames(claims)
		var labels []string
		for _, id := range strings.Split(categories, ",") {
			id = strings.TrimSpace(id)
			if id == "" {
				continue
			}
			if n, ok := names[id]; ok && n != "" {
				labels = append(labels, n)
			} else {
				labels = append(labels, id)
			}
		}
		out.ScopeLabel = strings.Join(labels, ", ")
		// Inventory honesty line: the rate numbers only ever come from
		// topics that actually traded, so say how many of the selected
		// topics that is — and when none are even declared, say plainly
		// that targeting only these topics buys nothing today.
		if avail := h.fetchCategoryAvailability(claims, strings.Split(categories, ",")); avail != nil {
			out.AvailabilityKnown = true
			for _, a := range avail {
				out.TotalTopics++
				switch a.Status {
				case "live":
					out.LiveTopics++
				case "declared":
					out.DeclaredTopics++
				}
			}
		}
		// Nothing to quote AND nothing to say about inventory → no hint.
		if marketEmpty && !out.AvailabilityKnown {
			return nil
		}
	}
	return out
}

// MarketRatesHint renders the going-rate hint fragment scoped to the
// given categories — fetched by the create form's topic picker and by
// edit panels on open, so each context sees its own market.
func (h *Handler) MarketRatesHint(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	data := h.fetchMarketRates(claims, r.URL.Query().Get("categories"))
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	getPage(h.lang(r, user), "advertiser/campaigns.html").ExecuteTemplate(w, "market-rates-hint", data)
}

func (h *Handler) AdvertiserAccount(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	var advBudget *advertiserBudget
	advBody, _ := h.coreGet("/v1/advertisers/me", claims)
	var advResp struct {
		Budget struct {
			DailyBudget string `json:"dailyBudget"`
			Remaining   string `json:"remaining"`
			SpendToday  string `json:"spendToday"`
		} `json:"budget"`
	}
	budgetUnset := true
	if json.Unmarshal(advBody, &advResp) == nil {
		parsed, _ := strconv.ParseFloat(advResp.Budget.DailyBudget, 64)
		// Spent/remaining from the entity's reserved-spend counter — the values
		// the serve-time gate benches on — so this line matches the bench and
		// agrees with the Campaigns page. Delivered spend is billing/performance
		// (Stats/Report), not this budget-enforcement view.
		advBudget = &advertiserBudget{
			DailyBudget: money(advResp.Budget.DailyBudget),
			Remaining:   money(advResp.Budget.Remaining),
			SpendToday:  money(advResp.Budget.SpendToday),
			IsZero:      parsed <= 0,
		}
		budgetUnset = advBudget.IsZero
	}

	noCampaigns := true
	if !budgetUnset {
		campsBody, _ := h.coreGet("/v1/advertisers/me/campaigns?limit=1", claims)
		var campsResp struct {
			Data []json.RawMessage `json:"data"`
		}
		if json.Unmarshal(campsBody, &campsResp) == nil {
			noCampaigns = len(campsResp.Data) == 0
		}
	}

	// The embedded wallet summary is money-page content: org members see
	// budget (pacing) but the balance stays with the org's admins.
	walletBalance, walletUnfunded := "", false
	if user.CanBilling() {
		walletBalance, walletUnfunded = h.walletSummary(r.Context(), claims.AdvertiserID)
	}
	h.render(w, r, "advertiser/account.html", pageData{
		Title:          "Account",
		Nav:            "account",
		User:           user,
		AdvBudget:      advBudget,
		BudgetUnset:    budgetUnset,
		NoCampaigns:    noCampaigns,
		WalletNotice:   h.walletNotice(r.Context(), claims.AdvertiserID),
		WalletBalance:  walletBalance,
		WalletUnfunded: walletUnfunded,
	})
}

func (h *Handler) SetAdvertiserBudget(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	body, _ := json.Marshal(map[string]string{"dailyBudget": r.FormValue("dailyBudget")})
	h.corePut("/v1/advertisers/me/budget", claims, string(body))
	redirect := r.Referer()
	if redirect == "" {
		redirect = "/advertiser/campaigns"
	}
	http.Redirect(w, r, redirect, http.StatusSeeOther)
}

func (h *Handler) CreateCampaign(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	// Schedule. The form's <input type="datetime-local"> sends a zoneless
	// wall-clock string like "2026-12-31T17:00" — interpreted in the
	// advertiser's account timezone (the budget-rollover boundary), then
	// stored as a UTC instant. Empty startAt = "start now"; empty endAt =
	// open-ended campaign.
	_, loc := h.accountTimeContext(r.Context(), claims.AdvertiserID)
	startAt := r.FormValue("startAt")
	if startAt == "" {
		startAt = time.Now().UTC().Format(time.RFC3339)
	} else {
		startAt = parseScheduleInput(startAt, loc)
	}
	schedule := map[string]string{"startAt": startAt}
	if endAt := r.FormValue("endAt"); endAt != "" {
		schedule["endAt"] = parseScheduleInput(endAt, loc)
	}
	// Server-side guard mirroring the form's `required` (and the core's
	// own invalid_name rejection): never create a nameless campaign.
	name := strings.TrimSpace(r.FormValue("name"))
	if name == "" {
		http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
		return
	}
	payload := map[string]any{
		"name":              name,
		"budget":            map[string]string{"daily": r.FormValue("budget")},
		"schedule":          schedule,
		"adProductCategory": r.FormValue("adProductCategory"),
		"bidding":           map[string]string{"strategy": "fixed", "maxCpm": r.FormValue("maxCpm")},
		"landingUrl":        r.FormValue("landingUrl"),
	}
	// targetCategories (comma-separated chip values from the form) → JSON
	// array. Empty string means "no explicit targeting" — the campaign
	// will rely on auto-derive from creative LP via RefineCategoriesFromCreative,
	// or sit untargeted if that returns nothing usable.
	if raw := strings.TrimSpace(r.FormValue("targetCategories")); raw != "" {
		parts := strings.Split(raw, ",")
		cats := make([]string, 0, len(parts))
		for _, p := range parts {
			if t := strings.TrimSpace(p); t != "" {
				cats = append(cats, t)
			}
		}
		if len(cats) > 0 {
			payload["targetCategories"] = cats
		}
	}
	// siteAllowlist (comma-separated siteIds from the media picker) → JSON
	// array. Empty = no restriction (bid everywhere). Matches CampaignEntity
	// by siteId, so the picker submits siteIds (label shows the domain).
	if raw := strings.TrimSpace(r.FormValue("siteAllowlist")); raw != "" {
		parts := strings.Split(raw, ",")
		sites := make([]string, 0, len(parts))
		for _, p := range parts {
			if t := strings.TrimSpace(p); t != "" {
				sites = append(sites, t)
			}
		}
		if len(sites) > 0 {
			payload["siteAllowlist"] = sites
		}
	}
	body, _ := json.Marshal(payload)
	h.corePost("/v1/advertisers/me/campaigns", claims, string(body))
	http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
}

// UpdateCampaignSchedule patches an existing campaign's startAt and
// endAt. Form: campaignId, startAt, endAt (RFC 3339 or datetime-local).
// Empty startAt = "start now"; empty endAt = open-ended.
func (h *Handler) UpdateCampaignSchedule(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	campID := r.FormValue("campaignId")
	_, loc := h.accountTimeContext(r.Context(), claims.AdvertiserID)
	startAt := r.FormValue("startAt")
	if startAt == "" {
		startAt = time.Now().UTC().Format(time.RFC3339)
	} else {
		startAt = parseScheduleInput(startAt, loc)
	}
	schedule := map[string]any{"startAt": startAt}
	if endAt := r.FormValue("endAt"); endAt != "" {
		schedule["endAt"] = parseScheduleInput(endAt, loc)
	}
	body, _ := json.Marshal(map[string]any{"schedule": schedule})
	h.corePatch(fmt.Sprintf("/v1/advertisers/me/campaigns/%s", campID), claims, string(body))
	http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
}

// accountTimeContext resolves the org's IANA timezone for a core entity
// id (either side) — the account timezone that budget rollover and
// settlement days follow. Used for schedule input/display and report
// ranges. Unset or unloadable zones fall back to UTC, matching the
// core's own Timezones.zoneOf fallback.
func (h *Handler) accountTimeContext(ctx context.Context, entityID string) (string, *time.Location) {
	tz, err := h.orgRepo.TimezoneByEntity(ctx, entityID)
	if err != nil || tz == "" {
		return "UTC", time.UTC
	}
	loc, lerr := time.LoadLocation(tz)
	if lerr != nil {
		return "UTC", time.UTC
	}
	return tz, loc
}

// displayZone is the zone for RENDERING-ONLY surfaces (chart axes, hour
// labels): the viewer's preference timezone when set, else the org's
// account timezone, else UTC. Never used for bucketing or money — those
// take accountTimeContext directly.
func (h *Handler) displayZone(r *http.Request, u *model.User, entityID string) *time.Location {
	if u != nil && u.Timezone != "" {
		if loc, err := time.LoadLocation(u.Timezone); err == nil {
			return loc
		}
	}
	_, loc := h.accountTimeContext(r.Context(), entityID)
	return loc
}

// parseScheduleInput converts a datetime-local form value (no zone suffix)
// to RFC 3339 UTC, interpreting the wall-clock time in the advertiser's
// account zone. Values that already carry a zone (Z or offset) pass
// through untouched.
func parseScheduleInput(v string, loc *time.Location) string {
	if strings.Contains(v, "Z") || strings.Contains(v, "+") {
		return v
	}
	for _, layout := range []string{"2006-01-02T15:04", "2006-01-02T15:04:05"} {
		if t, err := time.ParseInLocation(layout, v, loc); err == nil {
			return t.UTC().Format(time.RFC3339)
		}
	}
	return v + ":00Z"
}

// splitCSV turns a comma-separated chip value (from the type-ahead pickers)
// into a trimmed, empty-stripped slice. Returns a non-nil empty slice for an
// empty input so callers can send it as a JSON [] to clear a list.
func splitCSV(s string) []string {
	out := []string{}
	for _, p := range strings.Split(s, ",") {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}

// UpdateCampaign proxies the comprehensive campaign edit modal to the core
// PATCH endpoint, updating every advertiser-editable setting in one request
// (vs. the focused cpm/schedule/filler handlers). Blank fields are omitted
// (no change); the two picker lists are sent whenever present so emptying
// them clears the restriction. Campaign name is intentionally not editable —
// it isn't backed by the entity (getCampaign returns the campaignId as name).
func (h *Handler) UpdateCampaign(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	campID := r.FormValue("campaignId")
	if campID == "" {
		http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
		return
	}

	payload := map[string]any{}
	// landingUrl and adProductCategory are intentionally NOT read here: both
	// are fixed at creation (the Edit modal renders them disabled). The
	// landing page is the creative's identity anchor and is enforced immutable
	// server-side too (CreativeGuards.landingUrlChanged); "what you're
	// advertising" likewise defines the campaign and must not be re-pointable
	// via the edit path. To change either, create a new campaign.
	if v := strings.TrimSpace(r.FormValue("name")); v != "" {
		payload["name"] = v
	}
	if v := strings.TrimSpace(r.FormValue("budget")); v != "" {
		payload["budget"] = map[string]string{"daily": v}
	}
	if v := strings.TrimSpace(r.FormValue("maxCpm")); v != "" {
		payload["bidding"] = map[string]string{"strategy": "fixed", "maxCpm": v}
	}
	// Picker lists: present (even if empty) ⇒ send, so removing every chip
	// clears the restriction. Absent ⇒ omit (no change).
	if r.Form.Has("targetCategories") {
		payload["targetCategories"] = splitCSV(r.FormValue("targetCategories"))
	}
	if r.Form.Has("siteAllowlist") {
		payload["siteAllowlist"] = splitCSV(r.FormValue("siteAllowlist"))
	}
	// Schedule: only when a start is provided (CampaignSchedule.startAt is
	// required to also set endAt). Mirrors CreateCampaign's datetime-local
	// → account-zone → UTC-instant conversion.
	if startAt := r.FormValue("startAt"); startAt != "" {
		_, loc := h.accountTimeContext(r.Context(), claims.AdvertiserID)
		schedule := map[string]string{"startAt": parseScheduleInput(startAt, loc)}
		if endAt := r.FormValue("endAt"); endAt != "" {
			schedule["endAt"] = parseScheduleInput(endAt, loc)
		}
		payload["schedule"] = schedule
	}

	body, _ := json.Marshal(payload)
	h.corePatch(fmt.Sprintf("/v1/advertisers/me/campaigns/%s", campID), claims, string(body))
	http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
}

func (h *Handler) ToggleCampaignStatus(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	campID := r.FormValue("campaignId")
	newStatus := r.FormValue("status")
	body, _ := json.Marshal(map[string]string{"status": newStatus})
	h.corePut(fmt.Sprintf("/v1/advertisers/me/campaigns/%s/status", campID), claims, string(body))
	http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
}

func (h *Handler) UpdateFloorCPM(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	floorCpm := r.FormValue("floorCpm")
	body, _ := json.Marshal(map[string]any{
		"floorCpm": floorCpm,
	})
	h.corePatch(fmt.Sprintf("/v1/publishers/me/sites/%s", siteID), claims, string(body))
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

func (h *Handler) UpdateMinFloorCPM(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	minFloorCpm := r.FormValue("minFloorCpm")
	body, _ := json.Marshal(map[string]any{
		"minFloorCpm": minFloorCpm,
	})
	h.corePatch(fmt.Sprintf("/v1/publishers/me/sites/%s", siteID), claims, string(body))
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

// argmaxHistoryNav carries the date-picker state for the chart so the
// template can render the input + a "today" link + the displayed range.
type argmaxHistoryNav struct {
	SelectedDate string // "YYYY-MM-DD" — empty if showing the most-recent N
	Today        string // "YYYY-MM-DD" UTC today, for the picker max + reset link
	PrevDate     string // previous day, for ← nav
	NextDate     string // next day, for → nav (empty if SelectedDate == today)
}

// xAxisTick is one timestamp label along the chart's X axis, positioned
// at a viewBox-coordinate X (0-100) with a human-readable HH:MM label.
type xAxisTick struct {
	X     int
	Label string
}

// argmaxHistoryPoint is one completed cycle's pick, sourced from the
// persistent /sweep-history endpoint. Renders as a simple SVG chart of
// "optimized floor over time".
type argmaxHistoryPoint struct {
	TS    string  // ISO timestamp, for tooltip and X-axis label
	Floor float64 // argmax value picked at this cycle
	Label string  // formatted "$X.XX"
	X     int     // SVG x coordinate (computed; viewBox uses 0–100)
	Y     int     // SVG y coordinate (computed; viewBox uses 0–100, inverted)
}

// argmaxStability summarizes the shape of the argmax history into
// human-readable labels. Two consecutive picks at the same value
// produce a horizontal line on the chart which is *visually* ambiguous
// — this struct interprets the data for the publisher so they don't
// have to read the shape themselves.
type argmaxStability struct {
	Status      string // "Stable" / "Mildly variable" / "Highly variable" / "Insufficient data"
	StatusColor string // tailwind color class for the badge
	Summary     string // e.g. "Last 5 cycles agreed on $4.50" or "Last 5 picks ranged $1.20 - $5.60"
	StdDev      string // formatted "$X.XX"
	Recent      string // e.g. "$4.50, $4.50, $3.40" — comma-separated recent picks
	N           int    // count of cycles considered
}

// trafficShapeView renders the learned 24-bucket traffic shapes on the
// observations page — pacing decisions are only explainable next to the
// curve that drives them.
type shapeBar struct {
	Hour      int
	HeightPct int    // 4..100, scaled to the max volume across both shapes
	Title     string // tooltip: "14:00 JST · 1.42× average"
	IsNow     bool   // current display-zone hour on today's day-type row
}

type trafficShapeView struct {
	Weekday  []shapeBar
	Weekend  []shapeBar
	Learning bool   // both shapes ≈ uniform → tracker still converging
	TzLabel  string // zone the bars are ROTATED to for display (e.g. "Asia/Tokyo")
}

type floorObservationsData struct {
	SiteID          string
	ArgmaxHistory   []argmaxHistoryPoint // completed cycles' picks (site-wide sweep only)
	ArgmaxStability *argmaxStability     // nil if no data
	ArgmaxNav       *argmaxHistoryNav    // date picker state
	ArgmaxXTicks    []xAxisTick          // X-axis time labels for the chart
	// Sweep evidence table — nil only before the entity has finished
	// recovery and installed its optimizer.
	Sweep *floorSweepEvidence
	// Per-category learned floors (PER_CATEGORY_FLOOR_MODE). Empty unless the
	// site has per-category sweeps — then one row per demand category with its
	// latest floor. Site-wide rows (category=null) stay out of here and drive
	// the fallback sweep section. When non-empty, these ARE the page: the
	// per-category floors are what actually price each advertiser segment, so
	// they lead and the site-wide sweep is demoted to a fallback.
	CategoryFloors []categoryFloorRow
	// TrafficShape is nil when the stats fetch failed — section omitted.
	TrafficShape *trafficShapeView
	// FloorRange is the headline summary of the LIVE per-category floors
	// (categories with current bidders), e.g. "$1.00 – $2.50". Historical
	// floors are excluded — they price nothing. Empty when there are no
	// per-category floors at all.
	FloorRange string
	// Live vs fossil category counts for the headline subtitle.
	ActiveCategories     int
	HistoricalCategories int
}

// categoryFloorRow is one demand category's latest floor, for the per-category
// floor table on the floor-decisions page.
type categoryFloorRow struct {
	Category     string // demand category id
	CategoryName string // resolved taxonomy name; falls back to the id
	Floor        string // live enforced floor when known, else latest journaled, "$X.XX"
	LastUpdate   string // timestamp of the latest journaled decision
	// Live demand context (from /category-demand): what's happening NOW,
	// as opposed to SetBy/LastUpdate which describe the last decision.
	Bidders   int    // current distinct bidders (0 = none observed)
	DemandNow string // human line, e.g. "2 bidders — sweep-governed (top $2.50)"
	// Live sweep progress for competitive rows, e.g.
	// "probing candidate 3/8 at $1.75 — decision in ~22m". Empty when
	// pegged/monopoly or no optimizer state yet.
	SweepStatus string
	Historical  bool // true → no current bidders; row is a fossil, render dimmed
}

// categoryName resolves a content-taxonomy category id to its human name via
// the core taxonomy search (which matches by name OR id). Falls back to the
// raw id so the floor table never renders blank.
func (h *Handler) categoryName(claims *model.Claims, id string) string {
	body, err := h.coreGet("/v1/taxonomy/categories?q="+url.QueryEscape(id)+"&limit=8", claims)
	if err != nil {
		return id
	}
	var resp struct {
		Data []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return id
	}
	for _, c := range resp.Data {
		if c.ID == id && c.Name != "" {
			return c.Name
		}
	}
	return id
}

// floorSweepEvidence is the per-cycle picture: which candidate floors the
// optimizer has tried, the realized revenue and impression count at each,
// and which one it currently considers best. Also carries the previous
// cycle's argmax + per-candidate revenues so the dashboard can render
// this-cycle-vs-last-cycle deltas — the publisher's actual question is
// "did anything change?", and the answer needs comparison context.
type floorSweepEvidence struct {
	Phase                 string
	CurrentFloor          string
	BestFloor             string // "—" when no argmax yet (still in first sweep)
	PreviousBestFloor     string // "—" when the optimizer hasn't completed a prior cycle
	BestFloorDelta        string // signed $ change vs previous cycle's argmax, "—" if no prior
	BestFloorTrendArrow   string // "▲" / "▼" / "=" for the headline
	Cursor                int
	TicksThisCandidate    int
	ExploitTicksRemaining int
	Rows                  []floorSweepCandidateRow
	// Derived display fields, filled once the observation interval is
	// known (fetched later in the handler): honest wall-clock estimates
	// instead of the stale hard-coded "~20 seconds" / "/10" era text.
	CursorDisplay  int // 1-based probe position
	CandidateCount int // real candidate count (len(Rows))
	HoldMins       int // ≈ minutes each candidate floor is held (4 ticks × interval)
	ExploitMins    int // ≈ minutes left in the exploit hold
}

type floorSweepCandidateRow struct {
	Floor           string
	Revenue         string // dollars, this cycle
	Impressions     int64
	IsCurrent       bool   // candidate currently being measured
	IsBest          bool   // current argmax
	WasBest         bool   // was the previous cycle's argmax
	PreviousRevenue string // "—" if this candidate wasn't measured last cycle
	RevenueDelta    string // "+$0.05" / "-$0.02" / "—" / "+new"
	DeltaSign       int    // -1, 0, +1 — for visual color
	BarWidth        int    // 0-100, % of widest bar in current cycle
}

func (h *Handler) FloorObservations(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	obsLang := h.lang(r, user)
	siteID := r.PathValue("siteId")
	// Every timestamp on this page renders in the viewer's display zone
	// (preference -> org -> UTC); the date picker's "day" uses the same
	// zone end to end (the core windows the query on it via ?tz=).
	obsLoc := h.displayZone(r, user, claims.PublisherID)
	obsTzAbbrev := time.Now().In(obsLoc).Format("MST")

	// Fetch the sweep optimizer's evidence table.
	evBody, _ := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/sweep-evidence", siteID), claims)
	type sweepCandidate struct {
		Floor       string `json:"floor"`
		Revenue     string `json:"revenue"`
		Impressions int64  `json:"impressions"`
	}
	var evResp struct {
		SiteID                string           `json:"siteId"`
		Phase                 string           `json:"phase,omitempty"`
		CurrentFloor          string           `json:"currentFloor,omitempty"`
		BestFloor             string           `json:"bestFloor,omitempty"`
		Cursor                int              `json:"cursor,omitempty"`
		TicksThisCandidate    int              `json:"ticksThisCandidate,omitempty"`
		ExploitTicksRemaining int              `json:"exploitTicksRemaining,omitempty"`
		Candidates            []sweepCandidate `json:"candidates"`
		PreviousBestFloor     string           `json:"previousBestFloor,omitempty"`
		PreviousCandidates    []sweepCandidate `json:"previousCandidates"`
	}
	json.Unmarshal(evBody, &evResp)

	var sweep *floorSweepEvidence
	// Phase is empty only when the entity hasn't finished recovery yet
	// (no snapshot in the response) — leave Sweep nil in that window.
	if evResp.Phase != "" {
		curFloor, _ := strconv.ParseFloat(evResp.CurrentFloor, 64)
		bestStr := "—"
		bestVal := -1.0
		if evResp.BestFloor != "" {
			bestVal, _ = strconv.ParseFloat(evResp.BestFloor, 64)
			bestStr = fmt.Sprintf("$%.2f", bestVal)
		}
		prevBestStr := "—"
		prevBestVal := -1.0
		if evResp.PreviousBestFloor != "" {
			prevBestVal, _ = strconv.ParseFloat(evResp.PreviousBestFloor, 64)
			prevBestStr = fmt.Sprintf("$%.2f", prevBestVal)
		}

		// Best-floor delta vs previous cycle. Only meaningful when both
		// the current cycle has produced an argmax AND the prior cycle
		// completed — i.e., we're in or after the second cycle.
		bestDelta := "—"
		trend := "="
		if bestVal >= 0 && prevBestVal >= 0 {
			diff := bestVal - prevBestVal
			if diff > 0.005 {
				bestDelta = fmt.Sprintf("+$%.2f", diff)
				trend = "▲"
			} else if diff < -0.005 {
				bestDelta = fmt.Sprintf("-$%.2f", -diff)
				trend = "▼"
			} else {
				bestDelta = "$0.00"
				trend = "="
			}
		}

		// Display sweep revenues NET of the platform margin, consistent with
		// the stats page. A flat multiplier preserves the argmax and every
		// delta's sign, so the optimizer story the table tells is unchanged.
		netFactor := 1 - float64(h.settingsSvc.CurrentMarginBps(r.Context()))/10000

		// Index previous candidates by floor for delta lookup. Floor
		// values match exactly (server formats both with %.4f from the
		// same candidate-build math), so a string-keyed map is fine.
		prevByFloor := make(map[string]float64, len(evResp.PreviousCandidates))
		for _, p := range evResp.PreviousCandidates {
			rev, _ := strconv.ParseFloat(p.Revenue, 64)
			prevByFloor[p.Floor] = rev * netFactor
		}

		// Find the widest revenue in this cycle for bar scaling.
		maxRev := 0.0
		for _, c := range evResp.Candidates {
			rev, _ := strconv.ParseFloat(c.Revenue, 64)
			if rev*netFactor > maxRev {
				maxRev = rev * netFactor
			}
		}

		rows := make([]floorSweepCandidateRow, 0, len(evResp.Candidates))
		for _, c := range evResp.Candidates {
			f, _ := strconv.ParseFloat(c.Floor, 64)
			rev, _ := strconv.ParseFloat(c.Revenue, 64)
			rev *= netFactor

			prevRevStr := "—"
			deltaStr := "—"
			deltaSign := 0
			if prevRev, ok := prevByFloor[c.Floor]; ok {
				prevRevStr = fmt.Sprintf("$%.4f", prevRev)
				diff := rev - prevRev
				if diff > 0.005 {
					deltaStr = fmt.Sprintf("+$%.4f", diff)
					deltaSign = 1
				} else if diff < -0.005 {
					deltaStr = fmt.Sprintf("-$%.4f", -diff)
					deltaSign = -1
				} else {
					deltaStr = "$0.0000"
					deltaSign = 0
				}
			} else if len(evResp.PreviousCandidates) > 0 {
				// Previous cycle existed but this floor wasn't measured then
				// (e.g. minFloor moved). Mark as new rather than zero.
				deltaStr = "(new)"
			}

			barWidth := 0
			if maxRev > 0 {
				barWidth = int(60.0 * rev / maxRev)
				if barWidth < 0 {
					barWidth = 0
				}
				if barWidth > 60 {
					barWidth = 60
				}
			}

			rows = append(rows, floorSweepCandidateRow{
				Floor:           fmt.Sprintf("$%.2f", f),
				Revenue:         fmt.Sprintf("$%.4f", rev),
				Impressions:     c.Impressions,
				IsCurrent:       math.Abs(f-curFloor) < 1e-6,
				IsBest:          bestVal >= 0 && math.Abs(f-bestVal) < 1e-6,
				WasBest:         prevBestVal >= 0 && math.Abs(f-prevBestVal) < 1e-6,
				PreviousRevenue: prevRevStr,
				RevenueDelta:    deltaStr,
				DeltaSign:       deltaSign,
				BarWidth:        barWidth,
			})
		}

		sweep = &floorSweepEvidence{
			Phase:                 evResp.Phase,
			CurrentFloor:          fmt.Sprintf("$%.2f", curFloor),
			BestFloor:             bestStr,
			PreviousBestFloor:     prevBestStr,
			BestFloorDelta:        bestDelta,
			BestFloorTrendArrow:   trend,
			Cursor:                evResp.Cursor,
			TicksThisCandidate:    evResp.TicksThisCandidate,
			ExploitTicksRemaining: evResp.ExploitTicksRemaining,
			Rows:                  rows,
		}
	}

	// Build argmax-history chart from the persistent /sweep-history endpoint.
	// This source survives cluster restarts and isn't bounded by the in-memory
	// ring buffer — gives the chart real historical depth.
	//
	// If ?date=YYYY-MM-DD is set, scope the chart to that UTC calendar day
	// (the date-picker case). Otherwise return the most-recent 200 cycles.
	var argmaxHistory []argmaxHistoryPoint
	var argmaxNav *argmaxHistoryNav
	var categoryFloors []categoryFloorRow
	var floorRange string
	var activeCats, historicalCats int
	{
		selectedDate := r.URL.Query().Get("date")
		histURL := fmt.Sprintf("/v1/publishers/me/sites/%s/sweep-history?limit=200", siteID)
		if selectedDate != "" {
			histURL += "&date=" + url.QueryEscape(selectedDate) + "&tz=" + url.QueryEscape(obsLoc.String())
		}
		histBody, _ := h.coreGet(histURL, claims)
		var histResp struct {
			Decisions []struct {
				TS          string  `json:"ts"`
				ArgmaxFloor string  `json:"argmaxFloor"`
				Candidates  *string `json:"candidates"`
				Category    *string `json:"category"`
			} `json:"decisions"`
		}
		json.Unmarshal(histBody, &histResp)
		// API returns newest-first; reverse for chronological left-to-right plot.
		// Site-wide rows (category=null) drive the chart; per-category rows feed
		// the per-category table (keep only each category's latest decision).
		catLatest := map[string]argmaxHistoryPoint{}
		for i := len(histResp.Decisions) - 1; i >= 0; i-- {
			d := histResp.Decisions[i]
			floor, _ := strconv.ParseFloat(d.ArgmaxFloor, 64)
			pt := argmaxHistoryPoint{
				TS:    d.TS,
				Floor: floor,
				Label: fmt.Sprintf("$%.2f", floor),
			}
			if d.Category == nil || *d.Category == "" {
				argmaxHistory = append(argmaxHistory, pt)
			} else {
				catLatest[*d.Category] = pt // loop runs oldest→newest, so last write = newest
			}
		}
		// Live demand context: bidder counts + top observed bid per category
		// from the site's latest auction rounds. This is what disambiguates
		// the table — the journaled decisions below only say how a floor was
		// last SET, not whether anyone still bids into it.
		type liveSweep struct {
			Phase                 string  `json:"phase"`
			Cursor                int     `json:"cursor"`
			CandidateCount        int     `json:"candidateCount"`
			TicksThisCandidate    int     `json:"ticksThisCandidate"`
			TicksPerCandidate     int     `json:"ticksPerCandidate"`
			ExploitTicksRemaining int     `json:"exploitTicksRemaining"`
			CurrentFloor          float64 `json:"currentFloor"`
		}
		type liveDemand struct {
			Bidders int        `json:"bidders"`
			TopBid  float64    `json:"topBid"`
			Floor   *float64   `json:"floor"`
			Name    string     `json:"categoryName"`
			Sweep   *liveSweep `json:"sweep"`
		}
		demand := map[string]liveDemand{}
		obsInterval := 60 // seconds; overwritten by the response
		if body, err := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/category-demand", siteID), claims); err == nil {
			var resp struct {
				Categories []struct {
					CategoryID string     `json:"categoryId"`
					Name       string     `json:"categoryName"`
					Floor      *float64   `json:"floor"`
					Bidders    int        `json:"bidders"`
					TopBid     float64    `json:"topBid"`
					Sweep      *liveSweep `json:"sweep"`
				} `json:"categories"`
				ObservationIntervalSeconds int `json:"observationIntervalSeconds"`
			}
			if json.Unmarshal(body, &resp) == nil {
				if resp.ObservationIntervalSeconds > 0 {
					obsInterval = resp.ObservationIntervalSeconds
				}
				for _, c := range resp.Categories {
					demand[c.CategoryID] = liveDemand{Bidders: c.Bidders, TopBid: c.TopBid, Floor: c.Floor, Name: c.Name, Sweep: c.Sweep}
				}
			}
		}
		// Backfill the site-wide sweep panel's wall-clock estimates now that
		// the observation interval is known (the panel was built above).
		if sweep != nil {
			sweep.CursorDisplay = sweep.Cursor + 1
			sweep.CandidateCount = len(sweep.Rows)
			sweep.HoldMins = (4*obsInterval + 59) / 60
			sweep.ExploitMins = (sweep.ExploitTicksRemaining*obsInterval + 59) / 60
		}

		// Render the sweep's raw counters as a human progress line: what
		// the optimizer is doing right now and when its next decision lands.
		sweepStatus := func(d liveDemand) string {
			if d.Sweep == nil || d.Bidders < 2 {
				return ""
			}
			sw := d.Sweep
			switch sw.Phase {
			case "sweep":
				ticksLeft := (sw.CandidateCount-sw.Cursor)*sw.TicksPerCandidate - sw.TicksThisCandidate
				if ticksLeft < 0 {
					ticksLeft = 0
				}
				mins := (ticksLeft*obsInterval + 59) / 60
				return i18n.T(obsLang, "probing candidate %d/%d at $%.2f — decision in ~%dm", sw.Cursor+1, sw.CandidateCount, sw.CurrentFloor, mins)
			case "exploit":
				mins := (sw.ExploitTicksRemaining*obsInterval + 59) / 60
				return i18n.T(obsLang, "holding at $%.2f — next probe cycle in ~%dm", sw.CurrentFloor, mins)
			default:
				return i18n.T(obsLang, "sweep cycle starting")
			}
		}

		minFloor, maxFloor := math.Inf(1), math.Inf(-1)
		for cat, pt := range catLatest {
			lastUpdate := pt.TS
			if t, err := time.Parse(time.RFC3339Nano, pt.TS); err == nil {
				lastUpdate = t.In(obsLoc).Format("01-02 15:04") + " " + obsTzAbbrev
			}
			row := categoryFloorRow{
				Category:     cat,
				CategoryName: h.categoryName(claims, cat),
				Floor:        pt.Label,
				LastUpdate:   lastUpdate,
			}
			if d, ok := demand[cat]; ok && d.Bidders > 0 {
				row.Bidders = d.Bidders
				if d.Bidders == 1 {
					row.DemandNow = i18n.T(obsLang, "1 bidder — pegged to bid ($%.2f)", d.TopBid)
				} else {
					row.DemandNow = i18n.T(obsLang, "%d bidders — sweep-governed (top $%.2f)", d.Bidders, d.TopBid)
				}
				row.SweepStatus = sweepStatus(d)
				// Prefer the LIVE enforced floor over the last journaled one
				// when they disagree (the journal lags mid-cycle).
				if d.Floor != nil {
					row.Floor = fmt.Sprintf("$%.2f", *d.Floor)
				}
			} else {
				row.DemandNow = i18n.T(obsLang, "no current bidders — historical")
				row.Historical = true
			}
			categoryFloors = append(categoryFloors, row)
			// Headline range/count: LIVE categories only. Fossil floors
			// (no current bidders) price nothing and would stretch the
			// range with meaningless values.
			if !row.Historical {
				activeCats++
				eff := pt.Floor
				if d, ok := demand[cat]; ok && d.Floor != nil {
					eff = *d.Floor
				}
				if eff < minFloor {
					minFloor = eff
				}
				if eff > maxFloor {
					maxFloor = eff
				}
			} else {
				historicalCats++
			}
		}
		// Categories with live bidders but no journaled decision yet (e.g.
		// newly targeted, first sweep cycle still in progress) — show them
		// so the table reflects demand the moment it exists.
		for cat, d := range demand {
			if _, seen := catLatest[cat]; seen || d.Bidders == 0 {
				continue
			}
			row := categoryFloorRow{
				Category:     cat,
				CategoryName: d.Name,
				Floor:        "site fallback",
				Bidders:      d.Bidders,
			}
			if d.Floor != nil {
				row.Floor = fmt.Sprintf("$%.2f", *d.Floor)
			}
			row.SweepStatus = sweepStatus(d)
			if d.Bidders == 1 {
				row.DemandNow = i18n.T(obsLang, "1 bidder — pegged to bid ($%.2f)", d.TopBid)
			} else {
				row.DemandNow = i18n.T(obsLang, "%d bidders — sweep-governed (top $%.2f)", d.Bidders, d.TopBid)
			}
			categoryFloors = append(categoryFloors, row)
		}
		sort.Slice(categoryFloors, func(i, j int) bool {
			return categoryFloors[i].Category < categoryFloors[j].Category
		})
		if activeCats > 0 && !math.IsInf(minFloor, 1) {
			if math.Abs(maxFloor-minFloor) < 0.005 {
				floorRange = fmt.Sprintf("$%.2f", minFloor)
			} else {
				floorRange = fmt.Sprintf("$%.2f – $%.2f", minFloor, maxFloor)
			}
		} else if activeCats > 0 {
			floorRange = "measuring"
		} else if len(categoryFloors) > 0 {
			floorRange = "no live demand"
		}

		// Build date-picker nav state. Today and prev/next anchors are
		// always available; the template decides whether to enable them.
		// "Today" is the display zone's current day, matching the ?tz=
		// window the core applies to the picked date.
		today := time.Now().In(obsLoc).Format("2006-01-02")
		nav := &argmaxHistoryNav{
			SelectedDate: selectedDate,
			Today:        today,
		}
		// Anchor date is the selected one, or today if none selected
		anchor := selectedDate
		if anchor == "" {
			anchor = today
		}
		if t, err := time.Parse("2006-01-02", anchor); err == nil {
			nav.PrevDate = t.AddDate(0, 0, -1).Format("2006-01-02")
			next := t.AddDate(0, 0, 1).Format("2006-01-02")
			if next <= today {
				nav.NextDate = next
			}
		}
		argmaxNav = nav

		// Compute SVG coords: X spans 0–100 across all points, Y inverts
		// (0 = top of chart = $10, 100 = bottom = $0). Y scale uses the
		// 0–10 floor range hard-coded (matches sweep's default maxFloor).
		// If there's only one point we put it dead center horizontally.
		// Y coordinates map to a 0-40 range in viewBox units (instead of
		// 0-100) so the chart's viewBox can be wide-aspect (~3:1) without
		// preserveAspectRatio stretching. Template gridlines + labels use
		// matching 0-40 Y positions: $10=0, $7.50=10, $5=20, $2.50=30, $0=40.
		n := len(argmaxHistory)
		for j := range argmaxHistory {
			if n == 1 {
				argmaxHistory[j].X = 50
			} else {
				argmaxHistory[j].X = int(float64(j) / float64(n-1) * 100)
			}
			yf := argmaxHistory[j].Floor / 10.0
			if yf < 0 {
				yf = 0
			}
			if yf > 1 {
				yf = 1
			}
			argmaxHistory[j].Y = int((1.0 - yf) * 40)
		}
	}

	// Compute the stability summary from the argmax history. This is what
	// the publisher reads to decide "is the optimizer working?" without
	// having to interpret the chart shape — a horizontal line at one
	// value can mean "stable convergence" or "no data," and the chart
	// alone can't disambiguate.
	stabilityPtr := computeArgmaxStability(obsLang, argmaxHistory)

	// X-axis time ticks: interpolate 5 timestamps evenly across the
	// visible cycle range. With a date filter active, this naturally
	// shows the time-of-day progression. Multi-day views fall back to
	// MM-DD format.
	var argmaxXTicks []xAxisTick
	if len(argmaxHistory) >= 2 {
		first, errF := time.Parse(time.RFC3339Nano, argmaxHistory[0].TS)
		last, errL := time.Parse(time.RFC3339Nano, argmaxHistory[len(argmaxHistory)-1].TS)
		if errF == nil && errL == nil {
			span := last.Sub(first)
			sameDay := first.In(obsLoc).Format("2006-01-02") == last.In(obsLoc).Format("2006-01-02")
			layout := "15:04"
			if !sameDay {
				layout = "01-02"
			}
			for i := 0; i <= 4; i++ {
				frac := float64(i) / 4.0
				t := first.Add(time.Duration(float64(span) * frac))
				argmaxXTicks = append(argmaxXTicks, xAxisTick{
					X:     int(frac * 100),
					Label: t.In(obsLoc).Format(layout),
				})
			}
		}
	}

	data := floorObservationsData{
		SiteID:               siteID,
		ArgmaxHistory:        argmaxHistory,
		ArgmaxStability:      stabilityPtr,
		ArgmaxNav:            argmaxNav,
		ArgmaxXTicks:         argmaxXTicks,
		Sweep:                sweep,
		CategoryFloors:       categoryFloors,
		FloorRange:           floorRange,
		ActiveCategories:     activeCats,
		HistoricalCategories: historicalCats,
		TrafficShape:         h.fetchTrafficShape(siteID, claims, obsLoc),
	}
	h.render(w, r, "publisher/site-observations.html", pageData{
		Title:             i18n.T(h.lang(r, user), "Floor Decisions · %s", siteID),
		Nav:               "sites",
		User:              user,
		FloorObservations: &data,
	})
}

// fetchTrafficShape pulls the learned weekday/weekend traffic shapes
// from the site stats endpoint and turns them into renderable bars.
// Returns nil (section omitted) when the fetch fails or the tracker
// hasn't reported shapes.
func (h *Handler) fetchTrafficShape(siteID string, claims *model.Claims, loc *time.Location) *trafficShapeView {
	body, err := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/stats", siteID), claims)
	if err != nil {
		return nil
	}
	var resp struct {
		WeekdayShapeVolumes []float64 `json:"weekdayShapeVolumes"`
		WeekendShapeVolumes []float64 `json:"weekendShapeVolumes"`
	}
	if json.Unmarshal(body, &resp) != nil ||
		len(resp.WeekdayShapeVolumes) != 24 || len(resp.WeekendShapeVolumes) != 24 {
		return nil
	}

	globalMax := 0.0
	for _, v := range append(append([]float64{}, resp.WeekdayShapeVolumes...), resp.WeekendShapeVolumes...) {
		if v > globalMax {
			globalMax = v
		}
	}
	if globalMax <= 0 {
		return nil
	}

	// "Still learning": a fresh tracker is exactly uniform (all 1.0); after
	// real learning, buckets diverge. 5% spread on both shapes ≈ untrained.
	flat := func(vs []float64) bool {
		lo, hi := vs[0], vs[0]
		for _, v := range vs {
			if v < lo {
				lo = v
			}
			if v > hi {
				hi = v
			}
		}
		return lo > 0 && hi/lo < 1.05
	}

	// Bars are LEARNED per UTC hour but ROTATED for display into the
	// viewer's zone — the curve is absolute, so relabeling hours is pure
	// rendering (the preference timezone's whole job). Fractional-hour
	// zones round to the nearest hour. The weekday/weekend split itself
	// stays UTC-learned (see docs/design/TIME_AND_TIMEZONES.md, "The
	// third clock") — amber marks today's row by the viewer's calendar.
	now := time.Now().In(loc)
	_, offSec := now.Zone()
	offHours := int(math.Round(float64(offSec)/3600.0)) % 24
	zoneAbbrev := now.Format("MST")
	wd := now.Weekday()
	todayIsWeekend := wd == time.Saturday || wd == time.Sunday
	bars := func(vs []float64, markNow bool) []shapeBar {
		out := make([]shapeBar, 24)
		for local := 0; local < 24; local++ {
			utcHour := ((local-offHours)%24 + 24) % 24
			v := vs[utcHour]
			pct := int(v/globalMax*100 + 0.5)
			if pct < 4 {
				pct = 4 // a zero-height bar reads as missing data
			}
			out[local] = shapeBar{
				Hour:      local,
				HeightPct: pct,
				Title:     fmt.Sprintf("%02d:00 %s · %.2f× average", local, zoneAbbrev, v),
				IsNow:     markNow && local == now.Hour(),
			}
		}
		return out
	}
	return &trafficShapeView{
		Weekday:  bars(resp.WeekdayShapeVolumes, !todayIsWeekend),
		Weekend:  bars(resp.WeekendShapeVolumes, todayIsWeekend),
		Learning: flat(resp.WeekdayShapeVolumes) && flat(resp.WeekendShapeVolumes),
		TzLabel:  zoneAbbrev,
	}
}

// ResetFloorAgent wipes a site's persisted floor-optimizer snapshot so
// the sweep restarts from scratch. Admin escape hatch for when the
// optimizer has drifted to something pathological.
func (h *Handler) ResetFloorAgent(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	h.corePost(fmt.Sprintf("/v1/publishers/me/sites/%s/reset-floor-agent", siteID), claims, "")
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

// UpdateSlotFloorOverride sets or clears the admin per-slot floor.
// Empty floorCpm clears the override; the slot then falls back to the
// RL-learned floor or the slot-quality-prior-scaled site floor.
func (h *Handler) UpdateSlotFloorOverride(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	// Batch save: the form submits one (slotId, floorCpm) pair per slot
	// row as parallel arrays, so all overrides save in a single request.
	// Empty floorCpm clears that slot's override (it then falls back to
	// the RL-learned floor / slot-quality-prior-scaled site floor).
	slotIDs := r.Form["slotId"]
	floors := r.Form["floorCpm"]
	for i, slotID := range slotIDs {
		floorCpm := ""
		if i < len(floors) {
			floorCpm = floors[i]
		}
		body := map[string]any{}
		if floorCpm == "" {
			body["floorCpm"] = nil // clear
		} else {
			body["floorCpm"] = floorCpm
		}
		payload, _ := json.Marshal(body)
		h.corePut(fmt.Sprintf("/v1/publishers/me/sites/%s/slots/%s/floor", siteID, slotID), claims, string(payload))
	}
	// htmx save → no full-page reload (the slots panel stays open). Plain
	// form fallback redirects.
	if r.Header.Get("HX-Request") == "true" {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

func (h *Handler) UpdateBidWeight(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	bidWeight := r.FormValue("bidWeight")
	body, _ := json.Marshal(map[string]any{
		"bidWeight": bidWeight,
	})
	h.corePatch(fmt.Sprintf("/v1/publishers/me/sites/%s", siteID), claims, string(body))
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

// UpdateAcceptsFillerTraffic toggles whether pages with no contextual
// match route to the filler auction for this site. HTML checkboxes
// only submit a value when checked, so absence of the form field
// means "off" — we always PATCH an explicit bool so the server state
// matches the checkbox exactly.
func (h *Handler) UpdateAcceptsFillerTraffic(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	accept := r.FormValue("acceptsFillerTraffic") == "on"
	body, _ := json.Marshal(map[string]any{
		"acceptsFillerTraffic": accept,
	})
	h.corePatch(fmt.Sprintf("/v1/publishers/me/sites/%s", siteID), claims, string(body))
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

func (h *Handler) BlockAdProduct(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	category := r.FormValue("category")
	body, _ := json.Marshal(map[string]any{
		"categories": []string{category},
	})
	h.corePost(fmt.Sprintf("/v1/publishers/me/sites/%s/ad-product-blocklist", siteID), claims, string(body))
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

func (h *Handler) UnblockAdProduct(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	category := r.FormValue("category")
	body, _ := json.Marshal(map[string]any{
		"categories": []string{category},
	})
	h.coreDelete(fmt.Sprintf("/v1/publishers/me/sites/%s/ad-product-blocklist", siteID), claims, string(body))
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

func (h *Handler) BlockSite(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	domain := strings.TrimSpace(r.FormValue("domain"))
	if domain == "" {
		http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
		return
	}
	body, _ := json.Marshal(map[string]any{
		"domains": []string{domain},
	})
	h.corePost("/v1/advertisers/me/blocklist", claims, string(body))
	http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
}

func (h *Handler) UnblockSite(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	domain := strings.TrimSpace(r.FormValue("domain"))
	if domain == "" {
		http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
		return
	}
	body, _ := json.Marshal(map[string]any{
		"domains": []string{domain},
	})
	h.coreDelete("/v1/advertisers/me/blocklist", claims, string(body))
	http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
}

// BlockAdvertiserDomain adds an advertiser landing-page domain to the
// publisher's blocklist (ads pointing there won't serve on this publisher).
func (h *Handler) BlockAdvertiserDomain(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	domain := strings.TrimSpace(r.FormValue("domain"))
	if domain == "" {
		http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
		return
	}
	body, _ := json.Marshal(map[string]any{"domains": []string{domain}})
	h.corePost("/v1/publishers/me/blocklist", claims, string(body))
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

// UnblockAdvertiserDomain removes a domain from the publisher's blocklist.
func (h *Handler) UnblockAdvertiserDomain(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	domain := strings.TrimSpace(r.FormValue("domain"))
	if domain == "" {
		http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
		return
	}
	body, _ := json.Marshal(map[string]any{"domains": []string{domain}})
	h.coreDelete("/v1/publishers/me/blocklist", claims, string(body))
	http.Redirect(w, r, "/publisher/sites", http.StatusSeeOther)
}

func (h *Handler) UpdateCampaignCPM(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	campID := r.FormValue("campaignId")
	maxCpm := r.FormValue("maxCpm")
	body, _ := json.Marshal(map[string]any{
		"bidding": map[string]string{"strategy": "fixed", "maxCpm": maxCpm},
	})
	h.corePatch(fmt.Sprintf("/v1/advertisers/me/campaigns/%s", campID), claims, string(body))
	http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
}

// UpdateCampaignFiller toggles whether this campaign opts in to the
// filler auction (bidding on pages with no contextual match). Default
// off — advertisers must explicitly choose to bid on unmatched
// traffic.
func (h *Handler) UpdateCampaignFiller(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	campID := r.FormValue("campaignId")
	optIn := r.FormValue("bidOnUnmatchedContext") == "on"
	body, _ := json.Marshal(map[string]any{
		"bidOnUnmatchedContext": optIn,
	})
	h.corePatch(fmt.Sprintf("/v1/advertisers/me/campaigns/%s", campID), claims, string(body))
	http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
}

func (h *Handler) ToggleCreativeStatus(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	campID := r.FormValue("campaignId")
	creativeID := r.FormValue("creativeId")
	newStatus := r.FormValue("status")
	body, _ := json.Marshal(map[string]string{"status": newStatus})
	h.corePatch(fmt.Sprintf("/v1/advertisers/me/campaigns/%s/creatives/%s/status", campID, creativeID), claims, string(body))
	http.Redirect(w, r, "/advertiser/creatives?campaignId="+campID, http.StatusSeeOther)
}

func (h *Handler) DeleteCreative(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	campID := r.FormValue("campaignId")
	creativeID := r.FormValue("creativeId")
	h.coreDelete(fmt.Sprintf("/v1/advertisers/me/campaigns/%s/creatives/%s", campID, creativeID), claims, "")
	http.Redirect(w, r, "/advertiser/creatives?campaignId="+campID, http.StatusSeeOther)
}

// DeleteCampaign logically deletes a campaign: the core marks it Deleted
// (which stops it bidding) and soft-deletes its creatives, while tracking
// and report rows are preserved for historical reporting.
func (h *Handler) DeleteCampaign(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	campID := r.FormValue("campaignId")
	h.coreDelete(fmt.Sprintf("/v1/advertisers/me/campaigns/%s", campID), claims, "")
	http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
}

func (h *Handler) ReprocessCreative(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	campID := r.FormValue("campaignId")
	creativeID := r.FormValue("creativeId")
	h.corePost(fmt.Sprintf("/v1/advertisers/me/creatives/%s/reprocess", creativeID), claims, "")
	http.Redirect(w, r, "/advertiser/creatives?campaignId="+campID, http.StatusSeeOther)
}

// --- Advertiser Creatives ---

// ownsCampaign reports whether campID appears in the advertiser's own
// campaign list (fetched from /advertisers/me/campaigns). Used as the
// BFF-side ownership gate before rendering pages that reflect a
// URL-supplied campaignId, so an advertiser can't open another's campaign.
func ownsCampaign(campaigns []campaignData, campID string) bool {
	for _, c := range campaigns {
		if c.ID == campID {
			return true
		}
	}
	return false
}

type creativeData struct {
	ID           string
	Name         string
	Status       string
	ActiveStatus string
	AssetURL     string
	Confidence   string
	PagesJson    string
	Impressions  int64
	Clicks       int64
	CTAClicks    int64
	CTR          string
	// Pin re-engagement counters. Populated from creative_stats'
	// dogeared_* columns. Surface as a separate dimension on the card
	// so dashboards distinguish "fresh CTR" from "bookmark-driven CTR".
	DogearedImpressions int64
	DogearedClicks      int64
	DogearedCTAClicks   int64
	PinCTR              string
	// >0 = images (page image and/or logo) failed to load at the last
	// render and were hidden; the card surfaces a warning so the advertiser
	// can swap the source image.
	BrokenImages int
	// Per-site performance of THIS creative (trailing 30 days, bounded by
	// tracking_events retention) — "which media does this creative earn
	// its keep on". Sorted by spend desc; empty until it has served.
	Media []creativeMediaRow
}

// One site row in a creative's "By media" breakdown.
type creativeMediaRow struct {
	SiteLabel   string // real host, or the site_id slug when unknown
	Impressions int64
	Clicks      int64
	CTAClicks   int64
	CTR         string // "" when no clicks (see the CTR note on creativeData)
	Spend       string // "$X.XX"
}

// One site row of the going-rate view shown beside the Max CPM inputs.
type marketRateRow struct {
	SiteLabel   string
	Impressions int64
	P25         string // "" below the sample floor
	Median      string
	P75         string
	Floor       string // "" when the optimizer has no decision yet
}

// Going rates for the Max CPM decision (trailing window).
type marketRatesData struct {
	Days          int
	OverallMedian string
	OverallP25    string
	OverallP75    string
	Sites         []marketRateRow
	// Clearing-price quantile ladder (5% steps, ascending) as JSON,
	// embedded as a data-attribute on the hint container so every hint
	// (create form, each edit panel) carries ITS OWN ladder for the live
	// reach indicator. "null" when the sample was too small.
	ReachLadderJSON string
	// Lowest current site-wide floor across the scoped market — the
	// cheapest entry ticket. The per-site table was removed on purpose:
	// advertisers buy CONTEXT here, not placements.
	FloorFrom string
	// Human-readable context scope ("Cooking, Sports") — empty = whole
	// network. Context is the unit of value on this network; the hint
	// must always name which market it is quoting.
	ScopeLabel string
	// Inventory honesty for the scoped topics: how many of them publisher
	// inventory actually backs. AvailabilityKnown=false (lookup failed or
	// unscoped view) renders nothing — unknown must not read as "none".
	AvailabilityKnown bool
	LiveTopics        int // topics with cleared impressions in the window
	DeclaredTopics    int // topics only declared by publishers (no trades)
	TotalTopics       int
}

// Chart.js payload for the creative × media stacked-bar chart: labels =
// creative names, one dataset per site (top sites + Other). Pre-marshaled
// JSON as template.JS, the same convention reportSeriesChart uses.
type creativeMediaChart struct {
	Labels template.JS
	Series template.JS
}

func (h *Handler) AdvertiserCreatives(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	campID := r.URL.Query().Get("campaignId")

	// Load campaigns for selector
	var campaigns []campaignData
	campBody, _ := h.coreGet("/v1/advertisers/me/campaigns?limit=50", claims)
	var campResp struct {
		Data []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"data"`
	}
	json.Unmarshal(campBody, &campResp)
	for _, c := range campResp.Data {
		campaigns = append(campaigns, campaignData{ID: c.ID, Name: c.Name})
	}
	if len(campaigns) == 0 {
		// No campaigns ⇒ no creatives possible. Redirect to campaigns
		// (which itself redirects to /account when budget is unset).
		http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
		return
	}
	// IDOR guard: only show creatives for a campaign this advertiser owns
	// (the list above is scoped to /advertisers/me). campID is URL-supplied.
	if campID == "" {
		campID = campaigns[0].ID
	} else if !ownsCampaign(campaigns, campID) {
		http.Redirect(w, r, "/advertiser/creatives", http.StatusSeeOther)
		return
	}

	// Load creatives
	var creatives []creativeData
	if campID != "" {
		crBody, _ := h.coreGet(fmt.Sprintf("/v1/advertisers/me/campaigns/%s/creatives?limit=50", campID), claims)
		var crResp struct {
			Data []struct {
				ID           string `json:"id"`
				Name         string `json:"name"`
				Status       string `json:"status"`
				ActiveStatus string `json:"activeStatus"`
				Asset        *struct {
					Type string `json:"type"`
					URL  string `json:"url"`
				} `json:"asset"`
				MatchConfidence *float64 `json:"matchConfidence"`
				BrokenImages    int      `json:"brokenImages"`
			} `json:"data"`
		}
		json.Unmarshal(crBody, &crResp)

		// Load per-creative stats from dashboard projection
		type creativeStatRow struct {
			Impressions         int64
			Clicks              int64
			CTAClicks           int64
			CTR                 float64
			DogearedImpressions int64
			DogearedClicks      int64
			DogearedCTAClicks   int64
			PinCTR              float64
		}
		crStats := make(map[string]creativeStatRow)
		statsBody, _ := h.coreGet(fmt.Sprintf("/v1/dashboard/advertisers/%s/campaigns/%s/creatives", claims.AdvertiserID, campID), claims)
		var statsResp []struct {
			CreativeID          string  `json:"creativeId"`
			Impressions         int64   `json:"impressions"`
			Clicks              int64   `json:"clicks"`
			CTAClicks           int64   `json:"ctaClicks"`
			CTR                 float64 `json:"ctr"`
			DogearedImpressions int64   `json:"dogearedImpressions"`
			DogearedClicks      int64   `json:"dogearedClicks"`
			DogearedCTAClicks   int64   `json:"dogearedCtaClicks"`
			PinCTR              float64 `json:"pinCtr"`
		}
		if json.Unmarshal(statsBody, &statsResp) == nil {
			for _, s := range statsResp {
				crStats[s.CreativeID] = creativeStatRow{
					Impressions:         s.Impressions,
					Clicks:              s.Clicks,
					CTAClicks:           s.CTAClicks,
					CTR:                 s.CTR,
					DogearedImpressions: s.DogearedImpressions,
					DogearedClicks:      s.DogearedClicks,
					DogearedCTAClicks:   s.DogearedCTAClicks,
					PinCTR:              s.PinCTR,
				}
			}
		}

		for _, c := range crResp.Data {
			cd := creativeData{ID: c.ID, Name: c.Name, Status: c.Status, ActiveStatus: c.ActiveStatus, BrokenImages: c.BrokenImages}
			if c.Asset != nil {
				cd.AssetURL = c.Asset.URL
			}
			if c.MatchConfidence != nil {
				cd.Confidence = fmt.Sprintf("%.0f%%", *c.MatchConfidence*100)
			}
			if st, ok := crStats[c.ID]; ok {
				cd.Impressions = st.Impressions
				cd.Clicks = st.Clicks
				cd.CTAClicks = st.CTAClicks
				// Show CTR only when there's at least one click. "0.0%"
				// after N impressions adds noise without information —
				// the impression + click counts already convey "no clicks".
				if st.Clicks > 0 {
					cd.CTR = fmt.Sprintf("%.1f%%", st.CTR*100)
				}
				cd.DogearedImpressions = st.DogearedImpressions
				cd.DogearedClicks = st.DogearedClicks
				cd.DogearedCTAClicks = st.DogearedCTAClicks
				if st.DogearedClicks > 0 {
					cd.PinCTR = fmt.Sprintf("%.1f%%", st.PinCTR*100)
				}
			}
			creatives = append(creatives, cd)
		}
	}

	// Check if any creative is still rendering (no asset URL yet).
	// Across ALL creatives, before pagination — a pending render on
	// page 2 should still drive the poll loop.
	hasPending := false
	for _, c := range creatives {
		if c.AssetURL == "" {
			hasPending = true
			break
		}
	}

	// Creative × media matrix (trailing 30 days, tracking_events window):
	// which creative earns its keep on which site. Attached BEFORE
	// pagination so every card carries its own breakdown; the stacked-bar
	// chart covers the whole campaign (top sites + Other).
	var mediaChart *creativeMediaChart
	{
		msBody, _ := h.coreGet(fmt.Sprintf("/v1/dashboard/advertisers/%s/campaigns/%s/creative-sites?days=30", claims.AdvertiserID, campID), claims)
		var msResp []struct {
			CreativeID  string  `json:"creativeId"`
			SiteID      string  `json:"siteId"`
			SiteLabel   string  `json:"siteLabel"`
			Impressions int64   `json:"impressions"`
			Clicks      int64   `json:"clicks"`
			CTAClicks   int64   `json:"ctaClicks"`
			Spend       float64 `json:"spend"`
			CTR         float64 `json:"ctr"`
		}
		if json.Unmarshal(msBody, &msResp) == nil && len(msResp) > 0 {
			byCreative := map[string][]creativeMediaRow{}
			siteImps := map[string]int64{}
			cellImps := map[string]map[string]int64{} // creativeID → site label → imps
			for _, m := range msResp {
				label := m.SiteLabel
				if label == "" {
					label = m.SiteID
				}
				row := creativeMediaRow{
					SiteLabel:   label,
					Impressions: m.Impressions,
					Clicks:      m.Clicks,
					CTAClicks:   m.CTAClicks,
					Spend:       fmt.Sprintf("$%.2f", m.Spend),
				}
				// CTR only with at least one click — same rule as the card totals.
				if m.Clicks > 0 {
					row.CTR = fmt.Sprintf("%.1f%%", m.CTR*100)
				}
				byCreative[m.CreativeID] = append(byCreative[m.CreativeID], row)
				siteImps[label] += m.Impressions
				if cellImps[m.CreativeID] == nil {
					cellImps[m.CreativeID] = map[string]int64{}
				}
				cellImps[m.CreativeID][label] += m.Impressions
			}
			for i := range creatives {
				creatives[i].Media = byCreative[creatives[i].ID]
			}
			// Chart: one bar per creative (with data), one dataset per top
			// site; the tail collapses into "Other" so the legend stays legible.
			type chartDataset struct {
				Label string  `json:"label"`
				Data  []int64 `json:"data"`
			}
			var chartIDs, chartLabels []string
			for _, c := range creatives {
				if len(cellImps[c.ID]) == 0 {
					continue
				}
				chartIDs = append(chartIDs, c.ID)
				if c.Name != "" {
					chartLabels = append(chartLabels, c.Name)
				} else {
					chartLabels = append(chartLabels, c.ID)
				}
			}
			topSites := make([]string, 0, len(siteImps))
			for s := range siteImps {
				topSites = append(topSites, s)
			}
			sort.Slice(topSites, func(i, j int) bool { return siteImps[topSites[i]] > siteImps[topSites[j]] })
			const maxSites = 6
			var datasets []chartDataset
			for si, site := range topSites {
				if si >= maxSites {
					break
				}
				ds := chartDataset{Label: site, Data: make([]int64, len(chartIDs))}
				for ci, id := range chartIDs {
					ds.Data[ci] = cellImps[id][site]
				}
				datasets = append(datasets, ds)
			}
			if len(topSites) > maxSites {
				ds := chartDataset{Label: i18n.T(h.lang(r, user), "Other"), Data: make([]int64, len(chartIDs))}
				for _, site := range topSites[maxSites:] {
					for ci, id := range chartIDs {
						ds.Data[ci] += cellImps[id][site]
					}
				}
				datasets = append(datasets, ds)
			}
			if len(chartIDs) > 0 {
				lb, err1 := json.Marshal(chartLabels)
				sb, err2 := json.Marshal(datasets)
				if err1 == nil && err2 == nil {
					mediaChart = &creativeMediaChart{Labels: template.JS(lb), Series: template.JS(sb)}
				}
			}
		}
	}

	sortCreatives(creatives, r.URL.Query().Get("sort"))
	start, end, nav := buildListNav(r, len(creatives), 12)
	creatives = creatives[start:end]

	// Always fetch fresh on navigation — otherwise the browser serves a
	// cached HTML with the old thumbnail after Save Draft → redirect, and
	// the auto-reload poll never fires because the DOM has no placeholder.
	w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
	h.render(w, r, "advertiser/creatives.html", pageData{
		Title:            "Creatives",
		Nav:              "creatives",
		User:             user,
		CampaignID:       campID,
		Campaigns:        campaigns,
		Creatives:        creatives,
		HasPendingRender: hasPending,
		ListNav:          nav,
		BannerScriptURL:  h.bannerScriptURL,
		MediaChart:       mediaChart,
	})
}

// CreativePages returns a creative's expandable-banner payload (pagesJson +
// config + landing URL) for the click-to-preview dialog on the Creatives
// page. The core draft endpoint is keyed by creativeId alone, so ownership
// is enforced here: the campaign must belong to the advertiser and the
// creative must be in that campaign.
func (h *Handler) CreativePages(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	creativeID := r.PathValue("creativeId")
	campID := r.URL.Query().Get("campaignId")

	campBody, _ := h.coreGet("/v1/advertisers/me/campaigns?limit=50", claims)
	var campResp struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	json.Unmarshal(campBody, &campResp)
	owned := false
	for _, c := range campResp.Data {
		if c.ID == campID {
			owned = true
			break
		}
	}
	if !owned {
		http.Error(w, `{"error":"not_found"}`, http.StatusNotFound)
		return
	}
	crBody, _ := h.coreGet(fmt.Sprintf("/v1/advertisers/me/campaigns/%s/creatives?limit=50", campID), claims)
	var crResp struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	json.Unmarshal(crBody, &crResp)
	inCampaign := false
	for _, c := range crResp.Data {
		if c.ID == creativeID {
			inCampaign = true
			break
		}
	}
	if !inCampaign {
		http.Error(w, `{"error":"not_found"}`, http.StatusNotFound)
		return
	}

	draftBody, err := h.coreGet(fmt.Sprintf("/v1/advertisers/me/campaigns/%s/creatives/%s/draft", url.PathEscape(campID), url.PathEscape(creativeID)), claims)
	if err != nil {
		http.Error(w, `{"error":"core_unreachable"}`, http.StatusBadGateway)
		return
	}
	// Re-emit only what the preview needs (lpTextSnapshot can be large).
	var draft struct {
		PagesJSON        *string `json:"pagesJson"`
		BannerConfigJSON *string `json:"bannerConfigJson"`
		LandingURL       string  `json:"landingUrl"`
	}
	if err := json.Unmarshal(draftBody, &draft); err != nil {
		http.Error(w, `{"error":"bad_core_response"}`, http.StatusBadGateway)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"pagesJson":        draft.PagesJSON,
		"bannerConfigJson": draft.BannerConfigJSON,
		"landingUrl":       draft.LandingURL,
	})
}

// --- Advertiser Stats ---

func (h *Handler) AdvertiserStats(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	// Load campaigns with budgets inline
	var campaigns []campaignData
	campBody, _ := h.coreGet("/v1/advertisers/me/campaigns?limit=50", claims)
	var campResp struct {
		Data []struct {
			ID                    string                 `json:"id"`
			Name                  string                 `json:"name"`
			Status                string                 `json:"status"`
			AdProductCategory     string                 `json:"adProductCategory"`
			AdProductCategoryName string                 `json:"adProductCategoryName"`
			Budget                struct{ Daily string } `json:"budget"`
			Bidding               struct {
				MaxCPM string `json:"maxCpm"`
			} `json:"bidding"`
			Spent *string `json:"spent"`
		} `json:"data"`
	}
	json.Unmarshal(campBody, &campResp)

	// Load projection stats
	projStats := make(map[string]struct {
		Impressions int
		Clicks      int
		Spend       float64
		CTR         float64
	})
	if claims != nil {
		projBody, _ := h.coreGet(fmt.Sprintf("/v1/dashboard/advertisers/%s/campaigns", claims.AdvertiserID), claims)
		var projResp []struct {
			CampaignID  string  `json:"campaignId"`
			Impressions int     `json:"impressions"`
			Clicks      int     `json:"clicks"`
			TotalSpend  float64 `json:"totalSpend"`
			CTR         float64 `json:"ctr"`
		}
		if json.Unmarshal(projBody, &projResp) == nil {
			for _, p := range projResp {
				projStats[p.CampaignID] = struct {
					Impressions int
					Clicks      int
					Spend       float64
					CTR         float64
				}{p.Impressions, p.Clicks, p.TotalSpend, p.CTR}
			}
		}
	}

	// Per-campaign spend today from tracking_events — same source as the
	// campaigns page so the two screens agree.
	campSpendToday := make(map[string]string)
	campSpendOK := false
	csBody, _ := h.coreGet("/v1/advertisers/me/campaign-spend-today", claims)
	var csResp struct {
		Campaigns []struct {
			CampaignID string `json:"campaignId"`
			Spend      string `json:"spend"`
		} `json:"campaigns"`
	}
	if json.Unmarshal(csBody, &csResp) == nil {
		campSpendOK = true
		for _, cs := range csResp.Campaigns {
			campSpendToday[cs.CampaignID] = cs.Spend
		}
	}

	for _, c := range campResp.Data {
		cd := campaignData{ID: c.ID, Name: c.Name, Status: c.Status, AdProductCategory: c.AdProductCategory, AdProductCategoryName: c.AdProductCategoryName, DailyBudget: money(c.Budget.Daily), MaxCPM: money(c.Bidding.MaxCPM)}
		if campSpendOK {
			if s, ok := campSpendToday[c.ID]; ok {
				cd.SpendToday = money(s)
			} else {
				cd.SpendToday = "0.00"
			}
		} else if c.Spent != nil {
			cd.SpendToday = money(*c.Spent)
		}
		// Budget bar (was never assigned — rendered 0% regardless of spend).
		if spend, err := strconv.ParseFloat(cd.SpendToday, 64); err == nil {
			if budget, err := strconv.ParseFloat(cd.DailyBudget, 64); err == nil && budget > 0 {
				cd.BudgetPct = math.Round(math.Min(100, spend/budget*100)*10) / 10
			}
		}
		if ps, ok := projStats[c.ID]; ok {
			cd.Impressions = ps.Impressions
			cd.Clicks = ps.Clicks
			if ps.Impressions > 0 {
				cd.CTR = fmt.Sprintf("%.1f%%", ps.CTR*100)
				cd.ECPM = fmt.Sprintf("$%.2f", ps.Spend/float64(ps.Impressions)*1000)
			}
			if ps.Spend > 0 {
				cd.LifetimeSpend = fmt.Sprintf("$%.2f", ps.Spend)
			}
		}
		campaigns = append(campaigns, cd)
	}

	// Hourly delivery chart: today's impressions per UTC hour. All 24
	// buckets are materialized (the endpoint omits empty hours) so the
	// chart's x-axis is a stable day, not just the active hours.
	var hourly []hourlyBar
	var hourlyTotal int64
	hBody, _ := h.coreGet("/v1/advertisers/me/hourly-today", claims)
	var hResp struct {
		Hours []struct {
			Hour        int    `json:"hour"`
			Impressions int64  `json:"impressions"`
			Spend       string `json:"spend"`
		} `json:"hours"`
	}
	if json.Unmarshal(hBody, &hResp) == nil {
		byHour := make(map[int]struct {
			Imps  int64
			Spend string
		}, len(hResp.Hours))
		var maxImps int64
		for _, hr := range hResp.Hours {
			byHour[hr.Hour] = struct {
				Imps  int64
				Spend string
			}{hr.Impressions, hr.Spend}
			hourlyTotal += hr.Impressions
			if hr.Impressions > maxImps {
				maxImps = hr.Impressions
			}
		}
		for hr := 0; hr < 24; hr++ {
			b := hourlyBar{Hour: hr, Spend: "$0.00"}
			if v, ok := byHour[hr]; ok {
				b.Imps = v.Imps
				if sv, err := strconv.ParseFloat(v.Spend, 64); err == nil {
					b.Spend = fmt.Sprintf("$%.2f", sv)
				}
				if maxImps > 0 {
					b.HeightPct = int(float64(v.Imps) / float64(maxImps) * 100)
					if b.HeightPct < 3 && v.Imps > 0 {
						b.HeightPct = 3 // an hour with traffic should be visible
					}
				}
			}
			hourly = append(hourly, b)
		}
	}

	// Sort + paginate the cards. (The 5s auto-refresh and its HX-Request
	// partial path are gone — hourly/daily numbers don't move fast
	// enough to earn a polling loop; reload when you want fresh data.)
	sortCampaigns(campaigns, r.URL.Query().Get("sort"))
	start, end, nav := buildListNav(r, len(campaigns), 8)
	campaigns = campaigns[start:end]

	h.render(w, r, "advertiser/stats.html", pageData{
		Title:          "Stats",
		Nav:            "stats",
		User:           user,
		Campaigns:      campaigns,
		AdvHourly:      hourly,
		AdvHourlyTotal: hourlyTotal,
		ListNav:        nav,
		WalletNotice:   h.walletNotice(r.Context(), claims.AdvertiserID),
	})
}

// hourlyBar is one UTC hour of today's delivery for the stats page's
// chart. HeightPct is precomputed (0-100, fraction of the busiest hour).
type hourlyBar struct {
	Hour      int
	Imps      int64
	Spend     string // "$X.XX"
	HeightPct int
}

// --- Helpers ---

// listNav carries sort + pagination state for a list page. Prev/Next
// URLs are precomputed (html/template has no arithmetic) and preserve
// every other query param (campaignId, sort, …).
type listNav struct {
	Sort       string
	Page       int // 1-based
	TotalPages int
	Total      int
	From, To   int // 1-based display range ("Showing From–To of Total")
	PrevURL    string
	NextURL    string
}

// buildListNav reads ?sort and ?page off the request and returns the
// slice bounds for the current page plus the nav state for the footer.
func buildListNav(r *http.Request, total, pageSize int) (start, end int, nav *listNav) {
	return buildListNavParam(r, total, pageSize, "page")
}

// buildListNavParam is buildListNav with a custom page-param name, for pages
// that paginate TWO independent tables (e.g. /admin/users: users on ?page,
// organizations on ?orgPage) without each nav resetting the other.
func buildListNavParam(r *http.Request, total, pageSize int, pageParam string) (start, end int, nav *listNav) {
	sortKey := r.URL.Query().Get("sort")
	page, _ := strconv.Atoi(r.URL.Query().Get(pageParam))
	if page < 1 {
		page = 1
	}
	totalPages := (total + pageSize - 1) / pageSize
	if totalPages < 1 {
		totalPages = 1
	}
	if page > totalPages {
		page = totalPages
	}
	start = (page - 1) * pageSize
	end = start + pageSize
	if end > total {
		end = total
	}
	mk := func(p int) string {
		q := r.URL.Query()
		q.Set(pageParam, strconv.Itoa(p))
		return r.URL.Path + "?" + q.Encode()
	}
	nav = &listNav{Sort: sortKey, Page: page, TotalPages: totalPages, Total: total, From: start + 1, To: end}
	if total == 0 {
		nav.From = 0
	}
	if page > 1 {
		nav.PrevURL = mk(page - 1)
	}
	if page < totalPages {
		nav.NextURL = mk(page + 1)
	}
	return
}

// sortCampaigns orders the campaigns list by the pulldown's key.
// "newest" relies on ULIDs sorting lexicographically by creation time.
func sortCampaigns(cs []campaignData, key string) {
	money := func(s string) float64 { v, _ := strconv.ParseFloat(s, 64); return v }
	switch key {
	case "name":
		sort.SliceStable(cs, func(i, j int) bool {
			return strings.ToLower(cs[i].Name) < strings.ToLower(cs[j].Name)
		})
	case "spend":
		sort.SliceStable(cs, func(i, j int) bool { return money(cs[i].SpendToday) > money(cs[j].SpendToday) })
	case "impressions":
		sort.SliceStable(cs, func(i, j int) bool { return cs[i].Impressions > cs[j].Impressions })
	default: // newest
		sort.SliceStable(cs, func(i, j int) bool { return cs[i].ID > cs[j].ID })
	}
}

// sortCreatives orders the creatives grid by the pulldown's key.
func sortCreatives(cs []creativeData, key string) {
	ctr := func(c creativeData) float64 {
		if c.Impressions == 0 {
			return 0
		}
		return float64(c.Clicks) / float64(c.Impressions)
	}
	switch key {
	case "name":
		sort.SliceStable(cs, func(i, j int) bool {
			return strings.ToLower(cs[i].Name) < strings.ToLower(cs[j].Name)
		})
	case "impressions":
		sort.SliceStable(cs, func(i, j int) bool { return cs[i].Impressions > cs[j].Impressions })
	case "ctr":
		sort.SliceStable(cs, func(i, j int) bool { return ctr(cs[i]) > ctr(cs[j]) })
	default: // newest
		sort.SliceStable(cs, func(i, j int) bool { return cs[i].ID > cs[j].ID })
	}
}

// money renders a currency amount at a single, consistent 2 decimal places.
// The core API returns amounts as 4-decimal strings (e.g. "5.5000"); this
// normalises every displayed money value (CPM, budget, spend, remaining, floor)
// so the dashboard doesn't mix "$5.5000" and "$6.00". Non-numeric input is
// returned unchanged (e.g. an em-dash placeholder). The "$" is added by the
// template, not here.
func money(s string) string {
	if v, err := strconv.ParseFloat(s, 64); err == nil {
		return fmt.Sprintf("%.2f", v)
	}
	return s
}

// Humanized "how long ago" for queue-age display: "5m", "3h", "2d 5h".
// Sub-minute ages round up to "1m" so a fresh item never reads as "0".
func humanizeSince(t time.Time) string {
	d := time.Since(t)
	switch {
	case d < time.Minute:
		return "1m"
	case d < time.Hour:
		return fmt.Sprintf("%dm", int(d.Minutes()))
	case d < 24*time.Hour:
		return fmt.Sprintf("%dh", int(d.Hours()))
	default:
		days := int(d.Hours()) / 24
		hours := int(d.Hours()) % 24
		if hours == 0 {
			return fmt.Sprintf("%dd", days)
		}
		return fmt.Sprintf("%dd %dh", days, hours)
	}
}

func sanitizeID(s string) string {
	var result []byte
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') {
			result = append(result, c)
		} else if c >= 'A' && c <= 'Z' {
			result = append(result, c+32)
		} else if len(result) > 0 && result[len(result)-1] != '-' {
			result = append(result, '-')
		}
	}
	// Trim trailing dash
	if len(result) > 0 && result[len(result)-1] == '-' {
		result = result[:len(result)-1]
	}
	return string(result)
}

// --- Page Image Upload ---

// UploadPageImage handles per-page image replacement in the creative editor.
// Accepts multipart file upload, stores via core API, returns JSON {url: "cdn-url"}.
func (h *Handler) UploadPageImage(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	r.ParseMultipartForm(10 << 20) // 10MB max
	file, header, err := r.FormFile("file")
	if err != nil {
		http.Error(w, `{"error":"no file"}`, http.StatusBadRequest)
		return
	}
	defer file.Close()

	fileBytes, err := io.ReadAll(file)
	if err != nil {
		http.Error(w, `{"error":"read failed"}`, http.StatusInternalServerError)
		return
	}

	// Detect dimensions
	imgCfg, _, err := image.DecodeConfig(bytes.NewReader(fileBytes))
	width, height := 0, 0
	if err == nil {
		width = imgCfg.Width
		height = imgCfg.Height
	}

	// Detect MIME
	mime := header.Header.Get("Content-Type")
	if mime == "" {
		mime = "image/png"
	}

	// Base64 encode and POST to core /v1/images
	b64 := base64.StdEncoding.EncodeToString(fileBytes)
	reqBody, _ := json.Marshal(map[string]any{
		"imageBase64": b64,
		"mimeType":    mime,
		"width":       width,
		"height":      height,
	})

	body, err := h.corePost("/v1/images", claims, string(reqBody))
	if err != nil {
		http.Error(w, `{"error":"upload failed"}`, http.StatusInternalServerError)
		return
	}

	// Parse response to extract cdnUrl
	var resp struct {
		CdnUrl string `json:"cdnUrl"`
	}
	json.Unmarshal(body, &resp)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"url": resp.CdnUrl})
}

// --- LP Analysis ---

// AnalyzeLP extracts structured sections from a landing page via Playwright
func (h *Handler) AnalyzeLP(w http.ResponseWriter, r *http.Request) {
	slog.Info("AnalyzeLP: handler entered", "method", r.Method, "contentType", r.Header.Get("Content-Type"))
	_, claims := h.sessionUser(r)
	if claims == nil {
		slog.Warn("AnalyzeLP: unauthorized")
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	r.ParseForm()
	lpUrl := r.FormValue("url")
	slog.Info("AnalyzeLP: parsed form", "url", lpUrl)
	if lpUrl == "" {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{"error": "url required"})
		return
	}

	strategy := r.FormValue("strategy")
	if strategy == "" {
		strategy = "heading"
	}

	slog.Info("AnalyzeLP: requesting", "url", lpUrl, "strategy", strategy)
	reqBody, _ := json.Marshal(map[string]any{"url": lpUrl, "strategy": strategy})
	body, err := h.corePost("/v1/creatives/analyze-lp", claims, string(reqBody))
	if err != nil {
		slog.Error("AnalyzeLP: corePost failed", "url", lpUrl, "err", err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]string{"error": "analysis failed: " + err.Error()})
		return
	}
	slog.Info("AnalyzeLP: response", "url", lpUrl, "len", len(body), "valid_json", json.Valid(body))
	if !json.Valid(body) {
		slog.Error("AnalyzeLP: core API returned non-JSON", "url", lpUrl, "body", string(body))
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": string(body)})
		return
	}
	var errCheck struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	}
	if json.Unmarshal(body, &errCheck) == nil && errCheck.Code != "" {
		slog.Error("AnalyzeLP: core API error", "url", lpUrl, "code", errCheck.Code, "message", errCheck.Message)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": errCheck.Message})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// AnalyzeLPStart kicks off async LP analysis and returns a jobId immediately
// (the client then polls AnalyzeLPStatus). Avoids the long synchronous request
// that blocked the Designer transition.
func (h *Handler) AnalyzeLPStart(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	r.ParseForm()
	lpUrl := r.FormValue("url")
	if lpUrl == "" {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{"error": "url required"})
		return
	}
	strategy := r.FormValue("strategy")
	if strategy == "" {
		strategy = "heading"
	}
	payload := map[string]any{"url": lpUrl, "strategy": strategy}
	// Manual Analyze clicks bypass the core's (url, strategy) result
	// cache; the editor's auto-start on open stays cached.
	if r.FormValue("force") == "1" {
		payload["force"] = true
	}
	reqBody, _ := json.Marshal(payload)
	body, err := h.corePost("/v1/creatives/analyze-lp/start", claims, string(reqBody))
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]string{"error": "start failed: " + err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// AnalyzeLPStatus polls an async LP-analysis job by id.
func (h *Handler) AnalyzeLPStatus(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	jobID := r.URL.Query().Get("jobId")
	if jobID == "" {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{"error": "jobId required"})
		return
	}
	body, err := h.coreGet("/v1/creatives/analyze-lp/status/"+jobID, claims)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// AnalyzeLPScreenshot streams the early viewport PNG captured for an analyze
// job (shown as the loading background in the creative editor). Proxies the
// core endpoint and propagates its status + content-type.
func (h *Handler) AnalyzeLPScreenshot(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	jobID := r.URL.Query().Get("jobId")
	if jobID == "" {
		http.Error(w, `{"error":"jobId required"}`, http.StatusBadRequest)
		return
	}
	resp, err := h.coreGetRaw("/v1/creatives/analyze-lp/screenshot/"+jobID, claims)
	if err != nil {
		http.Error(w, `{"error":"`+err.Error()+`"}`, http.StatusInternalServerError)
		return
	}
	defer resp.Body.Close()
	if ct := resp.Header.Get("Content-Type"); ct != "" {
		w.Header().Set("Content-Type", ct)
	}
	// Short cache: the bytes for a jobId never change, but jobs are
	// short-lived; keep it private and modest.
	w.Header().Set("Cache-Control", "private, max-age=60")
	w.WriteHeader(resp.StatusCode)
	io.Copy(w, resp.Body)
}

// RewriteSections sends selected sections to Gemini for copy generation
func (h *Handler) RewriteSections(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}

	// Read JSON body from Alpine.js fetch
	bodyBytes, err := io.ReadAll(r.Body)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{"error": "bad request"})
		return
	}

	body, err := h.corePost("/v1/creatives/rewrite-sections", claims, string(bodyBytes))
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]string{"error": "rewrite failed: " + err.Error()})
		return
	}
	if !json.Valid(body) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": string(body)})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// --- Creative Editor ---

func (h *Handler) CreativeEditor(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	campID := r.URL.Query().Get("campaignId")

	// Load campaigns for selector
	var campaigns []campaignData
	campBody, _ := h.coreGet("/v1/advertisers/me/campaigns?limit=50", claims)
	var campResp struct {
		Data []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"data"`
	}
	json.Unmarshal(campBody, &campResp)
	for _, c := range campResp.Data {
		campaigns = append(campaigns, campaignData{ID: c.ID, Name: c.Name})
	}
	if len(campaigns) == 0 {
		http.Redirect(w, r, "/advertiser/campaigns", http.StatusSeeOther)
		return
	}
	// IDOR guard: campID comes straight from the URL and is reflected into
	// the page (the designer's brandKit storage key + landing URL). Only
	// render the editor for a campaign this advertiser actually owns — the
	// campaigns list above is already scoped to /advertisers/me. A foreign
	// or unknown id is sent back to the creatives list rather than opened.
	if campID == "" {
		campID = campaigns[0].ID
	} else if !ownsCampaign(campaigns, campID) {
		http.Redirect(w, r, "/advertiser/creatives", http.StatusSeeOther)
		return
	}

	// Fetch campaign's landing URL
	var landingUrl string
	if campID != "" {
		detailBody, _ := h.coreGet(fmt.Sprintf("/v1/advertisers/me/campaigns/%s", campID), claims)
		var detail struct {
			LandingUrl string `json:"landingUrl"`
		}
		json.Unmarshal(detailBody, &detail)
		landingUrl = detail.LandingUrl
	}

	h.render(w, r, "advertiser/creative-editor.html", pageData{
		Title:      "Creative Editor",
		Nav:        "creatives",
		User:       user,
		CampaignID: campID,
		Campaigns:  campaigns,
		LandingURL: landingUrl,
	})
}

// ListAdvertiserAssets proxies GET /v1/advertisers/{me}/assets.
func (h *Handler) ListAdvertiserAssets(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	body, err := h.coreGet("/v1/advertisers/me/assets", claims)
	if err != nil {
		slog.Error("ListAdvertiserAssets: core call failed", "err", err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// ImportAdvertiserAssetUrls proxies POST /v1/advertisers/me/assets/import-urls.
// Body is forwarded as-is; expected shape: {"urls":[{"url":"...","filename":"..."}]}
func (h *Handler) ImportAdvertiserAssetUrls(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	defer r.Body.Close()
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, `{"error":"read body failed"}`, http.StatusBadRequest)
		return
	}
	resp, err := h.corePost("/v1/advertisers/me/assets/import-urls", claims, string(body))
	if err != nil {
		slog.Error("ImportAdvertiserAssetUrls: core call failed", "err", err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(resp)
}

// PresignAdvertiserAsset proxies POST /v1/advertisers/me/assets/presigned-upload.
// Browser hashes the file bytes, requests a presigned PUT URL, then PUTs
// directly to R2 (bytes skip the dashboard entirely). Body shape:
// {"filename":"...","mimeType":"...","hash":"<sha256-hex>","sizeBytes":N}.
func (h *Handler) PresignAdvertiserAsset(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	defer r.Body.Close()
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, `{"error":"read body failed"}`, http.StatusBadRequest)
		return
	}
	resp, err := h.corePost("/v1/advertisers/me/assets/presigned-upload", claims, string(body))
	if err != nil {
		slog.Error("PresignAdvertiserAsset: core call failed", "err", err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(resp)
}

// RegisterAdvertiserAsset proxies POST /v1/advertisers/me/assets/register.
// Called by the browser after a successful PUT to the presigned URL —
// inserts the image_asset (if hash is new) + advertiser_asset rows.
// Body shape: {"filename":"...","mimeType":"...","hash":"...","width":N,"height":N}.
func (h *Handler) RegisterAdvertiserAsset(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	defer r.Body.Close()
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, `{"error":"read body failed"}`, http.StatusBadRequest)
		return
	}
	resp, err := h.corePost("/v1/advertisers/me/assets/register", claims, string(body))
	if err != nil {
		slog.Error("RegisterAdvertiserAsset: core call failed", "err", err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(resp)
}

// ProxyAsset re-serves an asset from the R2 CDN through the dashboard
// origin so canvas-reading code paths (auto-crop saliency, manual crop
// modal) can read pixels without hitting CORS preflight or stale-cache
// issues on R2's pub.r2.dev URLs.
//
// Browser → GET /proxy/asset/{hash}.{ext}
//
//	→ server fetches https://{CDN_BASE_URL}/assets/{hash}.{ext}
//	→ returns bytes with `Access-Control-Allow-Origin: *`
//
// Bytes flow through Go for this request — but it only fires from
// authoring code paths (designer auto-crop, crop-modal). Production
// ad-unit displays use plain <img src=R2-CDN> and skip this entirely.
//
// No auth on this route: assets in R2 are already publicly readable
// (the bucket is configured for public access). The proxy is just a
// CORS-header-adding pass-through. Adding auth would just slow down
// the dashboard without any new privacy property.
func (h *Handler) ProxyAsset(w http.ResponseWriter, r *http.Request) {
	cdnBase := os.Getenv("CDN_BASE_URL")
	if cdnBase == "" {
		http.Error(w, "CDN_BASE_URL not set", http.StatusServiceUnavailable)
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/proxy/asset/")
	if path == "" || strings.Contains(path, "..") {
		http.Error(w, "bad path", http.StatusBadRequest)
		return
	}
	upstream := strings.TrimRight(cdnBase, "/") + "/assets/" + path

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, upstream, nil)
	if err != nil {
		http.Error(w, "build request failed", http.StatusInternalServerError)
		return
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		slog.Error("ProxyAsset: upstream fetch failed", "err", err, "url", upstream)
		http.Error(w, "upstream fetch failed", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		http.Error(w, "upstream "+resp.Status, resp.StatusCode)
		return
	}

	// Mirror the upstream content-type and length; add CORS so the
	// browser's <img crossOrigin="anonymous"> can read pixels.
	if ct := resp.Header.Get("Content-Type"); ct != "" {
		w.Header().Set("Content-Type", ct)
	}
	if cl := resp.Header.Get("Content-Length"); cl != "" {
		w.Header().Set("Content-Length", cl)
	}
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Cache-Control", "public, max-age=300")
	io.Copy(w, resp.Body)
}

// DeleteAdvertiserAsset proxies DELETE /v1/advertisers/me/assets/{id}.
func (h *Handler) DeleteAdvertiserAsset(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	id := r.PathValue("id")
	_, err := h.coreDelete("/v1/advertisers/me/assets/"+url.PathEscape(id), claims, "")
	if err != nil {
		slog.Error("DeleteAdvertiserAsset: core call failed", "err", err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// LayoutTemplates proxies to core /v1/layout-templates. The LP-to-
// Creative editor's first step renders a layout-template picker from
// the response so the catalog stays single-sourced (server-side) and
// the picker can never list a template the auto-layout wouldn't
// recognise.
func (h *Handler) LayoutTemplates(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	resp, err := h.coreGet("/v1/layout-templates", claims)
	if err != nil {
		slog.Error("LayoutTemplates: core call failed", "err", err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(resp)
}

// GenerateLayout proxies to core /v1/creatives/generate-layout. The body is
// forwarded as-is — the template JS controls the payload shape.
func (h *Handler) GenerateLayout(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	defer r.Body.Close()
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, `{"error":"read body failed"}`, http.StatusBadRequest)
		return
	}
	resp, err := h.corePost("/v1/creatives/generate-layout", claims, string(body))
	if err != nil {
		slog.Error("GenerateLayout: core call failed", "err", err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(resp)
}

// RewriteCopy proxies to core /v1/creatives/rewrite-copy. Gemini
// returns alternative phrasings for the page's tag/headline/sub/body.
// Body shape: { page, lpContext } — see RewriteCopyRequest in core.
func (h *Handler) RewriteCopy(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	defer r.Body.Close()
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, `{"error":"read body failed"}`, http.StatusBadRequest)
		return
	}
	resp, err := h.corePost("/v1/creatives/rewrite-copy", claims, string(body))
	if err != nil {
		slog.Error("RewriteCopy: core call failed", "err", err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(resp)
}

// GenerateLayoutPair proxies to core /v1/creatives/generate-layout-pair.
// Gemini returns both PC (16:9) and Mobile (9:16) variants in one call
// so they read as a responsive pair.
func (h *Handler) GenerateLayoutPair(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	defer r.Body.Close()
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, `{"error":"read body failed"}`, http.StatusBadRequest)
		return
	}
	resp, err := h.corePost("/v1/creatives/generate-layout-pair", claims, string(body))
	if err != nil {
		slog.Error("GenerateLayoutPair: core call failed", "err", err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(resp)
}

// importPageImagesToAdvertiserLibrary extracts image URLs from a pagesJson
// blob and POSTs them to the core import-urls endpoint so they land in the
// advertiser's owned asset library AND so the original external URLs in
// pagesJSON get rewritten to their R2 cdnUrl equivalents.
//
// The rewrite step matters because the designer's crop-modal saliency
// (and other features) need same-origin / CORS-friendly URLs to read
// pixels via canvas. External URLs (LP-extracted from the customer's
// CDN) are blocked by the browser's tainted-canvas check. By the time
// the designer renders, every page.img should be a pub-*.r2.dev URL.
//
// Returns the (possibly-rewritten) pagesJSON. On any failure the
// original pagesJSON comes back unchanged — the design page still
// works, saliency just gracefully degrades on the leftover external
// URLs (per crop-modal's two-pass loader, commit dbc4b5b4).
//
// Safe to call with empty/invalid input.
func importPageImagesToAdvertiserLibrary(h *Handler, claims *model.Claims, pagesJSON string) string {
	if !json.Valid([]byte(pagesJSON)) {
		return pagesJSON
	}
	// We re-decode the pages as raw maps later for the rewrite step;
	// here we use a typed decode just to enumerate img URLs cheaply.
	var pages []struct {
		Img      string `json:"img"`
		Tag      string `json:"tag"`
		Headline string `json:"headline"`
	}
	if json.Unmarshal([]byte(pagesJSON), &pages) != nil {
		return pagesJSON
	}
	type urlItem struct {
		URL      string `json:"url"`
		Filename string `json:"filename,omitempty"`
	}
	seen := map[string]bool{}
	var urls []urlItem
	for _, p := range pages {
		u := p.Img
		if u == "" || !strings.HasPrefix(u, "http") || seen[u] {
			continue
		}
		seen[u] = true
		name := p.Headline
		if name == "" {
			name = p.Tag
		}
		urls = append(urls, urlItem{URL: u, Filename: name})
	}
	if len(urls) == 0 {
		slog.Info("auto-import: no image URLs found in pagesJSON")
		return pagesJSON
	}
	body, _ := json.Marshal(map[string]any{"urls": urls})
	slog.Info("auto-import: requesting", "count", len(urls), "payload", truncStr(string(body), 300))
	// Synchronous: the design page expects these images to be in the
	// advertiser's library AND have their R2 URLs available for rewrite
	// by the time it renders. Each URL is a network fetch on the core
	// side, so this adds ~1-2s per image.
	resp, err := h.corePost("/v1/advertisers/me/assets/import-urls", claims, string(body))
	if err != nil {
		slog.Warn("auto-import page images failed", "err", err)
		return pagesJSON
	}
	slog.Info("auto-import: response", "body", truncStr(string(resp), 500))

	// Build sourceUrl → cdnUrl map from the response so we can rewrite
	// page.img references in pagesJSON. Failures (asset == null) leave
	// the original URL in place.
	var importResp struct {
		Results []struct {
			SourceURL string `json:"sourceUrl"`
			Asset     *struct {
				CDNUrl string `json:"cdnUrl"`
			} `json:"asset"`
		} `json:"results"`
	}
	if err := json.Unmarshal(resp, &importResp); err != nil {
		slog.Warn("auto-import: failed to parse response", "err", err)
		return pagesJSON
	}
	rewriteMap := make(map[string]string, len(importResp.Results))
	for _, r := range importResp.Results {
		if r.Asset != nil && r.Asset.CDNUrl != "" {
			rewriteMap[r.SourceURL] = r.Asset.CDNUrl
		}
	}
	if len(rewriteMap) == 0 {
		return pagesJSON
	}

	// Rewrite as raw map[string]any so we don't drop fields we don't
	// know about. Only touches the top-level `img` key on each page;
	// layout-item image src URLs are derived from page.img by the
	// designer's auto-layout step (which runs after this rewrite).
	var rawPages []map[string]any
	if err := json.Unmarshal([]byte(pagesJSON), &rawPages); err != nil {
		return pagesJSON
	}
	rewriteCount := 0
	for _, p := range rawPages {
		if rawImg, ok := p["img"].(string); ok {
			if newURL, found := rewriteMap[rawImg]; found {
				p["img"] = newURL
				rewriteCount++
			}
		}
	}
	if rewriteCount == 0 {
		return pagesJSON
	}
	rewritten, err := json.Marshal(rawPages)
	if err != nil {
		slog.Warn("auto-import: failed to re-marshal pagesJSON", "err", err)
		return pagesJSON
	}
	slog.Info("auto-import: rewrote image URLs", "count", rewriteCount, "of", len(rewriteMap))
	return string(rewritten)
}

func truncStr(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

// CreativeDesign renders the isolated Fabric.js design page. Accepts the composed
// pages + banner context as POST form data from the compose step, then hands off
// to a standalone template (no layout wrapper, no Tailwind) to avoid CSS conflicts.
func (h *Handler) CreativeDesign(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil || claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()

	campID := r.FormValue("campaignId")
	pagesJSON := r.FormValue("pagesJson")
	landingURL := r.FormValue("landingUrl")
	creativeName := r.FormValue("creativeName")
	lpTextSnapshot := r.FormValue("lpTextSnapshot")
	// Set when reopening a draft so subsequent Save Draft / Publish
	// clicks overwrite the same row. Empty for a first-time author.
	creativeID := r.FormValue("creativeId")
	bannerSize := r.FormValue("bannerSize")
	if bannerSize == "" {
		bannerSize = "300x250"
	}
	// Pre-generation pickers from the LP-to-Creative first step.
	// brandKitJson is a JSON object {name, colors[], fonts[]} or empty;
	// templateId is one of the catalog ids served by /v1/layout-templates
	// or empty. The designer threads both through to its auto-layout
	// call so the Gemini prompt incorporates them.
	brandKitJSON := r.FormValue("brandKitJson")
	templateID := r.FormValue("templateId")
	// LP-original fonts the advertiser opted in to re-hosting (the wizard
	// only fills this field when the license checkbox is ticked). Validate
	// it's a JSON array before threading it through to the designer.
	lpFontsJSON := r.FormValue("lpFontsJson")
	if lpFontsJSON != "" {
		var lpFonts []struct {
			Family string `json:"family"`
			Weight int    `json:"weight"`
			Hash   string `json:"hash"`
		}
		if json.Unmarshal([]byte(lpFontsJSON), &lpFonts) != nil || len(lpFonts) == 0 {
			lpFontsJSON = ""
		} else {
			families := make([]string, 0, len(lpFonts))
			for _, f := range lpFonts {
				families = append(families, f.Family)
			}
			slog.Info("CreativeDesign: LP-original font re-hosting opted in",
				"advertiser", claims.AdvertiserID, "campID", campID, "families", strings.Join(families, ","))
		}
	}
	slog.Info("CreativeDesign POST",
		"campID", campID, "pagesJSON.len", len(pagesJSON),
		"creativeName", creativeName, "bannerSize", bannerSize,
		"lpTextSnapshot.len", len(lpTextSnapshot),
		"creativeId", creativeID,
		"pagesJSON.preview", truncStr(pagesJSON, 200),
	)
	// Auto-import any external image URLs from the compose step into the
	// advertiser's asset library AND rewrite each page.img to its R2
	// cdnUrl so the designer never receives third-party URLs. Returns
	// the (possibly-rewritten) pagesJSON; failures leave original URLs
	// in place.
	pagesJSON = importPageImagesToAdvertiserLibrary(h, claims, pagesJSON)
	// No pages = no design work. Redirect back to compose instead of
	// rendering an empty canvas.
	var pages []any
	if !json.Valid([]byte(pagesJSON)) || json.Unmarshal([]byte(pagesJSON), &pages) != nil || len(pages) == 0 {
		slog.Warn("CreativeDesign: empty/invalid pages, redirecting",
			"valid", json.Valid([]byte(pagesJSON)),
			"len", len(pages),
		)
		redirect := "/advertiser/creatives/editor"
		if campID != "" {
			redirect += "?campaignId=" + url.QueryEscape(campID)
		}
		http.Redirect(w, r, redirect, http.StatusSeeOther)
		return
	}

	data := creativeDesignData{
		CampaignID:      campID,
		LandingURL:      landingURL,
		CreativeName:    creativeName,
		BannerSize:      bannerSize,
		PagesJSON:       pagesJSON,
		LPTextSnapshot:  lpTextSnapshot,
		CreativeID:      creativeID,
		BannerScriptURL: h.bannerScriptURL,
		BrandKitJSON:    brandKitJSON,
		TemplateID:      templateID,
		LPFontsJSON:     lpFontsJSON,
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
	t := getPageStandalone(h.lang(r, user), "advertiser/creative-design.html")
	if err := t.ExecuteTemplate(w, "creative-design.html", data); err != nil {
		slog.Error("creative-design render failed", "error", err)
		http.Error(w, "render error: "+err.Error(), http.StatusInternalServerError)
	}
}

// creativeDesignData is the template payload for creative-design.html —
// shared by CreativeDesign (fresh design) and ResumeDraft. ONE type on
// purpose: the template dies mid-stream (blank page) on any field it
// references that the payload lacks, and the two hand-copied anonymous
// structs drifted exactly that way once (LPFontsJSON added for the LP-font
// license flow reached only the design path; resuming any draft went
// blank). A shared type makes template-field additions a compile-visible,
// single-site change.
type creativeDesignData struct {
	CampaignID      string
	LandingURL      string
	CreativeName    string
	BannerSize      string
	PagesJSON       string
	LPTextSnapshot  string
	CreativeID      string
	BannerScriptURL string
	BrandKitJSON    string
	TemplateID      string
	LPFontsJSON     string
	// Saved banner-level config (logo, paper stock, reading direction,
	// entrance). Resume MUST carry it back into the designer or the next
	// save silently overwrites the stored blob with defaults — the bug
	// that kept eating the author-placed brand logo.
	BannerConfigJSON string
	// Set when a save/publish failed and the designer is re-rendered
	// with the submitted state — the boot shows it as a toast.
	ErrorMsg string
}

// SaveCreative saves an expandable magazine banner creative
func (h *Handler) SaveCreative(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()

	campID := r.FormValue("campaignId")
	name := strings.TrimSpace(r.FormValue("name"))
	pagesJson := r.FormValue("pagesJson")
	bannerConfigJson := r.FormValue("bannerConfigJson")
	landingUrl := r.FormValue("landingUrl")
	lpTextSnapshot := r.FormValue("lpTextSnapshot")
	// draft=1 means the advertiser clicked Save Draft rather than Publish —
	// the API skips banner render + Gemini verify and stores with
	// status=Draft so the creative can be resumed later.
	isDraft := r.FormValue("draft") == "1"
	// creativeId lets us overwrite an existing draft rather than creating
	// a new row on each save.
	creativeID := r.FormValue("creativeId")

	if name == "" {
		// Defensive only — the editor's compose step requires a name
		// before Generate, so this fires just for stale designer
		// sessions opened pre-requirement. Never REJECT a save over a
		// missing name: that would discard the user's design work.
		name = "Untitled creative"
	}

	// Parse pages JSON to forward as structured request
	var pages []map[string]any
	json.Unmarshal([]byte(pagesJson), &pages)

	body := map[string]any{
		"name":       name,
		"pages":      pages,
		"landingUrl": landingUrl,
	}
	if lpTextSnapshot != "" {
		body["lpTextSnapshot"] = lpTextSnapshot
	}
	// Forward the banner-level config blob verbatim. Empty string =
	// designer didn't send one (older bundle); skip the field so the
	// Scala side stores null and the banner uses its hardcoded
	// defaults at delivery time.
	if bannerConfigJson != "" {
		body["bannerConfigJson"] = bannerConfigJson
	}
	if isDraft {
		body["draft"] = true
	}
	if creativeID != "" {
		body["creativeId"] = creativeID
	}
	// LP-original font grants (presence = the advertiser ticked the license
	// checkbox in the wizard; the designer threads the field through
	// verbatim). Forwarded as structured JSON so core copies each
	// quarantined file to the live catalog key before rendering.
	if lpFontsJSON := r.FormValue("lpFontsJson"); lpFontsJSON != "" {
		var lpFonts []struct {
			Family string `json:"family"`
			Weight int    `json:"weight"`
			Hash   string `json:"hash"`
		}
		if json.Unmarshal([]byte(lpFontsJSON), &lpFonts) == nil && len(lpFonts) > 0 {
			body["lpFonts"] = lpFonts
			families := make([]string, 0, len(lpFonts))
			for _, f := range lpFonts {
				families = append(families, f.Family)
			}
			slog.Info("SaveCreative: activating LP-original fonts (license confirmed)",
				"advertiser", claims.AdvertiserID, "campID", campID, "families", strings.Join(families, ","))
		}
	}
	reqBody, _ := json.Marshal(body)

	_, err := h.corePostChecked(
		fmt.Sprintf("/v1/advertisers/me/campaigns/%s/creatives", campID),
		claims,
		string(reqBody),
	)
	if err != nil {
		// The old path logged and redirected anyway — a mid-rollout 503
		// looked exactly like success, the creatives page polled 60s for
		// a row that never existed, and the author's design was GONE (the
		// form had already navigated away). Instead: put the designer
		// back up with the submitted state and say what happened. Nothing
		// is lost; Publish again when the ad server is back.
		slog.Error("SaveCreative failed", "err", err, "draft", isDraft)
		user, _ := h.sessionUser(r)
		size := "300x250"
		if wv, hv := r.FormValue("width"), r.FormValue("height"); wv != "" && hv != "" {
			size = wv + "x" + hv
		}
		data := creativeDesignData{
			CampaignID:       campID,
			LandingURL:       landingUrl,
			CreativeName:     name,
			BannerSize:       size,
			PagesJSON:        pagesJson,
			LPTextSnapshot:   lpTextSnapshot,
			CreativeID:       creativeID,
			BannerScriptURL:  h.bannerScriptURL,
			BannerConfigJSON: bannerConfigJson,
			LPFontsJSON:      r.FormValue("lpFontsJson"),
			ErrorMsg:         i18n.T(h.lang(r, user), "Saving failed — the ad server did not accept the creative. Your work is still here; try again in a moment."),
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		t := getPageStandalone(h.lang(r, user), "advertiser/creative-design.html")
		if terr := t.ExecuteTemplate(w, "creative-design.html", data); terr != nil {
			slog.Error("SaveCreative: designer re-render failed", "err", terr)
			http.Error(w, "render error: "+terr.Error(), http.StatusInternalServerError)
		}
		return
	}

	redirectURL := fmt.Sprintf("/advertiser/creatives?campaignId=%s", campID)
	if !isDraft {
		// Publish (not Save Draft): the new creative's row is written
		// asynchronously, so it may be absent on the first list load. The
		// marker makes the creatives page poll for a short grace window until
		// it appears — no manual reload needed.
		redirectURL += "&published=1"
	}
	http.Redirect(w, r, redirectURL, http.StatusSeeOther)
}

// ResumeDraft loads a saved draft's pages + metadata from the core API
// and renders the designer with that state pre-populated. creativeId
// is carried through so subsequent Save Draft / Publish overwrite the
// same row instead of minting new ULIDs.
func (h *Handler) ResumeDraft(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()

	campID := r.FormValue("campaignId")
	creativeID := r.FormValue("creativeId")
	if creativeID == "" {
		http.Redirect(w, r, "/advertiser/creatives?campaignId="+url.QueryEscape(campID), http.StatusSeeOther)
		return
	}

	body, err := h.coreGet("/v1/advertisers/me/campaigns/"+url.PathEscape(campID)+"/creatives/"+url.PathEscape(creativeID)+"/draft", claims)
	if err != nil {
		slog.Error("ResumeDraft: coreGet failed", "err", err, "creativeId", creativeID)
		http.Redirect(w, r, "/advertiser/creatives?campaignId="+url.QueryEscape(campID), http.StatusSeeOther)
		return
	}
	var draft struct {
		CreativeID       string `json:"creativeId"`
		Name             string `json:"name"`
		LandingURL       string `json:"landingUrl"`
		PagesJSON        string `json:"pagesJson"`
		LPTextSnapshot   string `json:"lpTextSnapshot"`
		BannerConfigJSON string `json:"bannerConfigJson"`
	}
	if err := json.Unmarshal(body, &draft); err != nil {
		slog.Error("ResumeDraft: bad JSON", "err", err)
		http.Redirect(w, r, "/advertiser/creatives?campaignId="+url.QueryEscape(campID), http.StatusSeeOther)
		return
	}

	// Render the designer template directly with the draft's state,
	// bypassing the editor/handoff form flow since the pages are
	// already authored.
	data := creativeDesignData{
		CampaignID:      campID,
		LandingURL:      draft.LandingURL,
		CreativeName:    draft.Name,
		BannerSize:      "300x250",
		PagesJSON:       draft.PagesJSON,
		LPTextSnapshot:  draft.LPTextSnapshot,
		CreativeID:      draft.CreativeID,
		BannerScriptURL: h.bannerScriptURL,
		// A resumed draft is already authored: brand-kit/template are
		// generation-time inputs and LP-font grants are one-shot save-request
		// data (the quarantined fonts were already activated to R2 by the
		// save that carried them) — empty is correct, not just safe.
		BrandKitJSON: "",
		TemplateID:   "",
		LPFontsJSON:  "",
		// The saved creative-wide config rides back in — see the struct
		// comment. Empty when the row predates bannerConfigJson.
		BannerConfigJSON: draft.BannerConfigJSON,
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
	t := getPageStandalone(h.lang(r, user), "advertiser/creative-design.html")
	if err := t.ExecuteTemplate(w, "creative-design.html", data); err != nil {
		slog.Error("ResumeDraft: render failed", "err", err)
		http.Error(w, "render error: "+err.Error(), http.StatusInternalServerError)
	}
}

// computeArgmaxStability turns the per-cycle argmax history into a
// publisher-readable status. Returns nil if there's no data at all
// (template handles nil-case with a fallback message).
//
// Status thresholds — calibrated from the wide-vs-tight cluster tests:
//   - σ < $0.50 over N≥3 cycles → "Stable optimum"
//   - σ < $2.00                 → "Mildly variable"
//   - σ ≥ $2.00                 → "Highly variable"
//   - N < 2                     → "Insufficient data"
//
// The publisher's actual question this answers: "is the optimizer
// converging on a real answer, or is it just guessing?"
func computeArgmaxStability(lang string, history []argmaxHistoryPoint) *argmaxStability {
	n := len(history)
	if n == 0 {
		return nil
	}
	if n == 1 {
		return &argmaxStability{
			Status:      "Insufficient data",
			StatusColor: "bg-gray-100 text-gray-600",
			Summary:     i18n.T(lang, "Only one completed cycle so far (picked %s)", history[0].Label),
			Recent:      history[0].Label,
			N:           1,
		}
	}

	// Mean + standard deviation across all picks in the buffer.
	sum := 0.0
	for _, p := range history {
		sum += p.Floor
	}
	mean := sum / float64(n)
	variance := 0.0
	for _, p := range history {
		d := p.Floor - mean
		variance += d * d
	}
	variance /= float64(n)
	stddev := math.Sqrt(variance)

	// Build the "recent picks" string (last up to 5, oldest→newest).
	tailLen := 5
	if tailLen > n {
		tailLen = n
	}
	recentLabels := make([]string, 0, tailLen)
	for i := n - tailLen; i < n; i++ {
		recentLabels = append(recentLabels, history[i].Label)
	}
	recent := strings.Join(recentLabels, ", ")

	// Count how many of the last `tailLen` picks fall within $0.50 of
	// the most recent pick. Catches the "stable but with one outlier"
	// case better than a pure std-dev threshold would.
	latest := history[n-1].Floor
	agreed := 0
	for i := n - tailLen; i < n; i++ {
		if math.Abs(history[i].Floor-latest) < 0.5 {
			agreed++
		}
	}

	status := ""
	color := ""
	summary := ""
	switch {
	case stddev < 0.50 && n >= 3:
		status = "Stable optimum"
		color = "bg-green-100 text-green-800"
		summary = i18n.T(lang, "Last %d cycles all within $0.50 of $%.2f", agreed, latest)
	case stddev < 2.00:
		status = "Mildly variable"
		color = "bg-yellow-100 text-yellow-800"
		summary = i18n.T(lang, "Recent picks: %s (σ=$%.2f)", recent, stddev)
	default:
		status = "Highly variable"
		color = "bg-red-100 text-red-700"
		summary = i18n.T(lang, "Recent picks: %s (σ=$%.2f over %d cycles)", recent, stddev, n)
	}

	// Special-case n==2 with same value: very obviously stable but
	// std-dev gates above require n≥3. Override label here.
	if n == 2 && stddev < 0.50 {
		status = "Converging"
		color = "bg-blue-100 text-blue-800"
		summary = i18n.T(lang, "2 consecutive cycles agree on %s (collect more cycles to confirm stability)", history[n-1].Label)
	}

	return &argmaxStability{
		Status:      status,
		StatusColor: color,
		Summary:     summary,
		StdDev:      fmt.Sprintf("$%.2f", stddev),
		Recent:      recent,
		N:           n,
	}
}
