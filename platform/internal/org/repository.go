// Package org owns organizations: one per email domain, holding the core
// advertiser/publisher entities that all members of the org share. See
// docs/design + the org membership handlers for the model.
package org

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/hanishi/promovolve/platform/internal/model"
)

var ErrNotFound = errors.New("org not found")

type Repository struct {
	pool *pgxpool.Pool
}

func NewRepository(pool *pgxpool.Pool) *Repository {
	return &Repository{pool: pool}
}

const maxMembersKey = "org_max_members"

// MaxMembers is the operator-set member cap per organization
// (/admin/settings), defaulting to model.DefaultMaxOrgMembers when the
// setting was never written or is unreadable. Read per check — invite
// and approval are rare paths, no cache needed.
func (r *Repository) MaxMembers(ctx context.Context) int {
	var raw string
	if err := r.pool.QueryRow(ctx,
		`SELECT value FROM platform_settings WHERE key = $1`, maxMembersKey,
	).Scan(&raw); err == nil {
		if n, convErr := strconv.Atoi(raw); convErr == nil && n >= 1 {
			return n
		}
	}
	return model.DefaultMaxOrgMembers
}

func (r *Repository) SetMaxMembers(ctx context.Context, n int, updatedBy string) error {
	if n < 1 {
		return fmt.Errorf("org: member cap must be at least 1")
	}
	_, err := r.pool.Exec(ctx, `
		INSERT INTO platform_settings (key, value, updated_by, updated_at)
		VALUES ($1, $2, $3, NOW())
		ON CONFLICT (key) DO UPDATE SET value = $2, updated_by = $3, updated_at = NOW()`,
		maxMembersKey, strconv.Itoa(n), updatedBy,
	)
	return err
}

// DomainOf extracts the lowercase email domain ("" when malformed).
func DomainOf(email string) string {
	at := strings.LastIndexByte(email, '@')
	if at < 0 || at == len(email)-1 {
		return ""
	}
	return strings.ToLower(email[at+1:])
}

const orgColumns = `id, domain, name, advertiser_id, publisher_id, created_at, updated_at`

