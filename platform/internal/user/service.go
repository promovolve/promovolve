package user

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"golang.org/x/crypto/bcrypt"

	"github.com/hanishi/promovolve/platform/internal/model"
	"github.com/hanishi/promovolve/platform/internal/org"
)

type Service struct {
	repo       *Repository
	orgs       *org.Repository
	coreAPIURL string
}

func NewService(repo *Repository, orgs *org.Repository, coreAPIURL string) *Service {
	return &Service{repo: repo, orgs: orgs, coreAPIURL: coreAPIURL}
}

// Orgs exposes the org repository to callers constructed around this service.
func (s *Service) Orgs() *org.Repository { return s.orgs }

// Register is the dev-only password path; side is the side being signed up
// for (the role column itself is just the 'user' grant).
func (s *Service) Register(ctx context.Context, email, password, displayName string, side model.Role) (*model.User, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return nil, fmt.Errorf("hash password: %w", err)
	}

	u := &model.User{
		ID:            uuid.New().String(),
		Email:         email,
		PasswordHash:  string(hash),
		Role:          model.RoleUser,
		RequestedSide: side,
		DisplayName:   displayName,
	}

	// Dev-only path: provision (or reuse the domain org's) entity, then
	// attach to the org like the approval flow does.
	o, entityID, err := s.ensureOrgSide(ctx, email, displayName, side, "")
	if err != nil {
		return nil, err
	}
	if side == model.RoleAdvertiser {
		u.AdvertiserID = &entityID
	} else if side == model.RolePublisher {
		u.PublisherID = &entityID
	}

	if err := s.repo.Create(ctx, u); err != nil {
		return nil, err
	}
	if err := s.attachMembership(ctx, o, u.ID, side); err != nil {
		return nil, err
	}
	return u, nil
}

// ensureOrgSide finds or creates the org for the email's domain and makes
// sure it holds the requested side, provisioning the core entity when the
// side is new. Returns the org and the side's entity id. Idempotent — safe
// to retry after partial failures (core auto-create is idempotent per email,
// SetSideEntity only fills an empty side). seedTz (validated IANA or "")
// seeds a NEWLY created org's account timezone — the requester's browser
// zone captured at signup; "" falls back to the platform default. An
// existing org's timezone is never touched here.
func (s *Service) ensureOrgSide(ctx context.Context, email, name string, side model.Role, seedTz string) (*model.Org, string, error) {
	domain := org.DomainOf(email)
	if domain == "" {
		return nil, "", fmt.Errorf("invalid email %q", email)
	}
	o, err := s.orgs.GetByDomain(ctx, domain)
	if err != nil {
		if !errors.Is(err, org.ErrNotFound) {
			return nil, "", err
		}
		if o, err = s.orgs.Create(ctx, domain, name, seedTz); err != nil {
			return nil, "", err
		}
	}
	if o.HasSide(side) {
		if side == model.RoleAdvertiser {
			return o, *o.AdvertiserID, nil
		}
		return o, *o.PublisherID, nil
	}
	entityID, err := s.provisionEntity(email, side)
	if err != nil {
		return nil, "", fmt.Errorf("provision %s: %w", side, err)
	}
	if err := s.orgs.SetSideEntity(ctx, o.ID, side, entityID); err != nil {
		return nil, "", err
	}
	if side == model.RoleAdvertiser {
		o.AdvertiserID = &entityID
	} else {
		o.PublisherID = &entityID
	}
	return o, entityID, nil
}

// attachMembership adds the user to the org: first member in = org admin,
// everyone after = member (org admins promote co-admins themselves). side
// seeds preferred_side — the member's landing side until they switch.
func (s *Service) attachMembership(ctx context.Context, o *model.Org, userID string, side model.Role) error {
	n, err := s.orgs.CountMembers(ctx, o.ID)
	if err != nil {
		return err
	}
	if max := s.orgs.MaxMembers(ctx); n >= max {
		return fmt.Errorf("organization %s already has the maximum of %d members", o.Domain, max)
	}
	role := model.OrgRoleMember
	if n == 0 {
		role = model.OrgRoleAdmin
	}
	return s.orgs.AddMember(ctx, o.ID, userID, role, nil, side)
}

