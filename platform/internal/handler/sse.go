package handler

import (
	"bufio"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"
)

// SSEProxy connects to the core API's SSE endpoint and re-emits events to the browser.
// This is the Go BFF acting as an SSE bridge.
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

	// Connect to core API SSE
	coreURL := fmt.Sprintf("%s/v1/publishers/%s/sites/%s/events", h.coreAPIURL, claims.PublisherID, siteID)
	slog.Info("SSE proxy connecting", "url", coreURL)

	resp, err := http.Get(coreURL)
	if err != nil {
		slog.Error("SSE proxy: failed to connect to core", "error", err)
		http.Error(w, "failed to connect to event stream", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

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
			slog.Info("SSE proxy: client disconnected", "siteId", siteID)
			return
		default:
		}
	}

	if err := scanner.Err(); err != nil {
		slog.Warn("SSE proxy: stream ended", "error", err)
	}
}
