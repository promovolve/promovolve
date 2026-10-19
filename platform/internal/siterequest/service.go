package siterequest

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/hanishi/promovolve/platform/internal/model"
)

// coreClient bounds the approve-time provisioning call so a hung core can't
// wedge the admin queue page (same rationale as handler.coreClient).
var coreClient = &http.Client{Timeout: 30 * time.Second}

type Service struct {
	repo       *Repository
	coreAPIURL string
	// floors supplies the starting and minimum floor CPM a newly provisioned
	// site opens at, in micros of the deployment's base currency. Set at
	// install: a floor is the one setting with no sensible cross-currency
	// default, since $0.50 reads as a reserve and ¥0.50 as none at all.
	floors FloorDefaults
	// ReservedHosts are the deployment's own public hosts (dashboard, ad
	// server, CDN). A site request for one of them, or a subdomain of one,
	// is refused before it reaches the admin queue. Sibling hosts stay
	// open: the operator may run real publisher sites next to them.
	ReservedHosts []string
}

// FloorDefaults reads the operator's configured floors. Satisfied by
// *billing.Service; an interface so this package does not depend on billing.
type FloorDefaults interface {
	FloorCpmMicros(ctx context.Context) (int64, error)
	MinFloorCpmMicros(ctx context.Context) (int64, error)
}

func NewService(repo *Repository, coreAPIURL string, floors FloorDefaults) *Service {
	return &Service{repo: repo, coreAPIURL: coreAPIURL, floors: floors}
}

// floorStrings renders the configured floors as the decimal strings the core
// API takes. Micros are the platform's storage unit; the core speaks decimal
// strings, and %.4f is the precision its CPM columns keep.
func floorStrings(ctx context.Context, f FloorDefaults) (start, min string) {
	start, min = "0.50", "0.10"
	if f == nil {
		return start, min
	}
	if v, err := f.FloorCpmMicros(ctx); err == nil && v > 0 {
		start = fmt.Sprintf("%.4f", float64(v)/1e6)
	}
	if v, err := f.MinFloorCpmMicros(ctx); err == nil && v > 0 {
		min = fmt.Sprintf("%.4f", float64(v)/1e6)
	}
	return start, min
}

