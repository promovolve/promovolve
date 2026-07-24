package siterequest

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/hanishi/promovolve/platform/internal/model"
)

// coreClient bounds the approve-time provisioning call so a hung core can't
// wedge the admin queue page (same rationale as handler.coreClient).
var coreClient = &http.Client{Timeout: 30 * time.Second}

type Service struct {
	repo       *Repository
	coreAPIURL string
}

func NewService(repo *Repository, coreAPIURL string) *Service {
	return &Service{repo: repo, coreAPIURL: coreAPIURL}
}

// Request records a publisher's intent to add a site. No core entity is
// created here — that happens at admin approval. A site that is already
// LIVE (verified by this publisher or registered to any other) is rejected
// up front: such a request could never be approved (core returns
// site_id_taken at provision time), so accepting it would only park a
// dead row in the admin queue.
func (s *Service) Request(ctx context.Context, publisherID, requestedBy, siteID, domain, pageURL string) error {
	if owner, found, err := s.repo.LiveSiteOwner(ctx, siteID, domain); err != nil {
		return err
	} else if found {
		if owner == publisherID {
			return ErrSiteAlreadyOwned
		}
		return ErrSiteTaken
	}
	req := &model.SiteRequest{
		PublisherID: publisherID,
		SiteID:      siteID,
		Domain:      domain,
		PageURL:     pageURL,
	}
	if requestedBy != "" {
		req.RequestedBy = &requestedBy
	}
	return s.repo.Create(ctx, req)
}

// Approve provisions the site on the core API and then persists the decision.
// Provision-first keeps approval retryable after a partial failure: the row
// stays pending on any core error, and re-creating a fresh slot-less site is
// a harmless re-initialization.
func (s *Service) Approve(ctx context.Context, requestID, reviewerID string) error {
	req, err := s.repo.GetByID(ctx, requestID)
	if err != nil {
		return err
	}
	if req.Status != StatusPending {
		return fmt.Errorf("request is not pending review (status: %s)", req.Status)
	}
	if err := s.provisionSite(req); err != nil {
		return err
	}
	return s.repo.UpdateDecision(ctx, requestID, StatusApproved, "", reviewerID)
}

// Reject keeps the row (with the optional admin-supplied reason) so the
// publisher sees the denied card until they delete it.
func (s *Service) Reject(ctx context.Context, requestID, reviewerID, reason string) error {
	req, err := s.repo.GetByID(ctx, requestID)
	if err != nil {
		return err
	}
	if req.Status != StatusPending {
		return fmt.Errorf("request is not pending review (status: %s)", req.Status)
	}
	return s.repo.UpdateDecision(ctx, requestID, StatusRejected, reason, reviewerID)
}

func (s *Service) Delete(ctx context.Context, requestID, publisherID string) error {
	return s.repo.Delete(ctx, requestID, publisherID)
}

func (s *Service) ListForPublisher(ctx context.Context, publisherID string) ([]model.SiteRequest, error) {
	return s.repo.ListForPublisher(ctx, publisherID)
}

func (s *Service) ListPending(ctx context.Context) ([]PendingRow, error) {
	return s.repo.ListPending(ctx)
}

// provisionSite replays the payload the dashboard used to send at add time,
// against the explicit-publisher path (no /me — there are no claims in the
// admin context). The payload shape is code, not data: only the inputs
// (site_id, domain, page_url) are stored on the request row.
func (s *Service) provisionSite(req *model.SiteRequest) error {
	payload, _ := json.Marshal(map[string]any{
		"id":     req.SiteID,
		"domain": req.Domain,
		// crawlConfig is vestigial (crawling is removed) but the core
		// CreateSiteRequest still requires the field.
		"crawlConfig": map[string]any{
			"seedUrl":        req.PageURL,
			"cronSchedule":   "0 0 2 * * ?",
			"maxDepth":       1,
			"concurrency":    1,
			"hostRegex":      ".*",
			"targetElements": []string{},
		},
		"slots":       []any{},
		"taxonomyIds": []string{},
		"minFloorCpm": "0.10",
	})

	url := fmt.Sprintf("%s/v1/publishers/%s/sites", s.coreAPIURL, req.PublisherID)
	resp, err := coreClient.Post(url, "application/json", bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("core API call failed: %w", err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	// Core failures arrive as an ErrorResponse JSON body {code, message},
	// e.g. site_id_taken when the site belongs to another publisher. Surface
	// the message verbatim so the admin sees why approval didn't stick.
	var coreErr struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	}
	if json.Unmarshal(body, &coreErr) == nil && coreErr.Code != "" {
		return fmt.Errorf("core rejected the site: %s", coreErr.Message)
	}
	if resp.StatusCode >= 300 {
		return fmt.Errorf("core API returned %d", resp.StatusCode)
	}
	return nil
}
