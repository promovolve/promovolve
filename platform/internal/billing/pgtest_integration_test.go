//go:build integration

package billing

// Database-backed half of the billing suite (GH #22): real Postgres via
// internal/pgtest. Without the tag these tests skip (pgtest_default_test.go).

import (
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/hanishi/promovolve/platform/internal/pgtest"
)

func TestMain(m *testing.M) { pgtest.Main(m) }

func testPool(t *testing.T) *pgxpool.Pool { return pgtest.Pool(t, "billing") }
