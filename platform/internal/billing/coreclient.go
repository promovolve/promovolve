package billing

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"
)

// MeteringRow is one billable cell from the core metering endpoint:
// (advertiser, campaign, site) for one UTC day. PublisherID is empty when
// the site has no publisher_sites mapping — such rows must be skipped and
// alerted, never silently credited.
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
	// MeteringDaily pulls one UTC day's billable aggregate. allowPartial
	// asks core to skip its day_not_final guard so an in-progress day can
	// be settled — for the settle-day operator/test path only; the
	// scheduled job always passes false.
	MeteringDaily(ctx context.Context, day time.Time, allowPartial bool) ([]MeteringRow, error)
	// MeteringIntraday returns per-advertiser billable gross that exists in
	// core metering but has not been settled yet (from `since` UTC midnight
	// to now) — the basis for projected wallet balances.
	MeteringIntraday(ctx context.Context, since time.Time) (map[string]int64, error)
	SuspendAdvertiser(ctx context.Context, advertiserID string) error
	ResumeAdvertiser(ctx context.Context, advertiserID string) error
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

// meteringResponse mirrors the core's MeteringDailyResponse JSON.
type meteringResponse struct {
	Date string `json:"date"`
	Rows []struct {
		AdvertiserID string `json:"advertiserId"`
		CampaignID   string `json:"campaignId"`
		SiteID       string `json:"siteId"`
		PublisherID  string `json:"publisherId"`
		Impressions  int64  `json:"impressions"`
		Gross        string `json:"gross"` // dollars, %.6f
	} `json:"rows"`
}

func (c *HTTPCoreClient) MeteringDaily(ctx context.Context, day time.Time, allowPartial bool) ([]MeteringRow, error) {
	url := fmt.Sprintf("%s/v1/internal/metering/daily?date=%s", c.BaseURL, day.UTC().Format("2006-01-02"))
	if allowPartial {
		url += "&allowPartial=true"
	}
	body, err := c.do(ctx, http.MethodGet, url)
	if err != nil {
		return nil, err
	}
	var resp meteringResponse
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, fmt.Errorf("metering response: %w", err)
	}
	rows := make([]MeteringRow, 0, len(resp.Rows))
	for _, r := range resp.Rows {
		gross, err := strconv.ParseFloat(r.Gross, 64)
		if err != nil {
			return nil, fmt.Errorf("metering row gross %q: %w", r.Gross, err)
		}
		rows = append(rows, MeteringRow{
			AdvertiserID: r.AdvertiserID,
			CampaignID:   r.CampaignID,
			SiteID:       r.SiteID,
			PublisherID:  r.PublisherID,
			Impressions:  r.Impressions,
			GrossMicros:  DollarsToMicros(gross),
		})
	}
	return rows, nil
}

type intradayResponse struct {
	Since string `json:"since"`
	Rows  []struct {
		AdvertiserID string `json:"advertiserId"`
		Gross        string `json:"gross"`
	} `json:"rows"`
}

func (c *HTTPCoreClient) MeteringIntraday(ctx context.Context, since time.Time) (map[string]int64, error) {
	url := fmt.Sprintf("%s/v1/internal/metering/intraday?since=%s", c.BaseURL, since.UTC().Format("2006-01-02"))
	body, err := c.do(ctx, http.MethodGet, url)
	if err != nil {
		return nil, err
	}
	var resp intradayResponse
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, fmt.Errorf("intraday response: %w", err)
	}
	out := make(map[string]int64, len(resp.Rows))
	for _, r := range resp.Rows {
		gross, err := strconv.ParseFloat(r.Gross, 64)
		if err != nil {
			return nil, fmt.Errorf("intraday row gross %q: %w", r.Gross, err)
		}
		out[r.AdvertiserID] = DollarsToMicros(gross)
	}
	return out, nil
}

func (c *HTTPCoreClient) SuspendAdvertiser(ctx context.Context, advertiserID string) error {
	_, err := c.do(ctx, http.MethodPost, fmt.Sprintf("%s/v1/internal/advertisers/%s/suspend", c.BaseURL, advertiserID))
	return err
}

func (c *HTTPCoreClient) ResumeAdvertiser(ctx context.Context, advertiserID string) error {
	_, err := c.do(ctx, http.MethodPost, fmt.Sprintf("%s/v1/internal/advertisers/%s/resume", c.BaseURL, advertiserID))
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

func (c *HTTPCoreClient) do(ctx context.Context, method, url string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, method, url, nil)
	if err != nil {
		return nil, err
	}
	if c.InternalKey != "" {
		req.Header.Set("X-Internal-Key", c.InternalKey)
	}
	resp, err := c.Client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		snippet := string(body)
		if len(snippet) > 300 {
			snippet = snippet[:300]
		}
		return nil, fmt.Errorf("core %s %s: %s: %s", method, url, resp.Status, snippet)
	}
	return body, nil
}
