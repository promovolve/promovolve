package middleware

import (
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/hanishi/promovolve/platform/internal/auth"
)

// Chain applies middleware in order: first listed wraps outermost.
func Chain(middlewares ...func(http.Handler) http.Handler) func(http.Handler) http.Handler {
	return func(final http.Handler) http.Handler {
		for i := len(middlewares) - 1; i >= 0; i-- {
			final = middlewares[i](final)
		}
		return final
	}
}

// Logger logs request method, path, status, and duration.
func Logger(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			sw := &statusWriter{ResponseWriter: w, status: 200}
			next.ServeHTTP(sw, r)
			logger.Info("request",
				"method", r.Method,
				"path", r.URL.Path,
				"status", sw.status,
				"duration", time.Since(start).String(),
			)
		})
	}
}

// Auth validates JWT or API key from the Authorization header.
func Auth(jwtSvc *auth.JWTService) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var tokenStr string

			// Try Authorization header first
			header := r.Header.Get("Authorization")
			if strings.HasPrefix(header, "Bearer ") {
				tokenStr = header[7:]
			}

			// Fall back to query param (for SSE — EventSource doesn't support headers)
			if tokenStr == "" {
				tokenStr = r.URL.Query().Get("token")
			}

			if tokenStr == "" {
				http.Error(w, `{"error":"missing authorization"}`, http.StatusUnauthorized)
				return
			}

			claims, err := jwtSvc.Validate(tokenStr)
			if err != nil {
				http.Error(w, `{"error":"invalid token"}`, http.StatusUnauthorized)
				return
			}
			// Admin view-as sessions are strictly read-only on every surface,
			// including this JSON API — the token must never mutate as the
			// viewed user.
			if claims.ActorID != "" && r.Method != http.MethodGet && r.Method != http.MethodHead {
				http.Error(w, `{"error":"view-as sessions are read-only"}`, http.StatusForbidden)
				return
			}
			ctx := auth.ContextWithClaims(r.Context(), claims)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// CORS sets CORS and baseline security headers. allowedOrigin restricts the
// Access-Control-Allow-Origin to a single trusted origin; when empty it falls
// back to the permissive "*" for local development. Don't serve authenticated,
// account-scoped data with a wildcard origin in production — set ALLOWED_ORIGIN.
func CORS(allowedOrigin string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if allowedOrigin != "" {
				// Echo the configured origin only when the request actually
				// comes from it; pair with Vary so caches don't cross origins.
				if origin := r.Header.Get("Origin"); origin == allowedOrigin {
					w.Header().Set("Access-Control-Allow-Origin", allowedOrigin)
					w.Header().Set("Access-Control-Allow-Credentials", "true")
				}
				w.Header().Add("Vary", "Origin")
			} else {
				w.Header().Set("Access-Control-Allow-Origin", "*")
			}
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

			// Baseline security headers (apply to every response).
			w.Header().Set("X-Frame-Options", "DENY")
			w.Header().Set("X-Content-Type-Options", "nosniff")
			w.Header().Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")

			if r.Method == "OPTIONS" {
				w.WriteHeader(http.StatusNoContent)
				return
			}

			next.ServeHTTP(w, r)
		})
	}
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	w.status = code
	w.ResponseWriter.WriteHeader(code)
}
