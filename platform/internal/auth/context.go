package auth

import (
	"context"
	"net/http"
	"strings"

	"github.com/hanishi/promovolve/platform/internal/model"
)

type contextKey string

const claimsKey contextKey = "claims"

func ContextWithClaims(ctx context.Context, claims *model.Claims) context.Context {
	return context.WithValue(ctx, claimsKey, claims)
}

func ClaimsFromContext(ctx context.Context) (*model.Claims, bool) {
	claims, ok := ctx.Value(claimsKey).(*model.Claims)
	return claims, ok
}

func ExtractToken(r *http.Request) string {
	header := r.Header.Get("Authorization")
	if strings.HasPrefix(header, "Bearer ") {
		return header[7:]
	}
	return ""
}
