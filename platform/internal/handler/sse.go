package handler

import (
	"bufio"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"
)

// SSEProxyPublisher connects to the core API's publisher-level SSE endpoint
// (events for ALL the publisher's sites on one stream) and re-emits to the
// browser. This is what the approval inbox uses: it aggregates every site,
// so a single-site stream missed events for all the others (live-update bug
// found 2026-07-12).
func (h *Handler) SSEProxyPublisher(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	coreURL := fmt.Sprintf("%s/v1/publishers/%s/events", h.coreAPIURL, claims.PublisherID)
	h.proxySSE(w, r, coreURL, "publisher:"+claims.PublisherID)
}

// SSEProxy connects to the core API's per-site SSE endpoint and re-emits
// events to the browser. Kept for compatibility; new pages should prefer
// SSEProxyPublisher.
func (h *Handler) SSEProxy(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	siteID := r.PathValue("siteId")
	if siteID == "" {
		http.Error(w, "missing siteId", http.StatusBadRequest)
		return
	}

	coreURL := fmt.Sprintf("%s/v1/publishers/%s/sites/%s/events", h.coreAPIURL, claims.PublisherID, siteID)
	h.proxySSE(w, r, coreURL, siteID)
}

// proxySSE is the Go BFF acting as an SSE bridge: connect to a core SSE
// endpoint and forward the stream to the browser line by line.
func (h *Handler) proxySSE(w http.ResponseWriter, r *http.Request, coreURL, label string) {
	slog.Info("SSE proxy connecting", "url", coreURL)

	resp, err := http.Get(coreURL)
	if err != nil {
		slog.Error("SSE proxy: failed to connect to core", "error", err)
		http.Error(w, "failed to connect to event stream", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	// A non-200 from core (404 unknown publisher/site, 5xx) must NOT be
	// wrapped in a 200 SSE stream — the browser's EventSource would treat
	// the error body as a dead stream and silently reconnect forever.
	if resp.StatusCode != http.StatusOK {
		slog.Warn("SSE proxy: core refused stream", "status", resp.StatusCode, "url", coreURL)
		http.Error(w, "event stream unavailable", http.StatusBadGateway)
		return
	}

	// Disable the server's WriteTimeout for this long-lived SSE connection
	rc := http.NewResponseController(w)
	rc.SetWriteDeadline(time.Time{}) // zero = no deadline

	// Set SSE headers
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no") // nginx compatibility

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming not supported", http.StatusInternalServerError)
		return
	}
	flusher.Flush()

	// Read from core and forward to browser
	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := scanner.Text()

		// Forward the SSE line as-is
		fmt.Fprintf(w, "%s\n", line)

		// Flush after each empty line (end of SSE event)
		if strings.TrimSpace(line) == "" {
			flusher.Flush()
		}

		// Check if client disconnected
		select {
		case <-r.Context().Done():
			slog.Info("SSE proxy: client disconnected", "stream", label)
			return
		default:
		}
	}

	if err := scanner.Err(); err != nil {
		slog.Warn("SSE proxy: stream ended", "error", err)
	}
}
