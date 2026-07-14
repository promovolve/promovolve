package handler

import (
	"bytes"
	"encoding/json"
	"errors"
	"log/slog"
	"math"
	"net/http"
	"net/mail"
	"strconv"
	"strings"

	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"

	"github.com/hanishi/promovolve/platform/internal/model"
	"github.com/hanishi/promovolve/platform/internal/setup"
)

// setupPayload rides the ceremony session between setup begin and finish so
// the admin row is only created once the passkey attestation verifies.
type setupPayload struct {
	User              *model.User
	MarginBps         int
	PayoutFloorMicros int64
	DefaultTimezone   string
}

// SetupPage renders the one-time installation wizard. Inert once an admin
// exists.
func (h *Handler) SetupPage(w http.ResponseWriter, r *http.Request) {
	if h.setupSvc.Initialized(r.Context()) {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	h.render(w, "setup.html", pageData{
		Title:     "Set up PromoVolve",
		DevAuth:   h.devAuth,
		Timezones: preferenceTimezones,
	})
}

type setupBeginRequest struct {
	Email         string `json:"email"`
	DisplayName   string `json:"displayName"`
	MarginPercent string `json:"marginPercent"`
	PayoutFloor   string `json:"payoutFloor"`
	Timezone      string `json:"timezone"`
}

func (req *setupBeginRequest) validate() (*model.User, int, int64, string, error) {
	email := strings.TrimSpace(strings.ToLower(req.Email))
	if _, err := mail.ParseAddress(email); err != nil {
		return nil, 0, 0, "", errors.New("a valid email address is required")
	}
	if strings.TrimSpace(req.DisplayName) == "" {
		return nil, 0, 0, "", errors.New("a display name is required")
	}
	bps, err := parseMarginPercent(req.MarginPercent)
	if err != nil {
		return nil, 0, 0, "", err
	}
	floor, err := parseDollars(req.PayoutFloor)
	if err != nil {
		return nil, 0, 0, "", errors.New("the minimum payout must be a positive dollar amount")
	}
	tz := strings.TrimSpace(req.Timezone)
	if !validTimezone(tz) {
		return nil, 0, 0, "", errors.New("the default timezone must be an IANA zone like Asia/Tokyo (leave blank for UTC)")
	}
	return &model.User{
		ID:          uuid.New().String(),
		Email:       email,
		DisplayName: strings.TrimSpace(req.DisplayName),
		Role:        model.RoleAdmin,
		Status:      model.StatusActive,
	}, bps, floor, tz, nil
}

// parseMarginPercent converts "15" / "15.25" to basis points, rejecting
// values outside [0, 100).
func parseMarginPercent(s string) (int, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, errors.New("a platform margin is required (use 0 for none)")
	}
	pct, err := strconv.ParseFloat(s, 64)
	if err != nil || math.IsNaN(pct) || pct < 0 || pct >= 100 {
		return 0, errors.New("margin must be a percentage between 0 and 99.99")
	}
	return int(math.Round(pct * 100)), nil
}

func (h *Handler) SetupBegin(w http.ResponseWriter, r *http.Request) {
	if h.setupSvc.Initialized(r.Context()) {
		writeJSONError(w, http.StatusConflict, "platform is already initialized")
		return
	}
	var req setupBeginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	admin, bps, floor, tz, err := req.validate()
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, err.Error())
		return
	}

	creation, token, err := h.passkeySvc.BeginRegistration(r.Context(), admin,
		setupPayload{User: admin, MarginBps: bps, PayoutFloorMicros: floor, DefaultTimezone: tz})
	if err != nil {
		slog.Error("setup begin failed", "error", err)
		writeJSONError(w, http.StatusInternalServerError, "could not start passkey registration")
		return
	}
	writeJSONResp(w, http.StatusOK, map[string]any{"sessionToken": token, "options": creation})
}

func (h *Handler) SetupFinish(w http.ResponseWriter, r *http.Request) {
	env, err := decodeEnvelope(r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	session, payload, err := h.passkeySvc.TakeSession(env.SessionToken)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "setup session expired — try again")
		return
	}
	sp, ok := payload.(setupPayload)
	if !ok {
		writeJSONError(w, http.StatusBadRequest, "invalid setup session")
		return
	}

	cred, err := h.passkeySvc.FinishRegistration(sp.User, session, bytes.NewReader(env.Credential))
	if err != nil {
		slog.Warn("setup passkey verification failed", "error", err)
		writeJSONError(w, http.StatusBadRequest, "passkey registration failed")
		return
	}

	if err := h.setupSvc.CreateAdmin(r.Context(), sp.User, cred, "Setup passkey", sp.MarginBps, sp.PayoutFloorMicros, sp.DefaultTimezone); err != nil {
		if errors.Is(err, setup.ErrAlreadyInitialized) {
			writeJSONError(w, http.StatusConflict, "platform is already initialized")
			return
		}
		slog.Error("setup failed", "error", err)
		writeJSONError(w, http.StatusInternalServerError, "could not create the admin account")
		return
	}

	token, err := h.jwtSvc.Issue(sp.User)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "failed to issue token")
		return
	}
	h.setSessionCookie(w, token)
	writeJSONResp(w, http.StatusOK, map[string]string{"redirect": homeFor(model.RoleAdmin)})
}

// SetupDev is the DEV_AUTH-only password variant so dev DB wipes don't need
// a browser passkey ceremony. Same guarded transaction underneath.
func (h *Handler) SetupDev(w http.ResponseWriter, r *http.Request) {
	if !h.devAuth {
		http.NotFound(w, r)
		return
	}
	r.ParseForm()
	req := setupBeginRequest{
		Email:         r.FormValue("email"),
		DisplayName:   r.FormValue("displayName"),
		MarginPercent: r.FormValue("marginPercent"),
		PayoutFloor:   r.FormValue("payoutFloor"),
		Timezone:      r.FormValue("timezone"),
	}
	renderErr := func(msg string) {
		h.render(w, "setup.html", pageData{Title: "Set up PromoVolve", DevAuth: h.devAuth, Error: msg, Timezones: preferenceTimezones})
	}

	admin, bps, floor, tz, err := req.validate()
	if err != nil {
		renderErr(err.Error())
		return
	}
	password := r.FormValue("password")
	if password == "" {
		renderErr("a password is required")
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		renderErr("could not hash password")
		return
	}
	admin.PasswordHash = string(hash)

	if err := h.setupSvc.CreateAdmin(r.Context(), admin, nil, "", bps, floor, tz); err != nil {
		if errors.Is(err, setup.ErrAlreadyInitialized) {
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}
		slog.Error("dev setup failed", "error", err)
		renderErr("could not create the admin account")
		return
	}

	token, err := h.jwtSvc.Issue(admin)
	if err != nil {
		renderErr("failed to issue token")
		return
	}
	h.setSessionCookie(w, token)
	http.Redirect(w, r, homeFor(model.RoleAdmin), http.StatusSeeOther)
}
