package billing

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// SetAdvertiserTimezone must POST a JSON {"timezone": ...} body to the
// internal timezone endpoint with the X-Internal-Key header — the first
// core-client call to carry a request body.
func TestSetAdvertiserTimezone(t *testing.T) {
	var gotMethod, gotPath, gotKey, gotContentType string
	var gotBody []byte
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		gotKey = r.Header.Get("X-Internal-Key")
		gotContentType = r.Header.Get("Content-Type")
		gotBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	c := NewHTTPCoreClient(server.URL, "test-key")
	if err := c.SetAdvertiserTimezone(context.Background(), "adv-1", "Asia/Tokyo"); err != nil {
		t.Fatalf("SetAdvertiserTimezone: %v", err)
	}
	if gotMethod != http.MethodPost || gotPath != "/v1/internal/advertisers/adv-1/timezone" {
		t.Errorf("request = %s %s, want POST /v1/internal/advertisers/adv-1/timezone", gotMethod, gotPath)
	}
	if gotKey != "test-key" {
		t.Errorf("X-Internal-Key = %q, want %q", gotKey, "test-key")
	}
	if gotContentType != "application/json" {
		t.Errorf("Content-Type = %q, want application/json", gotContentType)
	}
	var payload struct {
		Timezone string `json:"timezone"`
	}
	if err := json.Unmarshal(gotBody, &payload); err != nil || payload.Timezone != "Asia/Tokyo" {
		t.Errorf("body = %q (err=%v), want {\"timezone\":\"Asia/Tokyo\"}", gotBody, err)
	}
}

func TestSetAdvertiserTimezoneCoreError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "no such advertiser", http.StatusNotFound)
	}))
	defer server.Close()

	c := NewHTTPCoreClient(server.URL, "")
	err := c.SetAdvertiserTimezone(context.Background(), "adv-x", "UTC")
	if err == nil || !strings.Contains(err.Error(), "404") {
		t.Errorf("err = %v, want core 404 error", err)
	}
}
