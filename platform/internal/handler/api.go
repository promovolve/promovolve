package handler

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"

	"github.com/hanishi/promovolve/platform/internal/billing"
)

// SearchAdProducts proxies taxonomy search to core API and returns JSON.
// Passes the request language so display names come back localized; the
// core matches the query against English and Japanese names regardless.
// QueryEscape matters here: Japanese queries are multi-byte.
func (h *Handler) SearchAdProducts(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if claims == nil {
		slog.Warn("SearchAdProducts: unauthorized", "cookies", r.Header.Get("Cookie"))
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	q := r.URL.Query().Get("q")
	body, err := h.coreGet(fmt.Sprintf("/v1/taxonomy/ad-products?q=%s&lang=%s&limit=8",
		url.QueryEscape(q), h.lang(r, user)), claims)
	if err != nil {
		http.Error(w, `{"data":[]}`, http.StatusOK)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// SearchCategories proxies content category search to core API. Same
// language handling as SearchAdProducts.
func (h *Handler) SearchCategories(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	q := r.URL.Query().Get("q")
	body, err := h.coreGet(fmt.Sprintf("/v1/taxonomy/categories?q=%s&lang=%s&limit=8",
		url.QueryEscape(q), h.lang(r, user)), claims)
	if err != nil {
		http.Error(w, `{"data":[]}`, http.StatusOK)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// SearchSites proxies registered-site search to core API (media-targeting picker)
func (h *Handler) SearchSites(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	q := r.URL.Query().Get("q")
	body, err := h.coreGet(fmt.Sprintf("/v1/sites?q=%s&limit=8", q), claims)
	if err != nil {
		http.Error(w, `{"data":[]}`, http.StatusOK)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// SearchPlaces proxies the geographic type-ahead to the core API. Used by
// both the publisher declaring a site audience and the advertiser targeting
// one, so the two sides always pick from the same vocabulary.
// `lang` follows the viewer's dashboard language so a Japanese publisher
// searching 鎌倉 finds it.
func (h *Handler) SearchPlaces(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	q := r.URL.Query().Get("q")
	lang := r.URL.Query().Get("lang")
	codes := r.URL.Query().Get("codes")
	body, err := h.coreGet(fmt.Sprintf("/v1/places?q=%s&codes=%s&lang=%s&limit=8",
		url.QueryEscape(q), url.QueryEscape(codes), url.QueryEscape(lang)), claims)
	if err != nil {
		http.Error(w, `{"data":[]}`, http.StatusOK)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// GeoAvailability proxies the geographic inventory check. Called while the
// BidCheck asks the core whether a campaign would bid on a given page URL
// and, if not, which gate stops it. For the advertiser who set a target and
// sees no ads on the page they care about — the answer names the gate
// (category, place, audience, floor, paused) instead of leaving them to
// infer it from a blank slot.
func (h *Handler) BidCheck(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	campaignID := r.URL.Query().Get("campaignId")
	pageURL := strings.TrimSpace(r.URL.Query().Get("url"))
	if campaignID == "" || pageURL == "" {
		http.Error(w, `{"error":"campaignId and url are required"}`, http.StatusBadRequest)
		return
	}
	body, err := h.coreGet(fmt.Sprintf("/v1/advertisers/me/campaigns/%s/bid-check?url=%s",
		url.PathEscape(campaignID), url.QueryEscape(pageURL)), claims)
	if err != nil {
		http.Error(w, `{"error":"bid check unavailable"}`, http.StatusBadGateway)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// DeliveryCheck proxies the core's delivery checklist for one campaign. The
// core knows an advertiser is suspended but not why; when that item blocks,
// this adds detail.reason (org | wallet | other) from the org and wallet
// records only the platform holds.
func (h *Handler) DeliveryCheck(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	campaignID := r.URL.Query().Get("campaignId")
	if campaignID == "" {
		http.Error(w, `{"error":"campaignId is required"}`, http.StatusBadRequest)
		return
	}
	body, err := h.coreGet(fmt.Sprintf("/v1/advertisers/me/campaigns/%s/delivery-check",
		url.PathEscape(campaignID)), claims)
	if err != nil {
		http.Error(w, `{"error":"delivery check unavailable"}`, http.StatusBadGateway)
		return
	}
	var check struct {
		Items []struct {
			Key    string            `json:"key"`
			Status string            `json:"status"`
			Detail map[string]string `json:"detail"`
		} `json:"items"`
		FirstBlocked *string `json:"firstBlocked,omitempty"`
	}
	if json.Unmarshal(body, &check) == nil && len(check.Items) > 0 &&
		check.Items[0].Key == "advertiserActive" && check.Items[0].Status == "blocked" {
		ctx := r.Context()
		suspended := false
		if h.orgRepo != nil {
			suspended, _ = h.orgRepo.IsEntitySuspended(ctx, claims.AdvertiserID)
		}
		balance := int64(1) // unknown wallet → "other", never a wrong "top up"
		if h.billingSvc != nil {
			if acc, err := h.billingSvc.GetAccount(ctx, billing.OwnerAdvertiser, claims.AdvertiserID); err == nil {
				balance = acc.BalanceMicros
			}
		}
		if check.Items[0].Detail == nil {
			check.Items[0].Detail = map[string]string{}
		}
		check.Items[0].Detail["reason"] = suspensionReason(suspended, balance)
		if b, err := json.Marshal(check); err == nil {
			body = b
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// suspensionReason says why a suspended advertiser is suspended: the
// operator suspended its org, else its wallet ran dry (the wallet sweep),
// else something the platform doesn't track.
func suspensionReason(orgSuspended bool, balanceMicros int64) string {
	switch {
	case orgSuspended:
		return "org"
	case balanceMicros <= 0:
		return "wallet"
	default:
		return "other"
	}
}

// advertiser edits targeting, so a campaign that would never serve says so
// before it is saved rather than after a flat week of spend.
func (h *Handler) GeoAvailability(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	body, err := h.coreGet(fmt.Sprintf("/v1/geo-availability?audience=%s&places=%s",
		url.QueryEscape(r.URL.Query().Get("audience")),
		url.QueryEscape(r.URL.Query().Get("places"))), claims)
	if err != nil {
		// No body rather than zeros: the UI shows no warning at all when
		// this fails, because a wrong "no inventory" is worse than silence.
		http.Error(w, `{}`, http.StatusOK)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// SearchAdvertiserDomains proxies advertiser-domain search to core API
// (publisher's advertiser-domain block picker)
func (h *Handler) SearchAdvertiserDomains(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	q := r.URL.Query().Get("q")
	body, err := h.coreGet(fmt.Sprintf("/v1/advertiser-domains?q=%s&limit=8", q), claims)
	if err != nil {
		http.Error(w, `{"data":[]}`, http.StatusOK)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}