// Request records a publisher's intent to add a site. No core entity is
// created here — that happens at admin approval. A site that is already
// LIVE (verified by this publisher or registered to any other) is rejected
// up front: such a request could never be approved (core returns
// site_id_taken at provision time), so accepting it would only park a
// dead row in the admin queue.
//
// The publisher_sites projection outlives site deletion (it is the durable
// revenue-attribution mapping for settlement), so a row there proves
// history, not liveness — a deleted site must be re-registrable through
// this flow. A projection hit is therefore confirmed against the core API
// before it blocks the request.
func (s *Service) Request(ctx context.Context, publisherID, requestedBy, siteID, domain, pageURL string) error {
	if reservedHost(domain, s.ReservedHosts) {
		return ErrReservedHost
	}
	if owner, found, err := s.repo.LiveSiteOwner(ctx, siteID, domain); err != nil {
		return err
	} else if found && s.siteLive(ctx, owner, siteID) {
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

// siteLive reports whether siteID currently exists on the core API under the
// given owner. Body-based: a 200 with a matching id is live; an ErrorResponse
// whose code is a not-found variant is a stale projection row. The explicit-
// publisher getSite path answers through requireOwnedSite, which returns
// site_not_found both for a cleared site AND for an entity-ask failure — so
// a core-side blip can admit a request that a truly live site would have
// blocked. That is acceptable: approval is the authority (core rejects with
// site_id_taken), so the worst case is a dead row the admin sees fail.
// Transport-level ambiguity (unreachable core, unparseable body) still
// counts as live, degrading to the old behavior — a spurious "already
// registered" the publisher can retry.
func (s *Service) siteLive(ctx context.Context, ownerID, siteID string) bool {
	url := fmt.Sprintf("%s/v1/publishers/%s/sites/%s", s.coreAPIURL, ownerID, siteID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return true
	}
	resp, err := coreClient.Do(req)
	if err != nil {
		return true
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	var probe struct {
		ID   string `json:"id"`
		Code string `json:"code"`
	}
	if json.Unmarshal(body, &probe) != nil {
		return true
	}
	// Only an explicit not-found code proves staleness; every other outcome
	// (including odd 200s) keeps the conservative "live" answer.
	return probe.Code != "not_found" && probe.Code != "site_not_found"
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
	// A site opens at the operator's configured floors rather than a literal.
	// Failure here is not fatal: the core has its own seeds, and refusing to
	// provision an approved site because a settings read blipped would be a
	// worse outcome than opening at the fallback.
	startFloor, minFloor := floorStrings(context.Background(), s.floors)

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
		"minFloorCpm": minFloor,
		"floorCpm":    startFloor,
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

// reservedHost reports whether domain is one of hosts or a subdomain of one.
func reservedHost(domain string, hosts []string) bool {
	d := strings.TrimSuffix(strings.ToLower(domain), ".")
	for _, h := range hosts {
		if d == h || strings.HasSuffix(d, "."+h) {
			return true
		}
	}
	return false
}

// UnverifiedReleaseReason is the card text a publisher sees after release.
// A fixed sentence so the Sites page can translate it.
const UnverifiedReleaseReason = "Released: the site was not verified within 14 days of approval. Add it again to retry."

// ReleaseUnverified deletes every site approved more than maxAge ago that
// still has not verified, so an abandoned registration stops holding the
// domain against its real owner (#285). The core decides: a site is
// released only when it answers with this request's domain and
// "unverified". A failed lookup, a deleted site or a verified one is left
// alone; the next sweep looks again.
func (s *Service) ReleaseUnverified(ctx context.Context, maxAge time.Duration) {
	reqs, err := s.repo.ListApprovedBefore(ctx, time.Now().Add(-maxAge))
	if err != nil {
		slog.Error("unverified-site sweep: list failed", "error", err)
		return
	}
	for _, req := range reqs {
		if !s.siteUnverified(ctx, req) {
			continue
		}
		if err := s.deleteSite(ctx, req); err != nil {
			slog.Error("unverified-site sweep: delete failed", "siteId", req.SiteID, "error", err)
			continue
		}
		if err := s.repo.MarkReleased(ctx, req.ID, UnverifiedReleaseReason); err != nil {
			slog.Error("unverified-site sweep: mark released failed", "siteId", req.SiteID, "error", err)
			continue
		}
		slog.Warn("released unverified site", "siteId", req.SiteID, "domain", req.Domain,
			"publisherId", req.PublisherID, "approvedAt", req.ReviewedAt)
	}
}

// RunReleaseLoop sweeps once at start, then every interval until ctx ends.
func (s *Service) RunReleaseLoop(ctx context.Context, maxAge, interval time.Duration) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		s.ReleaseUnverified(ctx, maxAge)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (s *Service) siteUnverified(ctx context.Context, req model.SiteRequest) bool {
	url := fmt.Sprintf("%s/v1/publishers/%s/sites/%s", s.coreAPIURL, req.PublisherID, req.SiteID)
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return false
	}
	resp, err := coreClient.Do(httpReq)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	var site struct {
		Domain             string `json:"domain"`
		VerificationStatus string `json:"verificationStatus"`
	}
	if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&site) != nil {
		return false
	}
	return site.VerificationStatus == "unverified" && strings.EqualFold(site.Domain, req.Domain)
}

func (s *Service) deleteSite(ctx context.Context, req model.SiteRequest) error {
	url := fmt.Sprintf("%s/v1/publishers/%s/sites/%s", s.coreAPIURL, req.PublisherID, req.SiteID)
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodDelete, url, nil)
	if err != nil {
		return err
	}
	resp, err := coreClient.Do(httpReq)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("core delete: %d %s", resp.StatusCode, body)
	}
	return nil
}
