package billing

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// MeteringRow is one billable cell from the core metering range endpoint:
// (advertiser, campaign, site) over an instant window, in integer
// micro-dollars summed per event (partition-invariant — see
// /v1/internal/metering/range). PublisherID is empty when the site has no
// publisher_sites mapping — such cells must be skipped and alerted on BOTH
// sides, never silently billed.
type MeteringRow struct {
	AdvertiserID string
	CampaignID   string
	SiteID       string
	PublisherID  string
	Impressions  int64
	GrossMicros  int64
}

// CoreClient is the settlement job's view of the core API's internal
// endpoints. An interface so tests can fake the core.
type CoreClient interface {
	// MeteringRange pulls one entity's billable cells for the half-open
	// instant window [from, to). Exactly one of the owner types
	// advertiser/publisher selects the filter. allowPartial asks core to
	// skip its window_not_final guard so a window ending near now can be
	// read — for the settle-entity operator/test path only; the scheduled
	// job always passes false.
	MeteringRange(ctx context.Context, owner OwnerType, ownerID string, from, to time.Time, allowPartial bool) ([]MeteringRow, error)
	// MeteringUnsettled returns per-advertiser billable gross micros that
	// exist in core metering but are not settled yet — each advertiser
	// counted from its OWN cursor instant — the basis for projected wallet
	// balances.
	MeteringUnsettled(ctx context.Context, since map[string]time.Time) (map[string]int64, error)
	// MeteringEntities returns advertiser and publisher ids with billable
	// impressions since the instant, each mapped to its earliest billable
	// event time in the span — settlement-cursor discovery. Genesis cursors
	// anchor at the local midnight before that earliest event so no traffic
	// falls outside every window.
	MeteringEntities(ctx context.Context, since time.Time) (advertisers, publishers map[string]time.Time, err error)
	SuspendAdvertiser(ctx context.Context, advertiserID string) error
	ResumeAdvertiser(ctx context.Context, advertiserID string) error
	// SetAdvertiserTimezone pushes the advertiser account's IANA timezone
	// ("" = UTC) so the core's budget rollover + pacing follow it. The
	// settler reads the same zone from orgs.timezone directly (budget day
	// == billing day); this call only informs the core entities.
	SetAdvertiserTimezone(ctx context.Context, advertiserID, timezone string) error
}

// HTTPCoreClient talks to the Scala core's /v1/internal endpoints.
// InternalKey is sent as X-Internal-Key when non-empty (the core enforces
// it only when INTERNAL_API_KEY is set on its side).
type HTTPCoreClient struct {
	BaseURL     string
	InternalKey string
	Client      *http.Client
}

func NewHTTPCoreClient(baseURL, internalKey string) *HTTPCoreClient {
	return &HTTPCoreClient{
		BaseURL:     baseURL,
		InternalKey: internalKey,
		Client:      &http.Client{Timeout: 60 * time.Second},
	}
}

// meteringRangeResponse mirrors the core's MeteringRangeResponse JSON.
// GrossMicros is an integer straight from core — no float parsing, the
// partition-invariance guarantee rides on never round-tripping through
// floating point.
type meteringRangeResponse struct {
	From string `json:"from"`
	To   string `json:"to"`
	Rows []struct {
		AdvertiserID string `json:"advertiserId"`
		CampaignID   string `json:"campaignId"`
		SiteID       string `json:"siteId"`
		PublisherID  string `json:"publisherId"`
		Impressions  int64  `json:"impressions"`
		GrossMicros  int64  `json:"grossMicros"`
	} `json:"rows"`
}

