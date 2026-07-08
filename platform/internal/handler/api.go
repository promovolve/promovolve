package handler

import (
	"fmt"
	"log/slog"
	"net/http"
)

// SearchAdProducts proxies taxonomy search to core API and returns JSON
func (h *Handler) SearchAdProducts(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		slog.Warn("SearchAdProducts: unauthorized", "cookies", r.Header.Get("Cookie"))
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	q := r.URL.Query().Get("q")
	body, err := h.coreGet(fmt.Sprintf("/v1/taxonomy/ad-products?q=%s&limit=8", q), claims)
	if err != nil {
		http.Error(w, `{"data":[]}`, http.StatusOK)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(body)
}

// SearchCategories proxies content category search to core API
func (h *Handler) SearchCategories(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	q := r.URL.Query().Get("q")
	body, err := h.coreGet(fmt.Sprintf("/v1/taxonomy/categories?q=%s&limit=8", q), claims)
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