// CreatePendingUser inserts an account request (status=pending, no core
// entity yet — that happens at approval). saveExtra runs inside the same
// transaction so the caller can persist the passkey credential atomically
// with the user row.
func (s *Service) CreatePendingUser(ctx context.Context, u *model.User, saveExtra func(tx pgx.Tx) error) error {
	u.Status = model.StatusPending
	tx, err := s.repo.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if err := s.repo.CreateTx(ctx, tx, u); err != nil {
		return err
	}
	if saveExtra != nil {
		if err := saveExtra(tx); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

// Approve activates a pending account request. The core entity is
// provisioned here — NOT at request time — so unreviewed strangers never own
// core entities. Provisioning first and persisting after keeps approval
// retryable: the core auto-create endpoints are idempotent per email.
func (s *Service) Approve(ctx context.Context, userID, reviewerID string) error {
	u, err := s.repo.GetByID(ctx, userID)
	if err != nil {
		return err
	}
	if u.Status != model.StatusPending {
		return fmt.Errorf("account is not pending review (status: %s)", u.Status)
	}

	side := u.RequestedSide
	if side != model.RoleAdvertiser && side != model.RolePublisher {
		return fmt.Errorf("request has no side recorded (requested_side: %q)", side)
	}
	// The org (keyed by email domain) owns the entity, not the user; the
	// approved account becomes the org's first admin — or joins an existing
	// org (only possible for requests that predate the org) as a member.
	o, entityID, err := s.ensureOrgSide(ctx, u.Email, u.CompanyName, side, u.Timezone)
	if err != nil {
		return err
	}
	if err := s.attachMembership(ctx, o, u.ID, side); err != nil {
		return err
	}
	var advertiserID, publisherID *string
	if side == model.RoleAdvertiser {
		advertiserID = &entityID
	} else {
		publisherID = &entityID
	}
	return s.repo.UpdateStatusAndEntity(ctx, userID, model.StatusActive, advertiserID, publisherID, reviewerID)
}

// ApproveOrgSide provisions the org's OTHER side after operator review of an
// org side request. The core entity is created with the requester's email
// (the auto-create endpoints key on email per side).
func (s *Service) ApproveOrgSide(ctx context.Context, requestID, reviewerID string) error {
	sr, err := s.orgs.GetSideRequest(ctx, requestID)
	if err != nil {
		return err
	}
	if sr.Status != "pending" {
		return fmt.Errorf("request is not pending review (status: %s)", sr.Status)
	}
	o, err := s.orgs.GetByID(ctx, sr.OrgID)
	if err != nil {
		return err
	}
	if !o.HasSide(sr.Side) {
		email := sr.RequesterEmail
		if email == "" {
			// Requester offboarded meanwhile — provision under a stable
			// org-scoped address instead.
			email = "org+" + o.ID + "@" + o.Domain
		}
		entityID, err := s.provisionEntity(email, sr.Side)
		if err != nil {
			return fmt.Errorf("provision %s: %w", sr.Side, err)
		}
		if err := s.orgs.SetSideEntity(ctx, o.ID, sr.Side, entityID); err != nil {
			return err
		}
	}
	return s.orgs.DecideSideRequest(ctx, requestID, reviewerID, "approved")
}

func (s *Service) RejectOrgSide(ctx context.Context, requestID, reviewerID string) error {
	return s.orgs.DecideSideRequest(ctx, requestID, reviewerID, "rejected")
}

// Reject marks a request rejected; the row is kept for audit.
func (s *Service) Reject(ctx context.Context, userID, reviewerID string) error {
	u, err := s.repo.GetByID(ctx, userID)
	if err != nil {
		return err
	}
	if u.Status != model.StatusPending {
		return fmt.Errorf("account is not pending review (status: %s)", u.Status)
	}
	return s.repo.UpdateStatusAndEntity(ctx, userID, model.StatusRejected, nil, nil, reviewerID)
}

// ListByStatus and ListAll expose the repository queries for the admin pages.
func (s *Service) ListByStatus(ctx context.Context, status model.UserStatus) ([]model.User, error) {
	return s.repo.ListByStatus(ctx, status)
}

func (s *Service) ListAll(ctx context.Context) ([]model.User, error) {
	return s.repo.ListAll(ctx)
}

// provisionEntity creates an advertiser or publisher on the core API using the
// email-based login endpoints which auto-create entities if new.
func (s *Service) provisionEntity(email string, role model.Role) (string, error) {
	var endpoint string

	if role == model.RoleAdvertiser {
		endpoint = s.coreAPIURL + "/v1/login"
	} else if role == model.RolePublisher {
		endpoint = s.coreAPIURL + "/v1/publisher-login"
	} else {
		return "", fmt.Errorf("unsupported role: %s", role)
	}

	body, _ := json.Marshal(map[string]string{"email": email})
	resp, err := http.Post(endpoint, "application/json", bytes.NewReader(body))
	if err != nil {
		return "", fmt.Errorf("core API call failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		return "", fmt.Errorf("core API returned %d", resp.StatusCode)
	}

	var result struct {
		AdvertiserID string `json:"advertiserId"`
		PublisherID  string `json:"publisherId"`
		Name         string `json:"name"`
		IsNew        bool   `json:"isNew"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return "", fmt.Errorf("parse core API response: %w", err)
	}

	if result.AdvertiserID != "" {
		return result.AdvertiserID, nil
	}
	if result.PublisherID != "" {
		return result.PublisherID, nil
	}
	return "", fmt.Errorf("no entity ID in core API response")
}

func (s *Service) Authenticate(ctx context.Context, email, password string) (*model.User, error) {
	u, err := s.repo.GetByEmail(ctx, email)
	if err != nil {
		return nil, fmt.Errorf("invalid credentials")
	}

	// Passkey-only users have no hash; pending/rejected accounts can't sign in.
	if u.PasswordHash == "" || u.Status != model.StatusActive {
		return nil, fmt.Errorf("invalid credentials")
	}

	if err := bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password)); err != nil {
		return nil, fmt.Errorf("invalid credentials")
	}
	return u, nil
}

func (s *Service) GetByID(ctx context.Context, id string) (*model.User, error) {
	return s.repo.GetByID(ctx, id)
}

func (s *Service) GetByEmail(ctx context.Context, email string) (*model.User, error) {
	return s.repo.GetByEmail(ctx, email)
}

func (s *Service) Update(ctx context.Context, u *model.User) error {
	return s.repo.Update(ctx, u)
}

// Delete removes a user row entirely (org member offboarding). Passkeys,
// membership, and recovery tokens go with it via ON DELETE CASCADE; live
// JWTs die on the next render's DB re-check.
func (s *Service) Delete(ctx context.Context, id string) error {
	return s.repo.Delete(ctx, id)
}

// CreateAdmin adds another platform operator (no org, no entity, no cap) —
// minted by an existing admin, activated by the invitee registering a
// passkey through their one-time link. The operator role is deliberately
// not reachable from any self-service flow.
func (s *Service) CreateAdmin(ctx context.Context, u *model.User) error {
	u.Role = model.RoleAdmin
	u.Status = model.StatusActive
	return s.repo.Create(ctx, u)
}

// InviteMember creates an invited member's account and their org membership
// atomically. No operator approval and no entity provisioning: the org
// already owns its entities and the org admin vouches for the invitee. The
// invitee can't sign in until they register a passkey via the invite link.
// side seeds their landing side (the inviter's active side).
func (s *Service) InviteMember(ctx context.Context, u *model.User, orgID, invitedBy string, side model.Role) error {
	tx, err := s.repo.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	u.Role = model.RoleUser
	u.Status = model.StatusActive
	if err := s.repo.CreateTx(ctx, tx, u); err != nil {
		return err
	}
	if err := s.orgs.AddMemberTx(ctx, tx, orgID, u.ID, model.OrgRoleMember, &invitedBy, side); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
