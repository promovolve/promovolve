// Package audit is the actor-attributed trail behind org membership and the
// admin view-as feature: WHO (which human, not which account) did WHAT. Rows
// deliberately have no FK to platform_users — the trail outlives offboarded
// members.
package audit

import (
	"context"
	"log/slog"

	"github.com/jackc/pgx/v5/pgxpool"
)

type Repository struct {
	pool *pgxpool.Pool
}

func NewRepository(pool *pgxpool.Pool) *Repository {
	return &Repository{pool: pool}
}

// Log writes one audit line. Best-effort by design: an audit insert failure
// is logged but never blocks the user action itself.
func (r *Repository) Log(ctx context.Context, actorID, actorEmail, orgID, action, target, detail string) {
	var org *string
	if orgID != "" {
		org = &orgID
	}
	var actor *string
	if actorID != "" {
		actor = &actorID
	}
	_, err := r.pool.Exec(ctx, `
		INSERT INTO audit_log (actor_id, actor_email, org_id, action, target, detail)
		VALUES ($1, $2, $3, $4, $5, $6)`,
		actor, actorEmail, org, action, target, detail)
	if err != nil {
		slog.Error("audit log write failed", "action", action, "actor", actorEmail, "error", err)
	}
}
