//go:build integration

package billing

// Database-backed half of the billing suite (GH #22). These tests exercise
// real Postgres behavior — transactionality, idempotency keys, local-day
// settlement, holds and clawbacks — which no mock can stand in for, so they
// run against a real server:
//
//	go test -tags=integration ./internal/billing
//
// TestMain provisions ONE throwaway Postgres for the package run via
// Testcontainers; each test still gets its own schema. Set
// BILLING_TEST_DATABASE_URL to point at an existing server instead (the dev
// database is safe — nothing is created outside the per-test schema):
//
//	BILLING_TEST_DATABASE_URL='postgres://promovolve:promovolve@localhost:5432/promovolve?sslmode=disable' \
//	  go test -tags=integration ./internal/billing
//
// Without the tag, `go test` needs no Docker and these tests skip — see
// pgtest_default_test.go.

import (
	"context"
	"fmt"
	"io"
	"os"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/hanishi/promovolve/platform/internal/db"
)

// Pinned, and the same Postgres major the cluster runs (timescaledb
// 2.17.2-pg15). The migrations use no Timescale features, so plain Postgres
// is enough — but the major must match or a constraint or SQL dialect
// difference would only surface in production.
const postgresImage = "postgres:15.14-alpine"

// baseURL is the server every test connects to, set once by TestMain.
var baseURL string

// schemaSeq keeps schema names unique within the package run; the pid keeps
// them unique across concurrent runs against a shared external database.
var schemaSeq atomic.Int64

func TestMain(m *testing.M) {
	if url := os.Getenv("BILLING_TEST_DATABASE_URL"); url != "" {
		baseURL = url
		os.Exit(m.Run())
	}

	ctx := context.Background()
	container, err := postgres.Run(ctx, postgresImage,
		postgres.WithDatabase("promovolve_test"),
		postgres.WithUsername("promovolve"),
		postgres.WithPassword("promovolve"),
		// The server restarts once after initdb, so waiting for a single
		// "ready" line races the shutdown and yields a refused connection.
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).
				WithStartupTimeout(2*time.Minute)),
	)
	if err != nil {
		// A pull or startup failure must FAIL the job, not skip it — a
		// silently skipped money suite is the thing this issue removed.
		fmt.Fprintf(os.Stderr, "start postgres (%s): %v\n", postgresImage, err)
		dumpLogs(ctx, container)
		os.Exit(1)
	}

	baseURL, err = container.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		fmt.Fprintf(os.Stderr, "connection string: %v\n", err)
		dumpLogs(ctx, container)
		os.Exit(1)
	}

	code := m.Run()

	// Not deferred: os.Exit below would skip it.
	if err := testcontainers.TerminateContainer(container); err != nil {
		fmt.Fprintf(os.Stderr, "terminate postgres: %v\n", err)
	}
	os.Exit(code)
}

// dumpLogs prints whatever the container managed to emit before it failed;
// "could not start" alone is not enough to debug an image or initdb problem
// on a CI runner.
func dumpLogs(ctx context.Context, container *postgres.PostgresContainer) {
	if container == nil {
		return
	}
	logs, err := container.Logs(ctx)
	if err != nil {
		fmt.Fprintf(os.Stderr, "container logs unavailable: %v\n", err)
		return
	}
	defer logs.Close()
	fmt.Fprintln(os.Stderr, "--- postgres container logs ---")
	_, _ = io.Copy(os.Stderr, logs)
	fmt.Fprintln(os.Stderr, "--- end container logs ---")
}

// testPool gives the test its own schema on the shared server, runs the
// PRODUCTION migrations against it, and drops it afterwards. Migrating an
// empty schema each time is what makes these tests cover db.Migrate itself,
// not just the queries.
func testPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	ctx := context.Background()

	schema := fmt.Sprintf("billing_test_%d_%d", os.Getpid(), schemaSeq.Add(1))

	admin, err := pgxpool.New(ctx, baseURL)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	if _, err := admin.Exec(ctx, "DROP SCHEMA IF EXISTS "+schema+" CASCADE"); err != nil {
		t.Fatalf("drop stale schema: %v", err)
	}
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatalf("create schema: %v", err)
	}
	t.Cleanup(func() {
		admin.Exec(context.Background(), "DROP SCHEMA IF EXISTS "+schema+" CASCADE")
		admin.Close()
	})

	cfg, err := pgxpool.ParseConfig(baseURL)
	if err != nil {
		t.Fatalf("parse config: %v", err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("connect with search_path: %v", err)
	}
	t.Cleanup(pool.Close)

	if err := db.Migrate(pool); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	return pool
}