func (c *HTTPCoreClient) MeteringRange(ctx context.Context, owner OwnerType, ownerID string, from, to time.Time, allowPartial bool) ([]MeteringRow, error) {
	q := url.Values{}
	q.Set("from", from.UTC().Format(time.RFC3339))
	q.Set("to", to.UTC().Format(time.RFC3339))
	switch owner {
	case OwnerAdvertiser:
		q.Set("advertiserId", ownerID)
	case OwnerPublisher:
		q.Set("publisherId", ownerID)
	default:
		return nil, fmt.Errorf("metering range: owner must be advertiser or publisher, got %q", owner)
	}
	if allowPartial {
		q.Set("allowPartial", "true")
	}
	body, err := c.do(ctx, http.MethodGet, c.BaseURL+"/v1/internal/metering/range?"+q.Encode())
	if err != nil {
		return nil, err
	}
	var resp meteringRangeResponse
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, fmt.Errorf("metering range response: %w", err)
	}
	rows := make([]MeteringRow, 0, len(resp.Rows))
	for _, r := range resp.Rows {
		rows = append(rows, MeteringRow{
			AdvertiserID: r.AdvertiserID,
			CampaignID:   r.CampaignID,
			SiteID:       r.SiteID,
			PublisherID:  r.PublisherID,
			Impressions:  r.Impressions,
			GrossMicros:  r.GrossMicros,
		})
	}
	return rows, nil
}

type unsettledResponse struct {
	Rows []struct {
		AdvertiserID string `json:"advertiserId"`
		GrossMicros  int64  `json:"grossMicros"`
	} `json:"rows"`
}

func (c *HTTPCoreClient) MeteringUnsettled(ctx context.Context, since map[string]time.Time) (map[string]int64, error) {
	if len(since) == 0 {
		return map[string]int64{}, nil
	}
	type sinceRow struct {
		AdvertiserID string `json:"advertiserId"`
		Since        string `json:"since"`
	}
	rows := make([]sinceRow, 0, len(since))
	for id, t := range since {
		rows = append(rows, sinceRow{AdvertiserID: id, Since: t.UTC().Format(time.RFC3339)})
	}
	payload, err := json.Marshal(map[string]any{"rows": rows})
	if err != nil {
		return nil, err
	}
	body, err := c.doJSON(ctx, http.MethodPost, c.BaseURL+"/v1/internal/metering/unsettled", payload)
	if err != nil {
		return nil, err
	}
	var resp unsettledResponse
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, fmt.Errorf("unsettled response: %w", err)
	}
	out := make(map[string]int64, len(resp.Rows))
	for _, r := range resp.Rows {
		out[r.AdvertiserID] = r.GrossMicros
	}
	return out, nil
}

type entityRow struct {
	ID       string `json:"id"`
	Earliest string `json:"earliest"`
}

type entitiesResponse struct {
	Advertisers []entityRow `json:"advertisers"`
	Publishers  []entityRow `json:"publishers"`
}

func (c *HTTPCoreClient) MeteringEntities(ctx context.Context, since time.Time) (map[string]time.Time, map[string]time.Time, error) {
	body, err := c.do(ctx, http.MethodGet,
		c.BaseURL+"/v1/internal/metering/entities?since="+url.QueryEscape(since.UTC().Format(time.RFC3339)))
	if err != nil {
		return nil, nil, err
	}
	var resp entitiesResponse
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, nil, fmt.Errorf("entities response: %w", err)
	}
	parse := func(rows []entityRow) (map[string]time.Time, error) {
		out := make(map[string]time.Time, len(rows))
		for _, r := range rows {
			t, err := time.Parse(time.RFC3339Nano, r.Earliest)
			if err != nil {
				return nil, fmt.Errorf("entity %s earliest %q: %w", r.ID, r.Earliest, err)
			}
			out[r.ID] = t
		}
		return out, nil
	}
	advs, err := parse(resp.Advertisers)
	if err != nil {
		return nil, nil, err
	}
	pubs, err := parse(resp.Publishers)
	if err != nil {
		return nil, nil, err
	}
	return advs, pubs, nil
}

func (c *HTTPCoreClient) SuspendAdvertiser(ctx context.Context, advertiserID string) error {
	_, err := c.do(ctx, http.MethodPost, fmt.Sprintf("%s/v1/internal/advertisers/%s/suspend", c.BaseURL, advertiserID))
	return err
}

func (c *HTTPCoreClient) ResumeAdvertiser(ctx context.Context, advertiserID string) error {
	_, err := c.do(ctx, http.MethodPost, fmt.Sprintf("%s/v1/internal/advertisers/%s/resume", c.BaseURL, advertiserID))
	return err
}

