package config

import (
	"log/slog"
	"os"
	"strings"
	"time"
)

type Config struct {
	ListenAddr      string
	DatabaseURL     string
	CoreAPIURL      string
	BannerScriptURL string
	JWTSecret       string
	JWTExpiry       time.Duration
	LogLevel        slog.Level
	// AllowedOrigin restricts CORS to a single trusted origin (e.g.
	// "https://app.promovolve.com"). When empty, CORS falls back to the
	// permissive "*" used for local development. Set this in any deployment
	// that serves authenticated, account-scoped data.
	AllowedOrigin string
	// RPID is the WebAuthn Relying Party ID — the domain passkeys are bound
	// to. Changing it invalidates every registered passkey, so treat it as
	// permanent once real credentials exist.
	RPID string
	// RPOrigins are the browser origins allowed to complete WebAuthn
	// ceremonies (comma-separated in RP_ORIGINS).
	RPOrigins []string
	// DevAuth re-enables the legacy password login/register endpoints for
	// local development so DB wipes don't require a passkey ceremony.
	// Requires the exact value DEV_AUTH=true; never set it in deployments.
	DevAuth bool
	// InternalAPIKey is sent as X-Internal-Key on calls to the core's
	// /v1/internal endpoints (billing settlement). Must match the core's
	// INTERNAL_API_KEY; empty means the core runs ungated (dev).
	InternalAPIKey string
}

func Load() Config {
	cfg := Config{
		ListenAddr:      envOr("LISTEN_ADDR", ":9090"),
		DatabaseURL:     envOr("DATABASE_URL", "postgres://promovolve:promovolve@localhost:5432/promovolve?sslmode=disable"),
		CoreAPIURL:      envOr("CORE_API_URL", "http://localhost:8080"),
		BannerScriptURL: envOr("BANNER_SCRIPT_URL", ""),
		JWTSecret:       envOr("JWT_SECRET", "change-me-in-production"),
		JWTExpiry:       parseDuration(envOr("JWT_EXPIRY", "24h")),
		LogLevel:        parseLogLevel(envOr("LOG_LEVEL", "info")),
		AllowedOrigin:   envOr("ALLOWED_ORIGIN", ""),
		RPID:            envOr("RP_ID", "localhost"),
		RPOrigins:       splitCSV(envOr("RP_ORIGINS", "http://localhost:9091")),
		DevAuth:         os.Getenv("DEV_AUTH") == "true",
		InternalAPIKey:  envOr("INTERNAL_API_KEY", ""),
	}
	if cfg.DevAuth {
		slog.Warn("DEV_AUTH enabled — password auth endpoints are active; never use in production")
	}
	return cfg
}

func splitCSV(s string) []string {
	var out []string
	for part := range strings.SplitSeq(s, ",") {
		if p := strings.TrimSpace(part); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func parseDuration(s string) time.Duration {
	d, err := time.ParseDuration(s)
	if err != nil {
		return 24 * time.Hour
	}
	return d
}

func parseLogLevel(s string) slog.Level {
	switch s {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}
