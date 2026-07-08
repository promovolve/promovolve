package passkey

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/go-webauthn/webauthn/webauthn"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

var ErrNotFound = errors.New("credential not found")

// StoredCredential pairs a webauthn.Credential with its platform metadata.
type StoredCredential struct {
	ID         string
	UserID     string
	Name       string
	CreatedAt  time.Time
	LastUsedAt *time.Time
	Credential webauthn.Credential
}

type Repository struct {
	pool *pgxpool.Pool
}

func NewRepository(pool *pgxpool.Pool) *Repository {
	return &Repository{pool: pool}
}

// execer is satisfied by both *pgxpool.Pool and pgx.Tx so credential inserts
// can join an existing transaction (request-account commits user + credential
// atomically).
type execer interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

func (r *Repository) CreateCredential(ctx context.Context, userID, name string, cred *webauthn.Credential) error {
	return createCredential(ctx, r.pool, userID, name, cred)
}

func (r *Repository) CreateCredentialTx(ctx context.Context, tx pgx.Tx, userID, name string, cred *webauthn.Credential) error {
	return createCredential(ctx, tx, userID, name, cred)
}

func createCredential(ctx context.Context, db execer, userID, name string, cred *webauthn.Credential) error {
	blob, err := json.Marshal(cred)
	if err != nil {
		return err
	}
	_, err = db.Exec(ctx, `
		INSERT INTO webauthn_credentials (id, user_id, credential_id, credential, name)
		VALUES ($1, $2, $3, $4, $5)`,
		uuid.New().String(), userID, cred.ID, blob, name,
	)
	return err
}

func (r *Repository) ListByUser(ctx context.Context, userID string) ([]StoredCredential, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT id, user_id, name, created_at, last_used_at, credential FROM webauthn_credentials
		WHERE user_id = $1 ORDER BY created_at`, userID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var creds []StoredCredential
	for rows.Next() {
		var sc StoredCredential
		var blob []byte
		if err := rows.Scan(&sc.ID, &sc.UserID, &sc.Name, &sc.CreatedAt, &sc.LastUsedAt, &blob); err != nil {
			return nil, err
		}
		if err := json.Unmarshal(blob, &sc.Credential); err != nil {
			return nil, err
		}
		creds = append(creds, sc)
	}
	return creds, rows.Err()
}

// UserIDByCredentialID resolves which user owns a credential (assertion path).
func (r *Repository) UserIDByCredentialID(ctx context.Context, credentialID []byte) (string, error) {
	var userID string
	err := r.pool.QueryRow(ctx, `
		SELECT user_id FROM webauthn_credentials WHERE credential_id = $1`, credentialID,
	).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	return userID, err
}

// UpdateCredential persists post-login mutations (sign count, clone flags)
// and stamps last_used_at.
func (r *Repository) UpdateCredential(ctx context.Context, cred *webauthn.Credential) error {
	blob, err := json.Marshal(cred)
	if err != nil {
		return err
	}
	_, err = r.pool.Exec(ctx, `
		UPDATE webauthn_credentials SET credential = $2, last_used_at = NOW()
		WHERE credential_id = $1`, cred.ID, blob,
	)
	return err
}

func (r *Repository) DeleteCredential(ctx context.Context, id, userID string) error {
	tag, err := r.pool.Exec(ctx, `
		DELETE FROM webauthn_credentials WHERE id = $1 AND user_id = $2`, id, userID,
	)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// CountByUser reports how many passkeys a user has (admin users list).
func (r *Repository) CountByUser(ctx context.Context, userID string) (int, error) {
	var n int
	err := r.pool.QueryRow(ctx, `
		SELECT count(*) FROM webauthn_credentials WHERE user_id = $1`, userID,
	).Scan(&n)
	return n, err
}
