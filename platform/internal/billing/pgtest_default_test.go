//go:build !integration

package billing

// Docker-free build: the database-backed tests compile but skip. The real
// pool is in pgtest_integration_test.go.

import (
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/hanishi/promovolve/platform/internal/pgtest"
)

func testPool(t *testing.T) *pgxpool.Pool { return pgtest.Pool(t, "billing") }
