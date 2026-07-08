// Package setup owns the one-time platform initialization: the first-run
// gate that redirects everything to /setup until an admin account exists,
// and the transaction that creates that admin.
package setup

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"sync/atomic"

	"github.com/go-webauthn/webauthn/webauthn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/hanishi/promovolve/platform/internal/billing"
	"github.com/hanishi/promovolve/platform/internal/model"
	"github.com/hanishi/promovolve/platform/internal/passkey"
	"github.com/hanishi/promovolve/platform/internal/user"
)

var ErrAlreadyInitialized = errors.New("platform is already initialized")

// advisoryLockKey serializes concurrent setup attempts. Arbitrary constant,
// scoped to this database.
const advisoryLockKey = 0x70726f6d6f // "promo"

type Service struct {
	pool        *pgxpool.Pool
	userRepo    *user.Repository
	passkeyRepo *passkey.Repository

	// initialized flips true permanently once an admin exists; after that the
	// gate costs nothing per request.
	initialized atomic.Bool
}

func NewService(pool *pgxpool.Pool, userRepo *user.Repository, passkeyRepo *passkey.Repository) *Service {
	return &Service{pool: pool, userRepo: userRepo, passkeyRepo: passkeyRepo}
}

// Initialized reports whether an admin account exists, caching a true result.
func (s *Service) Initialized(ctx context.Context) bool {
	if s.initialized.Load() {
		return true
	}
	var exists bool
	if err := s.pool.QueryRow(ctx,
		`SELECT EXISTS (SELECT 1 FROM platform_users WHERE role = 'admin')`,
	).Scan(&exists); err != nil {
		// Fail open to the normal app rather than trapping everyone on /setup
		// during a transient DB error; the login path still requires auth.
		return true
	}
	if exists {
		s.initialized.Store(true)
	}
	return exists
}

// CreateAdmin creates the one admin account, its passkey (nil for the
// DEV_AUTH password variant), and the initial platform economics — margin
// and minimum payout floor — in a single transaction guarded against
// concurrent setup attempts.
func (s *Service) CreateAdmin(ctx context.Context, admin *model.User, cred *webauthn.Credential, credName string, marginBps int, payoutFloorMicros int64) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock($1)`, advisoryLockKey); err != nil {
		return err
	}

	var count int
	if err := tx.QueryRow(ctx, `SELECT count(*) FROM platform_users WHERE role = 'admin'`).Scan(&count); err != nil {
		return err
	}
	if count > 0 {
		return ErrAlreadyInitialized
	}

	admin.Role = model.RoleAdmin
	admin.Status = model.StatusActive
	if err := s.userRepo.CreateTx(ctx, tx, admin); err != nil {
		return err
	}
	if cred != nil {
		if err := s.passkeyRepo.CreateCredentialTx(ctx, tx, admin.ID, credName, cred); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO platform_margin_history (margin_bps, created_by) VALUES ($1, $2)`,
		marginBps, admin.ID,
	); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO platform_settings (key, value, updated_by) VALUES ($1, $2, $3)
		ON CONFLICT (key) DO UPDATE SET value = $2, updated_by = $3, updated_at = NOW()`,
		billing.PayoutFloorKey, strconv.FormatInt(payoutFloorMicros, 10), admin.ID,
	); err != nil {
		return err
	}

	if err := tx.Commit(ctx); err != nil {
		return err
	}
	s.initialized.Store(true)
	return nil
}

// Gate wraps the whole mux: until an admin exists, every request except the
// setup flow itself (and static assets/health) is redirected to /setup.
// Once initialized, GET /setup is handled by the setup page handler, which
// redirects to /login.
func (s *Service) Gate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.Initialized(r.Context()) || setupExempt(r.URL.Path) {
			next.ServeHTTP(w, r)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/api/") {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusServiceUnavailable)
			w.Write([]byte(`{"error":"platform not initialized"}`))
			return
		}
		http.Redirect(w, r, "/setup", http.StatusSeeOther)
	})
}

func setupExempt(path string) bool {
	return path == "/setup" ||
		path == "/setup/dev" ||
		strings.HasPrefix(path, "/webauthn/setup/") ||
		strings.HasPrefix(path, "/static/") ||
		path == "/health"
}
