//go:build !integration

package pgtest

// Docker-free build: the database-backed tests compile but skip, so the
// ordinary `go test ./...` (and `make check`) stays fast and needs no
// container runtime. The real one is pgtest.go.

import (
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

func Pool(t *testing.T, prefix string) *pgxpool.Pool {
	t.Helper()
	t.Skip("database-backed test: run with -tags=integration (see CONTRIBUTING.md)")
	return nil
}
