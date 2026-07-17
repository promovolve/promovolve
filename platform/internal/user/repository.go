package user

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/hanishi/promovolve/platform/internal/model"
)

var ErrNotFound = errors.New("user not found")
var ErrDuplicateEmail = errors.New("email already registered")

type Repository struct {
	pool *pgxpool.Pool
}

func NewRepository(pool *pgxpool.Pool) *Repository {
	return &Repository{pool: pool}
}

const userColumns = `id, email, COALESCE(password_hash, ''), role, display_name, timezone, locale, advertiser_id, publisher_id,
	COALESCE(requested_side, ''), status, company_name, website_url, contact_name, request_message,
	reviewed_by, reviewed_at, created_at, updated_at`

func scanUser(row pgx.Row) (*model.User, error) {
	u := &model.User{}
	err := row.Scan(&u.ID, &u.Email, &u.PasswordHash, &u.Role, &u.DisplayName, &u.Timezone, &u.Locale, &u.AdvertiserID,
		&u.PublisherID,
		&u.RequestedSide, &u.Status, &u.CompanyName, &u.WebsiteURL, &u.ContactName, &u.RequestMessage,
		&u.ReviewedBy, &u.ReviewedAt, &u.CreatedAt, &u.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	return u, err
}

func (r *Repository) Create(ctx context.Context, u *model.User) error {
	return r.create(ctx, r.pool, u)
}

// CreateTx inserts the user inside an existing transaction (used by the
// request-account flow so the user row and its passkey commit atomically).
func (r *Repository) CreateTx(ctx context.Context, tx pgx.Tx, u *model.User) error {
	return r.create(ctx, tx, u)
}

type execer interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

// Begin starts a transaction on the underlying pool (request-account commits
// the user row and its passkey atomically).
func (r *Repository) Begin(ctx context.Context) (pgx.Tx, error) {
	return r.pool.Begin(ctx)
}

func (r *Repository) create(ctx context.Context, db execer, u *model.User) error {
	var hash *string
	if u.PasswordHash != "" {
		hash = &u.PasswordHash
	}
	// requested_side is nullable with a CHECK — empty must insert as NULL.
	var requestedSide *string
	if u.RequestedSide != "" {
		s := string(u.RequestedSide)
		requestedSide = &s
	}
	if u.Status == "" {
		u.Status = model.StatusActive
	}
	_, err := db.Exec(ctx, `
		INSERT INTO platform_users (id, email, password_hash, role, display_name, advertiser_id, publisher_id,
			requested_side, status, company_name, website_url, contact_name, request_message)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)`,
		u.ID, u.Email, hash, u.Role, u.DisplayName, u.AdvertiserID, u.PublisherID,
		requestedSide, u.Status, u.CompanyName, u.WebsiteURL, u.ContactName, u.RequestMessage,
	)
	if err != nil && isDuplicateKey(err) {
		return ErrDuplicateEmail
	}
	return err
}

func (r *Repository) GetByID(ctx context.Context, id string) (*model.User, error) {
	return scanUser(r.pool.QueryRow(ctx, `
		SELECT `+userColumns+` FROM platform_users WHERE id = $1`, id))
}

func (r *Repository) GetByEmail(ctx context.Context, email string) (*model.User, error) {
	return scanUser(r.pool.QueryRow(ctx, `
		SELECT `+userColumns+` FROM platform_users WHERE email = $1`, email))
}

func (r *Repository) ListByStatus(ctx context.Context, status model.UserStatus) ([]model.User, error) {
	return r.list(ctx, `SELECT `+userColumns+` FROM platform_users WHERE status = $1 ORDER BY created_at`, status)
}

func (r *Repository) ListAll(ctx context.Context) ([]model.User, error) {
	return r.list(ctx, `SELECT `+userColumns+` FROM platform_users ORDER BY created_at`)
}

func (r *Repository) list(ctx context.Context, sql string, args ...any) ([]model.User, error) {
	rows, err := r.pool.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var users []model.User
	for rows.Next() {
		u, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		users = append(users, *u)
	}
	return users, rows.Err()
}

// UpdateStatusAndEntity records an approval/rejection decision. Entity IDs
// are only written when non-nil so a rejection doesn't clear anything.
func (r *Repository) UpdateStatusAndEntity(ctx context.Context, userID string, status model.UserStatus, advertiserID, publisherID *string, reviewerID string) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE platform_users
		SET status = $2,
		    advertiser_id = COALESCE($3, advertiser_id),
		    publisher_id  = COALESCE($4, publisher_id),
		    reviewed_by = $5, reviewed_at = NOW(), updated_at = NOW()
		WHERE id = $1`,
		userID, status, advertiserID, publisherID, reviewerID,
	)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *Repository) Delete(ctx context.Context, id string) error {
	tag, err := r.pool.Exec(ctx, `DELETE FROM platform_users WHERE id = $1`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *Repository) Update(ctx context.Context, u *model.User) error {
	_, err := r.pool.Exec(ctx, `
		UPDATE platform_users SET display_name = $2, timezone = $3, locale = $4, updated_at = NOW() WHERE id = $1`,
		u.ID, u.DisplayName, u.Timezone, u.Locale,
	)
	return err
}

func isDuplicateKey(err error) bool {
	return err != nil && contains(err.Error(), "duplicate key")
}

func contains(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}