// SetAdvertiserTimezone pushes the advertiser account's timezone (IANA name;
// "" = UTC) so budget rollover and pacing follow the advertiser's day. The
// settler reads the same zone from orgs.timezone directly for the billing
// day (budget day == billing day).
func (c *HTTPCoreClient) SetAdvertiserTimezone(ctx context.Context, advertiserID, timezone string) error {
	payload, err := json.Marshal(map[string]string{"timezone": timezone})
	if err != nil {
		return err
	}
	_, err = c.doJSON(ctx, http.MethodPost,
		fmt.Sprintf("%s/v1/internal/advertisers/%s/timezone", c.BaseURL, advertiserID), payload)
	return err
}

// SuspendPublisher freezes serving on every one of the publisher's sites
// (operator org suspension); ResumePublisher lifts it. Reversible — the
// core keeps approvals/state intact.
func (c *HTTPCoreClient) SuspendPublisher(ctx context.Context, publisherID string) error {
	_, err := c.do(ctx, http.MethodPost, fmt.Sprintf("%s/v1/internal/publishers/%s/suspend", c.BaseURL, publisherID))
	return err
}

func (c *HTTPCoreClient) ResumePublisher(ctx context.Context, publisherID string) error {
	_, err := c.do(ctx, http.MethodPost, fmt.Sprintf("%s/v1/internal/publishers/%s/resume", c.BaseURL, publisherID))
	return err
}

// FraudFlag is one open flag from the Layer-2 economics detector
// (docs/design/FRAUD_PREVENTION.md), for the admin review queue.
type FraudFlag struct {
	ID        int64   `json:"id"`
	SiteID    string  `json:"siteId"`
	Signal    string  `json:"signal"`
	Severity  float64 `json:"severity"`
	WindowDay string  `json:"windowDay"`
	Evidence  string  `json:"evidence"`
	Status    string  `json:"status"`
	FlaggedAt string  `json:"flaggedAt"`
}

// ListFraudFlags returns the open fraud flags (newest first).
func (c *HTTPCoreClient) ListFraudFlags(ctx context.Context) ([]FraudFlag, error) {
	body, err := c.do(ctx, http.MethodGet, fmt.Sprintf("%s/v1/internal/fraud-flags", c.BaseURL))
	if err != nil {
		return nil, err
	}
	var resp struct {
		Flags []FraudFlag `json:"flags"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, err
	}
	return resp.Flags, nil
}

// ResolveFraudFlag sets a flag's status to "released" (false positive) or
// "confirmed" (real fraud). Confirming does not itself suspend — the caller
// composes it with SuspendSite.
func (c *HTTPCoreClient) ResolveFraudFlag(ctx context.Context, id int64, status, by string) error {
	payload, err := json.Marshal(map[string]string{"status": status, "resolvedBy": by})
	if err != nil {
		return err
	}
	_, err = c.doJSON(ctx, http.MethodPost,
		fmt.Sprintf("%s/v1/internal/fraud-flags/%d/resolve", c.BaseURL, id), payload)
	return err
}

// SuspendSite freezes serving on one site (the Layer-3 confirm-fraud
// enforcement lever) without touching the publisher's other sites.
func (c *HTTPCoreClient) SuspendSite(ctx context.Context, siteID string) error {
	_, err := c.do(ctx, http.MethodPost, fmt.Sprintf("%s/v1/internal/sites/%s/suspend", c.BaseURL, siteID))
	return err
}

func (c *HTTPCoreClient) do(ctx context.Context, method, url string) ([]byte, error) {
	return c.doJSON(ctx, method, url, nil)
}

// doJSON is do with an optional application/json request body.
func (c *HTTPCoreClient) doJSON(ctx context.Context, method, url string, body []byte) ([]byte, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, url, reader)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.InternalKey != "" {
		req.Header.Set("X-Internal-Key", c.InternalKey)
	}
	resp, err := c.Client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	respBody, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		snippet := string(respBody)
		if len(snippet) > 300 {
			snippet = snippet[:300]
		}
		return nil, fmt.Errorf("core %s %s: %s: %s", method, url, resp.Status, snippet)
	}
	return respBody, nil
}
