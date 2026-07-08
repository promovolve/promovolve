package auth

import (
	"context"
	"encoding/json"
	"net/http"

	"github.com/hanishi/promovolve/platform/internal/model"
)

// UserService defines what the auth handler needs from the user layer.
type UserService interface {
	Register(ctx context.Context, email, password, displayName string, role model.Role) (*model.User, error)
	Authenticate(ctx context.Context, email, password string) (*model.User, error)
	GetByID(ctx context.Context, id string) (*model.User, error)
}

type Handler struct {
	userSvc UserService
	jwtSvc  *JWTService
}

func NewHandler(userSvc UserService, jwtSvc *JWTService) *Handler {
	return &Handler{userSvc: userSvc, jwtSvc: jwtSvc}
}

type registerRequest struct {
	Email       string     `json:"email"`
	Password    string     `json:"password"`
	DisplayName string     `json:"displayName"`
	Role        model.Role `json:"role"`
}

type loginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type tokenResponse struct {
	Token string      `json:"token"`
	User  *model.User `json:"user"`
}

func (h *Handler) Register(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if req.Email == "" || req.Password == "" || req.Role == "" {
		writeError(w, http.StatusBadRequest, "email, password, and role are required")
		return
	}

	if req.Role != model.RoleAdvertiser && req.Role != model.RolePublisher {
		writeError(w, http.StatusBadRequest, "role must be 'advertiser' or 'publisher'")
		return
	}

	u, err := h.userSvc.Register(r.Context(), req.Email, req.Password, req.DisplayName, req.Role)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	token, err := h.jwtSvc.Issue(u)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to issue token")
		return
	}

	writeJSON(w, http.StatusCreated, tokenResponse{Token: token, User: u})
}

func (h *Handler) Login(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	u, err := h.userSvc.Authenticate(r.Context(), req.Email, req.Password)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}

	token, err := h.jwtSvc.Issue(u)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to issue token")
		return
	}

	writeJSON(w, http.StatusOK, tokenResponse{Token: token, User: u})
}

func (h *Handler) Refresh(w http.ResponseWriter, r *http.Request) {
	claims, ok := ClaimsFromContext(r.Context())
	if !ok {
		tokenStr := ExtractToken(r)
		if tokenStr == "" {
			writeError(w, http.StatusUnauthorized, "missing token")
			return
		}
		var err error
		claims, err = h.jwtSvc.Validate(tokenStr)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "invalid token")
			return
		}
	}

	u, err := h.userSvc.GetByID(r.Context(), claims.UserID)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "user not found")
		return
	}

	token, err := h.jwtSvc.Issue(u)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to issue token")
		return
	}

	writeJSON(w, http.StatusOK, tokenResponse{Token: token, User: u})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
