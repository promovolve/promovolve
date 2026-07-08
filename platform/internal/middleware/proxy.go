package middleware

import (
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"

	"github.com/hanishi/promovolve/platform/internal/auth"
	"github.com/hanishi/promovolve/platform/internal/model"
)

// NewCoreProxy creates a reverse proxy to the Promovolve core API.
// It strips the /api/v1/core prefix and rewrites /me/ paths to the user's
// actual advertiser or publisher ID from JWT claims.
//
// Rewrites:
//
//	/api/v1/core/v1/advertisers/me/...  → /v1/advertisers/{advertiserId}/...
//	/api/v1/core/v1/publishers/me/...   → /v1/publishers/{publisherId}/...
//	/api/v1/core/...                    → /...  (passthrough)
func NewCoreProxy(coreURL string, jwtSvc *auth.JWTService) http.Handler {
	target, _ := url.Parse(coreURL)

	proxy := &httputil.ReverseProxy{
		FlushInterval: 100 * time.Millisecond, // Enable streaming for SSE
		Director: func(req *http.Request) {
			// Strip the /api/v1/core prefix
			path := strings.TrimPrefix(req.URL.Path, "/api/v1/core")
			if path == "" {
				path = "/"
			}

			// Rewrite /me/ to the user's actual entity ID
			claims, hasClaims := auth.ClaimsFromContext(req.Context())
			if hasClaims {
				path = rewriteMePath(path, claims)
			}

			req.URL.Scheme = target.Scheme
			req.URL.Host = target.Host
			req.URL.Path = path

			// Forward user identity as headers to core API
			if hasClaims {
				req.Header.Set("X-User-ID", claims.UserID)
				req.Header.Set("X-User-Email", claims.Email)
				req.Header.Set("X-User-Role", string(claims.Role))
			}

			// Remove the original Authorization header (core API doesn't need JWT)
			req.Header.Del("Authorization")
		},
	}

	// IDOR guard: the core API trusts the {advertiserId}/{publisherId} path
	// segment as the authenticated identity. The Director above only rewrites
	// the "/me" alias — an explicitly-numbered path (e.g.
	// /v1/advertisers/{someoneElse}/...) passes straight through. Reject any
	// request whose advertiser/publisher segment names an identity other than
	// the caller's, so this proxy can't be used to read or mutate another
	// tenant's data.
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		claims, ok := auth.ClaimsFromContext(r.Context())
		if ok {
			path := strings.TrimPrefix(r.URL.Path, "/api/v1/core")
			if id, scoped := scopedEntityID(path, "advertisers"); scoped &&
				id != "me" && id != claims.AdvertiserID {
				http.Error(w, `{"error":"forbidden"}`, http.StatusForbidden)
				return
			}
			if id, scoped := scopedEntityID(path, "publishers"); scoped &&
				id != "me" && id != claims.PublisherID {
				http.Error(w, `{"error":"forbidden"}`, http.StatusForbidden)
				return
			}
		}
		proxy.ServeHTTP(w, r)
	})
}

// scopedEntityID returns the id segment that follows /v1/{kind}/ (optionally
// nested under /v1/dashboard/), and whether the path is scoped to one. It
// covers both /v1/{kind}/{id} and /v1/dashboard/{kind}/{id}.
func scopedEntityID(path, kind string) (string, bool) {
	for _, prefix := range []string{"/v1/" + kind + "/", "/v1/dashboard/" + kind + "/"} {
		if strings.HasPrefix(path, prefix) {
			rest := strings.TrimPrefix(path, prefix)
			id := rest
			if i := strings.IndexByte(rest, '/'); i >= 0 {
				id = rest[:i]
			}
			if id != "" {
				return id, true
			}
		}
	}
	return "", false
}

// rewriteMePath replaces /me/ in advertiser and publisher paths with the actual entity ID.
func rewriteMePath(path string, claims *model.Claims) string {
	// /v1/advertisers/me/... → /v1/advertisers/{advertiserId}/...
	if strings.HasPrefix(path, "/v1/advertisers/me") {
		if claims.AdvertiserID != "" {
			return strings.Replace(path, "/v1/advertisers/me", "/v1/advertisers/"+claims.AdvertiserID, 1)
		}
	}

	// /v1/publishers/me/... → /v1/publishers/{publisherId}/...
	if strings.HasPrefix(path, "/v1/publishers/me") {
		if claims.PublisherID != "" {
			return strings.Replace(path, "/v1/publishers/me", "/v1/publishers/"+claims.PublisherID, 1)
		}
	}

	return path
}
