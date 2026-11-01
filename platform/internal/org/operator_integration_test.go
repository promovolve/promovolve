//go:build integration

package org

import (
	"context"
	"errors"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/hanishi/promovolve/platform/internal/model"
	"github.com/hanishi/promovolve/platform/internal/pgtest"
)

func TestMain(m *testing.M) { pgtest.Main(m) }

func addUser(t *testing.T, pool *pgxpool.Pool, email string, role model.Role) string {
	t.Helper()
	var id string
	if err := pool.QueryRow(context.Background(),
		`INSERT INTO platform_users (email, role) VALUES ($1, $2) RETURNING id`, email, role).Scan(&id); err != nil {
		t.Fatalf("insert user %s: %v", email, err)
	}
	return id
}

// Operators are implicit admins of the org for their own email domain: no
// org_members row, so they never need (or can't get) an invite.
func TestSessionMembershipOperators(t *testing.T) {
	ctx := context.Background()
	pool := pgtest.Pool(t, "org")
	r := NewRepository(pool)

	acme, err := r.Create(ctx, "acme.com", "Acme", "")
	if err != nil {
		t.Fatal(err)
	}
	op := addUser(t, pool, "op@acme.com", model.RoleAdmin)
	opElsewhere := addUser(t, pool, "op@other.com", model.RoleAdmin)
	stranger := addUser(t, pool, "nobody@acme.com", model.RoleUser)
	member := addUser(t, pool, "member@acme.com", model.RoleUser)
	if err := r.AddMember(ctx, acme.ID, member, model.OrgRoleMember, nil, model.RoleAdvertiser); err != nil {
		t.Fatal(err)
	}

	o, m, err := r.SessionMembership(ctx, op)
	if err != nil || o.ID != acme.ID || m.OrgRole != model.OrgRoleAdmin {
		t.Errorf("operator at the org's domain: got %v %v %v, want admin of acme", o, m, err)
	}
	if _, _, err := r.SessionMembership(ctx, opElsewhere); !errors.Is(err, ErrNotFound) {
		t.Errorf("operator with no org at their domain: err = %v, want ErrNotFound", err)
	}
	if _, _, err := r.SessionMembership(ctx, stranger); !errors.Is(err, ErrNotFound) {
		t.Errorf("non-operator without a membership: err = %v, want ErrNotFound", err)
	}
	if _, m, err := r.SessionMembership(ctx, member); err != nil || m.OrgRole != model.OrgRoleMember {
		t.Errorf("stored member: got %v %v, want their stored role", m, err)
	}

	ops, err := r.OperatorMembers(ctx, "acme.com")
	if err != nil || len(ops) != 1 || ops[0].Email != "op@acme.com" || !ops[0].Operator {
		t.Errorf("OperatorMembers(acme.com) = %+v, %v; want just op@acme.com", ops, err)
	}
}
