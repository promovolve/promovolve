// Package settings stores platform-wide configuration — today just the
// platform margin, the fee deducted from publisher earnings.
//
// The margin history is append-only and effective-dated: the current margin
// is the latest row with effective_from <= now(), so past earnings are never
// silently repriced by a settings change.
package settings

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type MarginEntry struct {
	MarginBps     int
	EffectiveFrom time.Time
	CreatedBy     *string
}

type Service struct {
	pool *pgxpool.Pool

	// The margin is read on every publisher page render; cache it briefly.
	// Single pod, and SetMargin invalidates, so 30 s staleness only ever
	// applies across replicas that don't exist.
	mu       sync.Mutex
	cached   int
	cachedAt time.Time
}

const cacheTTL = 30 * time.Second

func NewService(pool *pgxpool.Pool) *Service {
	return &Service{pool: pool}
}

// CurrentMarginBps returns the effective platform margin in basis points.
// A missing row (pre-setup database) degrades to 0 — plain gross display.
func (s *Service) CurrentMarginBps(ctx context.Context) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	if time.Since(s.cachedAt) < cacheTTL {
		return s.cached
	}

	var bps int
	err := s.pool.QueryRow(ctx, `
		SELECT margin_bps FROM platform_margin_history
		WHERE effective_from <= NOW()
		ORDER BY effective_from DESC LIMIT 1`,
	).Scan(&bps)
	if err != nil {
		if !errors.Is(err, pgx.ErrNoRows) {
			// Transient DB error: serve the stale value rather than flapping
			// the displayed fee to zero.
			return s.cached
		}
		bps = 0
	}
	s.cached = bps
	s.cachedAt = time.Now()
	return bps
}

// MarginBpsAt returns the margin effective at a point in time — the
// settlement job passes the end of the day being settled so the snapshot
// covers the whole day. Uncached: settlement runs a handful of times a day.
// A missing row (margin never configured) degrades to 0 like CurrentMarginBps.
func (s *Service) MarginBpsAt(ctx context.Context, at time.Time) (int, error) {
	var bps int
	err := s.pool.QueryRow(ctx, `
		SELECT margin_bps FROM platform_margin_history
		WHERE effective_from < $1
		ORDER BY effective_from DESC LIMIT 1`,
		at,
	).Scan(&bps)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, nil
	}
	return bps, err
}

func (s *Service) History(ctx context.Context) ([]MarginEntry, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT margin_bps, effective_from, created_by::text
		FROM platform_margin_history ORDER BY effective_from DESC`,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var entries []MarginEntry
	for rows.Next() {
		var e MarginEntry
		if err := rows.Scan(&e.MarginBps, &e.EffectiveFrom, &e.CreatedBy); err != nil {
			return nil, err
		}
		entries = append(entries, e)
	}
	return entries, rows.Err()
}

// SetMargin appends a new effective-dated margin row and invalidates the cache.
func (s *Service) SetMargin(ctx context.Context, bps int, createdBy string) error {
	if bps < 0 || bps >= 10000 {
		return errors.New("margin must be between 0 and 9999 basis points")
	}
	_, err := s.pool.Exec(ctx, `
		INSERT INTO platform_margin_history (margin_bps, created_by) VALUES ($1, $2)`,
		bps, createdBy,
	)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.cachedAt = time.Time{}
	s.mu.Unlock()
	return nil
}

// Net splits a gross amount into the publisher's net and the platform fee.
func Net(gross float64, bps int) (net, fee float64) {
	fee = gross * float64(bps) / 10000
	return gross - fee, fee
}
