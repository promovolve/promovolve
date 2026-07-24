package siterequest

import (
	"context"
	"errors"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/hanishi/promovolve/platform/internal/model"
)

const (
	StatusPending  = "pending"
	StatusApproved = "approved"
	StatusRejected = "rejected"
)

var ErrNotFound = errors.New("site request not found")
var ErrDuplicatePending = errors.New("a request for this site is already awaiting approval")
var ErrSiteAlreadyOwned = errors.New("the requesting publisher already owns this site")
var ErrSiteTaken = errors.New("this site is already registered to another publisher")

type Repository struct {
	pool *pgxpool.Pool
}

func NewRepository(pool *pgxpool.Pool) *Repository {
	return &Repository{pool: pool}
}

const requestColumns = `id, publisher_id, requested_by, site_id, domain, page_url,
	status, reject_reason, reviewed_by, reviewed_at, created_at, updated_at`

func scanRequest(row pgx.Row) (*model.SiteRequest, error) {
	sr := &model.SiteRequest{}
	err := row.Scan(&sr.ID, &sr.PublisherID, &sr.RequestedBy, &sr.SiteID, &sr.Domain, &sr.PageURL,
		&sr.Status, &sr.RejectReason, &sr.ReviewedBy, &sr.ReviewedAt, &sr.CreatedAt, &sr.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	return sr, err
}

// Create inserts a pending row. Prior rejected rows for the same
// (publisher_id, site_id) are removed in the same transaction so a re-request
// supersedes the old denied card instead of sitting next to it. A concurrent
// pending row trips the partial unique index → ErrDuplicatePending.
func (r *Repository) Create(ctx context.Context, req *model.SiteRequest) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx, `
		DELETE FROM site_requests
		WHERE publisher_id = $1 AND site_id = $2 AND status = 'rejected'`,
		req.PublisherID, req.SiteID,
	); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO site_requests (publisher_id, requested_by, site_id, domain, page_url)
		VALUES ($1, $2, $3, $4, $5)`,
		req.PublisherID, req.RequestedBy, req.SiteID, req.Domain, req.PageURL,
	); err != nil {
		if strings.Contains(err.Error(), "duplicate key") {
			return ErrDuplicatePending
		}
		return err
	}
	return tx.Commit(ctx)
}

// LiveSiteOwner looks the site up in the publisher_sites projection (the
// live-site mirror the core API maintains on create): a hit by either the
// sanitized site_id or the raw host means the site is already registered
// and a new request for it can never be approved.
func (r *Repository) LiveSiteOwner(ctx context.Context, siteID, host string) (string, bool, error) {
	var owner string
	err := r.pool.QueryRow(ctx, `
		SELECT publisher_id FROM publisher_sites
		WHERE site_id = $1 OR host = $2
		LIMIT 1`, siteID, host).Scan(&owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return owner, true, nil
}

func (r *Repository) GetByID(ctx context.Context, id string) (*model.SiteRequest, error) {
	return scanRequest(r.pool.QueryRow(ctx, `
		SELECT `+requestColumns+` FROM site_requests WHERE id = $1`, id))
}

// ListForPublisher returns the rows that render as cards on the publisher's
// Sites page: pending + rejected only (an approved request is represented by
// the live core site), newest first.
func (r *Repository) ListForPublisher(ctx context.Context, publisherID string) ([]model.SiteRequest, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT `+requestColumns+` FROM site_requests
		WHERE publisher_id = $1 AND status IN ('pending', 'rejected')
		ORDER BY created_at DESC`, publisherID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var reqs []model.SiteRequest
	for rows.Next() {
		sr, err := scanRequest(rows)
		if err != nil {
			return nil, err
		}
		reqs = append(reqs, *sr)
	}
	return reqs, rows.Err()
}

// PendingRow joins requester identity for the admin queue display.
type PendingRow struct {
	model.SiteRequest
	RequesterEmail   string
	RequesterCompany string
}

func (r *Repository) ListPending(ctx context.Context) ([]PendingRow, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT sr.id, sr.publisher_id, sr.requested_by, sr.site_id, sr.domain, sr.page_url,
		       sr.status, sr.reject_reason, sr.reviewed_by, sr.reviewed_at, sr.created_at, sr.updated_at,
		       COALESCE(u.email, ''), COALESCE(u.company_name, '')
		FROM site_requests sr
		LEFT JOIN platform_users u ON u.id = sr.requested_by
		WHERE sr.status = 'pending'
		ORDER BY sr.created_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var pending []PendingRow
	for rows.Next() {
		var p PendingRow
		if err := rows.Scan(&p.ID, &p.PublisherID, &p.RequestedBy, &p.SiteID, &p.Domain, &p.PageURL,
			&p.Status, &p.RejectReason, &p.ReviewedBy, &p.ReviewedAt, &p.CreatedAt, &p.UpdatedAt,
			&p.RequesterEmail, &p.RequesterCompany); err != nil {
			return nil, err
		}
		pending = append(pending, p)
	}
	return pending, rows.Err()
}

// UpdateDecision flips a pending row to approved/rejected and records the
// reviewer. The status='pending' guard makes concurrent double-decisions race
// safely: the loser affects zero rows and gets ErrNotFound.
func (r *Repository) UpdateDecision(ctx context.Context, id, status, rejectReason, reviewerID string) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE site_requests
		SET status = $2, reject_reason = $3, reviewed_by = $4, reviewed_at = NOW(), updated_at = NOW()
		WHERE id = $1 AND status = 'pending'`,
		id, status, rejectReason, reviewerID,
	)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// Delete removes a publisher's own pending or rejected card. The predicate
// enforces ownership and keeps approved rows as audit history in one
// statement; a non-matching id (foreign, approved, gone) → ErrNotFound.
func (r *Repository) Delete(ctx context.Context, id, publisherID string) error {
	tag, err := r.pool.Exec(ctx, `
		DELETE FROM site_requests
		WHERE id = $1 AND publisher_id = $2 AND status IN ('pending', 'rejected')`,
		id, publisherID,
	)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}