func scanOrg(row pgx.Row) (*model.Org, error) {
	o := &model.Org{}
	err := row.Scan(&o.ID, &o.Domain, &o.Name, &o.AdvertiserID, &o.PublisherID, &o.CreatedAt, &o.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	return o, err
}

func (r *Repository) GetByID(ctx context.Context, id string) (*model.Org, error) {
	return scanOrg(r.pool.QueryRow(ctx, `SELECT `+orgColumns+` FROM orgs WHERE id = $1`, id))
}

func (r *Repository) GetByDomain(ctx context.Context, domain string) (*model.Org, error) {
	return scanOrg(r.pool.QueryRow(ctx, `SELECT `+orgColumns+` FROM orgs WHERE domain = $1`, strings.ToLower(domain)))
}

// Create inserts a new org; the UNIQUE(domain) constraint is the one-org-per-
// domain rule, so a concurrent duplicate surfaces as an error here.
func (r *Repository) Create(ctx context.Context, domain, name string) (*model.Org, error) {
	return scanOrg(r.pool.QueryRow(ctx, `
		INSERT INTO orgs (domain, name) VALUES ($1, $2)
		RETURNING `+orgColumns, strings.ToLower(domain), name))
}

// SetSideEntity records a provisioned core entity on the org. It only fills
// an empty side — an org's entity id is permanent once set (billing history
// hangs off it), so a re-approve can never silently repoint it.
func (r *Repository) SetSideEntity(ctx context.Context, orgID string, side model.Role, entityID string) error {
	column := "advertiser_id"
	if side == model.RolePublisher {
		column = "publisher_id"
	}
	tag, err := r.pool.Exec(ctx, `
		UPDATE orgs SET `+column+` = $2, updated_at = NOW()
		WHERE id = $1 AND `+column+` IS NULL`, orgID, entityID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return errors.New("org side already provisioned")
	}
	return nil
}

// ForUser resolves a user's org and membership in one query; ErrNotFound for
// users outside any org (the platform operator).
func (r *Repository) ForUser(ctx context.Context, userID string) (*model.Org, *model.OrgMembership, error) {
	o := &model.Org{}
	m := &model.OrgMembership{}
	err := r.pool.QueryRow(ctx, `
		SELECT o.id, o.domain, o.name, o.advertiser_id, o.publisher_id, o.created_at, o.updated_at,
		       m.org_id, m.user_id, m.org_role, COALESCE(m.preferred_side, ''), m.invited_by, m.created_at
		FROM org_members m JOIN orgs o ON o.id = m.org_id
		WHERE m.user_id = $1`, userID,
	).Scan(&o.ID, &o.Domain, &o.Name, &o.AdvertiserID, &o.PublisherID, &o.CreatedAt, &o.UpdatedAt,
		&m.OrgID, &m.UserID, &m.OrgRole, &m.PreferredSide, &m.InvitedBy, &m.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil, ErrNotFound
	}
	return o, m, err
}

// SetPreferredSide remembers the member's last-used side (their next fresh
// login lands there).
func (r *Repository) SetPreferredSide(ctx context.Context, orgID, userID string, side model.Role) error {
	_, err := r.pool.Exec(ctx, `
		UPDATE org_members SET preferred_side = $3 WHERE org_id = $1 AND user_id = $2`,
		orgID, userID, side)
	return err
}

// AddMember attaches a user to an org, seeding their landing side.
// ON CONFLICT keeps approval retryable.
func (r *Repository) AddMember(ctx context.Context, orgID, userID string, role model.OrgRole, invitedBy *string, side model.Role) error {
	_, err := r.pool.Exec(ctx, `
		INSERT INTO org_members (org_id, user_id, org_role, invited_by, preferred_side)
		VALUES ($1, $2, $3, $4, $5) ON CONFLICT DO NOTHING`,
		orgID, userID, role, invitedBy, side)
	return err
}

// AddMemberTx is AddMember inside an existing transaction (invite flow: the
// user row and membership commit atomically).
func (r *Repository) AddMemberTx(ctx context.Context, tx pgx.Tx, orgID, userID string, role model.OrgRole, invitedBy *string, side model.Role) error {
	_, err := tx.Exec(ctx, `
		INSERT INTO org_members (org_id, user_id, org_role, invited_by, preferred_side)
		VALUES ($1, $2, $3, $4, $5) ON CONFLICT DO NOTHING`,
		orgID, userID, role, invitedBy, side)
	return err
}

// MemberRow is one row on the org Team page (membership + user identity).
type MemberRow struct {
	UserID      string
	Email       string
	DisplayName string
	OrgRole     model.OrgRole
	Passkeys    int
	JoinedAt    string
}

func (r *Repository) Members(ctx context.Context, orgID string) ([]MemberRow, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT u.id, u.email, u.display_name, m.org_role,
		       (SELECT COUNT(*) FROM webauthn_credentials c WHERE c.user_id = u.id),
		       to_char(m.created_at, 'YYYY-MM-DD')
		FROM org_members m JOIN platform_users u ON u.id = m.user_id
		WHERE m.org_id = $1 ORDER BY m.created_at`, orgID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MemberRow
	for rows.Next() {
		var m MemberRow
		if err := rows.Scan(&m.UserID, &m.Email, &m.DisplayName, &m.OrgRole, &m.Passkeys, &m.JoinedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (r *Repository) CountMembers(ctx context.Context, orgID string) (int, error) {
	var n int
	err := r.pool.QueryRow(ctx, `SELECT COUNT(*) FROM org_members WHERE org_id = $1`, orgID).Scan(&n)
	return n, err
}

func (r *Repository) CountAdmins(ctx context.Context, orgID string) (int, error) {
	var n int
	err := r.pool.QueryRow(ctx, `SELECT COUNT(*) FROM org_members WHERE org_id = $1 AND org_role = 'admin'`, orgID).Scan(&n)
	return n, err
}

// SetMemberRole promotes/demotes. Demoting the LAST admin is refused
// atomically here (not just in the handler) so two concurrent demotions can't
// leave the org adminless.
func (r *Repository) SetMemberRole(ctx context.Context, orgID, userID string, role model.OrgRole) error {
	var tag string
	if role == model.OrgRoleMember {
		tag = `UPDATE org_members SET org_role = 'member'
			WHERE org_id = $1 AND user_id = $2
			  AND (SELECT COUNT(*) FROM org_members x
			       WHERE x.org_id = $1 AND x.org_role = 'admin' AND x.user_id <> $2) >= 1`
	} else {
		tag = `UPDATE org_members SET org_role = 'admin' WHERE org_id = $1 AND user_id = $2`
	}
	res, err := r.pool.Exec(ctx, tag, orgID, userID)
	if err != nil {
		return err
	}
	if res.RowsAffected() == 0 {
		return errors.New("an org must keep at least one admin")
	}
	return nil
}

// MembershipSummaries maps user id → (domain, role, org sides) for the
// admin users page.
type MembershipSummary struct {
	OrgID   string
	Domain  string
	OrgRole model.OrgRole
	// Which sides the ORG holds — the meaningful "role" of an org user
	// (the user row's requested-at-signup role is a vestige).
	HasAdvertiser bool
	HasPublisher  bool
}

func (r *Repository) MembershipSummaries(ctx context.Context) (map[string]MembershipSummary, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT m.user_id, o.id, o.domain, m.org_role,
		       o.advertiser_id IS NOT NULL, o.publisher_id IS NOT NULL
		FROM org_members m JOIN orgs o ON o.id = m.org_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]MembershipSummary{}
	for rows.Next() {
		var userID string
		var s MembershipSummary
		if err := rows.Scan(&userID, &s.OrgID, &s.Domain, &s.OrgRole, &s.HasAdvertiser, &s.HasPublisher); err != nil {
			return nil, err
		}
		out[userID] = s
	}
	return out, rows.Err()
}

// DeleteOrg removes an org row (memberships and side requests cascade).
// Only for operator teardown of an org whose last member is being deleted:
// it frees the domain for a fresh signup. Billing ledger rows survive
// (append-only); the entity registry stops listing the org's wallets, which
// is correct — there's no customer left to top up.
func (r *Repository) DeleteOrg(ctx context.Context, id string) error {
	_, err := r.pool.Exec(ctx, `DELETE FROM orgs WHERE id = $1`, id)
	return err
}

// EntitySide is one provisioned side of an org — the authoritative "who owns
// this core entity" record (a side added via side-request exists ONLY here,
// never on a platform_users row).
type EntitySide struct {
	EntityID string
	Side     model.Role
	Domain   string
	Name     string
}

// SearchEntitySides is the SQL-side account search behind the admin billing
// comboboxes: matches org name, domain, entity id, or any member's email;
// side narrows to one owner type ("" = both). Filtering and LIMIT happen in
// Postgres — nothing is scanned app-side.
func (r *Repository) SearchEntitySides(ctx context.Context, q string, side model.Role, limit int) ([]EntitySide, error) {
	rows, err := r.pool.Query(ctx, `
		WITH sides AS (
			SELECT advertiser_id AS entity_id, 'advertiser' AS side, domain, name, id FROM orgs WHERE advertiser_id IS NOT NULL
			UNION ALL
			SELECT publisher_id, 'publisher', domain, name, id FROM orgs WHERE publisher_id IS NOT NULL
		)
		SELECT entity_id, side, domain, name FROM sides
		WHERE ($1 = '' OR side = $1)
		  AND ($2 = ''
		       OR domain ILIKE '%' || $2 || '%'
		       OR name ILIKE '%' || $2 || '%'
		       OR entity_id ILIKE '%' || $2 || '%'
		       OR EXISTS (SELECT 1 FROM org_members m JOIN platform_users u ON u.id = m.user_id
		                  WHERE m.org_id = sides.id AND u.email ILIKE '%' || $2 || '%'))
		ORDER BY name, domain, side
		LIMIT $3`, string(side), q, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []EntitySide
	for rows.Next() {
		var e EntitySide
		if err := rows.Scan(&e.EntityID, &e.Side, &e.Domain, &e.Name); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

func (r *Repository) EntitySides(ctx context.Context) ([]EntitySide, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT advertiser_id, 'advertiser', domain, name FROM orgs WHERE advertiser_id IS NOT NULL
		UNION ALL
		SELECT publisher_id, 'publisher', domain, name FROM orgs WHERE publisher_id IS NOT NULL`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []EntitySide
	for rows.Next() {
		var e EntitySide
		if err := rows.Scan(&e.EntityID, &e.Side, &e.Domain, &e.Name); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// --- Org side requests -------------------------------------------------------

func (r *Repository) CreateSideRequest(ctx context.Context, orgID string, side model.Role, requestedBy, message string) error {
	_, err := r.pool.Exec(ctx, `
		INSERT INTO org_side_requests (org_id, side, requested_by, message)
		VALUES ($1, $2, $3, $4)`, orgID, side, requestedBy, message)
	if err != nil && strings.Contains(err.Error(), "duplicate key") {
		return errors.New("a request for this side is already pending review")
	}
	return err
}

func (r *Repository) HasPendingSideRequest(ctx context.Context, orgID string, side model.Role) (bool, error) {
	var n int
	err := r.pool.QueryRow(ctx, `
		SELECT COUNT(*) FROM org_side_requests
		WHERE org_id = $1 AND side = $2 AND status = 'pending'`, orgID, side).Scan(&n)
	return n > 0, err
}

func (r *Repository) GetSideRequest(ctx context.Context, id string) (*model.OrgSideRequest, error) {
	sr := &model.OrgSideRequest{}
	err := r.pool.QueryRow(ctx, `
		SELECT s.id, s.org_id, s.side, s.requested_by, s.message, s.status, s.created_at,
		       o.domain, o.name, COALESCE(u.email, '')
		FROM org_side_requests s
		JOIN orgs o ON o.id = s.org_id
		LEFT JOIN platform_users u ON u.id = s.requested_by
		WHERE s.id = $1`, id,
	).Scan(&sr.ID, &sr.OrgID, &sr.Side, &sr.RequestedBy, &sr.Message, &sr.Status, &sr.CreatedAt,
		&sr.OrgDomain, &sr.OrgName, &sr.RequesterEmail)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	return sr, err
}

func (r *Repository) ListPendingSideRequests(ctx context.Context) ([]model.OrgSideRequest, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT s.id, s.org_id, s.side, s.requested_by, s.message, s.status, s.created_at,
		       o.domain, o.name, COALESCE(u.email, '')
		FROM org_side_requests s
		JOIN orgs o ON o.id = s.org_id
		LEFT JOIN platform_users u ON u.id = s.requested_by
		WHERE s.status = 'pending' ORDER BY s.created_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.OrgSideRequest
	for rows.Next() {
		sr := model.OrgSideRequest{}
		if err := rows.Scan(&sr.ID, &sr.OrgID, &sr.Side, &sr.RequestedBy, &sr.Message, &sr.Status, &sr.CreatedAt,
			&sr.OrgDomain, &sr.OrgName, &sr.RequesterEmail); err != nil {
			return nil, err
		}
		out = append(out, sr)
	}
	return out, rows.Err()
}

func (r *Repository) DecideSideRequest(ctx context.Context, id, reviewerID, status string) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE org_side_requests
		SET status = $3, reviewed_by = $2, reviewed_at = NOW()
		WHERE id = $1 AND status = 'pending'`, id, reviewerID, status)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return errors.New("request is not pending review")
	}
	return nil
}
