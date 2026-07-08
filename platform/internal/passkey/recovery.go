package passkey

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

var ErrRecoveryTokenInvalid = errors.New("recovery token invalid, expired, or already used")

// recoveryTTL bounds how long a minted link stays redeemable. Links are
// delivered manually (no email infra), so give the admin a comfortable
// window without leaving credentials mintable forever.
const recoveryTTL = 72 * time.Hour

// CreateRecoveryToken mints a one-time passkey re-registration link for a
// locked-out user. Only the sha256 of the token is stored; the raw value is
// shown once to the admin for manual delivery (same pattern as API keys).
// createdBy may be empty (break-glass CLI mint — no admin session exists).
func (r *Repository) CreateRecoveryToken(ctx context.Context, userID, createdBy string) (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	raw := hex.EncodeToString(buf)
	hash := sha256.Sum256([]byte(raw))

	var by *string
	if createdBy != "" {
		by = &createdBy
	}
	_, err := r.pool.Exec(ctx, `
		INSERT INTO recovery_tokens (id, user_id, token_hash, created_by, expires_at)
		VALUES ($1, $2, $3, $4, $5)`,
		uuid.New().String(), userID, hex.EncodeToString(hash[:]), by, time.Now().Add(recoveryTTL),
	)
	if err != nil {
		return "", err
	}
	return raw, nil
}

// UserIDForRecoveryToken resolves a raw token to its user if the token is
// unexpired and unused.
func (r *Repository) UserIDForRecoveryToken(ctx context.Context, raw string) (string, error) {
	hash := sha256.Sum256([]byte(raw))
	var userID string
	err := r.pool.QueryRow(ctx, `
		SELECT user_id FROM recovery_tokens
		WHERE token_hash = $1 AND used_at IS NULL AND expires_at > NOW()`,
		hex.EncodeToString(hash[:]),
	).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrRecoveryTokenInvalid
	}
	return userID, err
}

// ConsumeRecoveryTokenTx marks a token used inside the transaction that also
// inserts the replacement credential — redeeming is atomic with recovery.
func (r *Repository) ConsumeRecoveryTokenTx(ctx context.Context, tx pgx.Tx, raw string) error {
	hash := sha256.Sum256([]byte(raw))
	tag, err := tx.Exec(ctx, `
		UPDATE recovery_tokens SET used_at = NOW()
		WHERE token_hash = $1 AND used_at IS NULL AND expires_at > NOW()`,
		hex.EncodeToString(hash[:]),
	)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrRecoveryTokenInvalid
	}
	return nil
}

// BeginTx exposes a pool transaction for callers composing credential writes
// with token consumption.
func (r *Repository) BeginTx(ctx context.Context) (pgx.Tx, error) {
	return r.pool.Begin(ctx)
}
