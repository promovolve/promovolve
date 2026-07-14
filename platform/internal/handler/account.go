package handler

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/hanishi/promovolve/platform/internal/model"
)

// passkeyRow is one credential on /account/passkeys.
type passkeyRow struct {
	ID       string
	Name     string
	Created  string
	LastUsed string
}

// Curated IANA zones for the preferences dropdown — the full database is
// ~600 entries; these cover the audiences the platform serves. The user's
// current (possibly exotic) zone is always included by the handler.
var preferenceTimezones = []string{
	"UTC",
	"Asia/Tokyo", "Asia/Seoul", "Asia/Shanghai", "Asia/Singapore", "Asia/Kolkata", "Asia/Dubai",
	"Australia/Sydney", "Pacific/Auckland",
	"Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Madrid", "Europe/Amsterdam",
	"America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
	"America/Toronto", "America/Sao_Paulo",
}

// AccountPreferencesPage renders per-user preferences: display name,
// timezone (rendering only — stored data stays UTC), and, for dual-side
// orgs, which side a fresh login lands on. Email is deliberately absent:
// the address's domain binds the user to their org, so changing it would
// be an account migration, not a preference.
func (h *Handler) AccountPreferencesPage(w http.ResponseWriter, r *http.Request) {
	h.renderPreferences(w, r, "", r.URL.Query().Get("saved") == "1")
}

func (h *Handler) renderPreferences(w http.ResponseWriter, r *http.Request, errMsg string, saved bool) {
	user, _ := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	zones := preferenceTimezones
	if user.Timezone != "" {
		found := false
		for _, z := range zones {
			if z == user.Timezone {
				found = true
				break
			}
		}
		if !found {
			zones = append([]string{user.Timezone}, zones...)
		}
	}

	// Landing side: only meaningful for members of a dual-side org.
	landingSide := ""
	var landingSides []string
	if user.HasBothSides() {
		landingSides = []string{"advertiser", "publisher"}
		if _, m, err := h.orgRepo.ForUser(r.Context(), user.ID); err == nil && m.PreferredSide != "" {
			landingSide = string(m.PreferredSide)
		}
	}

	h.render(w, "account-preferences.html", pageData{
		Title:        "Preferences",
		Nav:          "preferences",
		User:         user,
		Error:        errMsg,
		Saved:        saved,
		Timezones:    zones,
		LandingSide:  landingSide,
		LandingSides: landingSides,
	})
}

// SavePreferences applies the form. Timezone must be a loadable IANA zone
// (or empty = UTC); landing side must be a side the org actually holds.
func (h *Handler) SavePreferences(w http.ResponseWriter, r *http.Request) {
	user, _ := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()

	displayName := strings.TrimSpace(r.FormValue("displayName"))
	if len(displayName) > 80 {
		h.renderPreferences(w, r, "display name is limited to 80 characters", false)
		return
	}

	tz := strings.TrimSpace(r.FormValue("timezone"))
	if tz != "" {
		if _, err := time.LoadLocation(tz); err != nil {
			h.renderPreferences(w, r, "unknown timezone: "+tz, false)
			return
		}
	}

	user.DisplayName = displayName
	user.Timezone = tz
	if err := h.userSvc.Update(r.Context(), user); err != nil {
		slog.Error("save preferences failed", "user", user.ID, "error", err)
		h.renderPreferences(w, r, "could not save preferences — try again", false)
		return
	}

	if side := r.FormValue("landingSide"); side != "" && user.HasBothSides() {
		role := model.Role(side)
		if user.Org != nil && user.Org.HasSide(role) {
			if err := h.orgRepo.SetPreferredSide(r.Context(), user.Org.ID, user.ID, role); err != nil {
				slog.Error("save landing side failed", "user", user.ID, "error", err)
			}
		}
	}

	http.Redirect(w, r, "/account/preferences?saved=1", http.StatusSeeOther)
}

// --- Lost-passkey recovery (admin-minted one-time links) --------------------

type recoverPayload struct {
	User  *model.User
	Token string
}

// RecoverPage renders the "register a new passkey" page behind a one-time
// token. Invalid/expired/used tokens all get the same generic error.
func (h *Handler) RecoverPage(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	if _, err := h.passkeySvc.Repo().UserIDForRecoveryToken(r.Context(), token); err != nil {
		h.render(w, "recover.html", pageData{Title: "Account recovery", Error: "This recovery link is invalid, expired, or already used."})
		return
	}
	h.render(w, "recover.html", pageData{Title: "Account recovery", RecoveryToken: token})
}

type recoverBeginRequest struct {
	Token string `json:"token"`
}

func (h *Handler) RecoverBegin(w http.ResponseWriter, r *http.Request) {
	var req recoverBeginRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	userID, err := h.passkeySvc.Repo().UserIDForRecoveryToken(r.Context(), req.Token)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "recovery link is invalid, expired, or already used")
		return
	}
	user, err := h.userSvc.GetByID(r.Context(), userID)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "recovery link is invalid, expired, or already used")
		return
	}

	creation, token, err := h.passkeySvc.BeginRegistration(r.Context(), user, recoverPayload{User: user, Token: req.Token})
	if err != nil {
		slog.Error("recover begin failed", "error", err)
		writeJSONError(w, http.StatusInternalServerError, "could not start passkey registration")
		return
	}
	writeJSONResp(w, http.StatusOK, map[string]any{"sessionToken": token, "options": creation})
}

func (h *Handler) RecoverFinish(w http.ResponseWriter, r *http.Request) {
	env, err := decodeEnvelope(r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	session, payload, err := h.passkeySvc.TakeSession(env.SessionToken)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "session expired — try again")
		return
	}
	rp, ok := payload.(recoverPayload)
	if !ok {
		writeJSONError(w, http.StatusBadRequest, "invalid session")
		return
	}

	cred, err := h.passkeySvc.FinishRegistration(rp.User, session, bytes.NewReader(env.Credential))
	if err != nil {
		slog.Warn("recover passkey verification failed", "error", err)
		writeJSONError(w, http.StatusBadRequest, "passkey registration failed")
		return
	}

	// Consume the token and store the credential atomically — a redeemed
	// link must never be reusable, and a consumed link must always have
	// produced a credential.
	repo := h.passkeySvc.Repo()
	tx, err := repo.BeginTx(r.Context())
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "could not complete recovery")
		return
	}
	defer tx.Rollback(r.Context())
	if err := repo.ConsumeRecoveryTokenTx(r.Context(), tx, rp.Token); err != nil {
		writeJSONError(w, http.StatusBadRequest, "recovery link is invalid, expired, or already used")
		return
	}
	if err := repo.CreateCredentialTx(r.Context(), tx, rp.User.ID, "Recovery passkey", cred); err != nil {
		slog.Error("recover credential insert failed", "error", err)
		writeJSONError(w, http.StatusInternalServerError, "could not complete recovery")
		return
	}
	if err := tx.Commit(r.Context()); err != nil {
		writeJSONError(w, http.StatusInternalServerError, "could not complete recovery")
		return
	}
	writeJSONResp(w, http.StatusOK, map[string]string{"redirect": "/login"})
}

// --- Self-service passkey management ----------------------------------------

type addPasskeyPayload struct {
	User *model.User
}

func (h *Handler) AccountPasskeysPage(w http.ResponseWriter, r *http.Request) {
	user, _ := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	stored, err := h.passkeySvc.Repo().ListByUser(r.Context(), user.ID)
	if err != nil {
		slog.Error("list passkeys failed", "error", err)
	}
	loc := user.Location()
	rows := make([]passkeyRow, 0, len(stored))
	for _, sc := range stored {
		row := passkeyRow{ID: sc.ID, Name: sc.Name, Created: sc.CreatedAt.In(loc).Format("2006-01-02"), LastUsed: "never"}
		if sc.LastUsedAt != nil {
			row.LastUsed = sc.LastUsedAt.In(loc).Format("2006-01-02 15:04")
		}
		rows = append(rows, row)
	}
	h.render(w, "account-passkeys.html", pageData{
		Title:    "Your passkeys",
		Nav:      "passkeys",
		User:     user,
		Passkeys: rows,
	})
}

func (h *Handler) AddPasskeyBegin(w http.ResponseWriter, r *http.Request) {
	user, _ := h.sessionUser(r)
	if user == nil {
		writeJSONError(w, http.StatusUnauthorized, "sign in first")
		return
	}
	creation, token, err := h.passkeySvc.BeginRegistration(r.Context(), user, addPasskeyPayload{User: user})
	if err != nil {
		slog.Error("add-passkey begin failed", "error", err)
		writeJSONError(w, http.StatusInternalServerError, "could not start passkey registration")
		return
	}
	writeJSONResp(w, http.StatusOK, map[string]any{"sessionToken": token, "options": creation})
}

func (h *Handler) AddPasskeyFinish(w http.ResponseWriter, r *http.Request) {
	user, _ := h.sessionUser(r)
	if user == nil {
		writeJSONError(w, http.StatusUnauthorized, "sign in first")
		return
	}
	env, err := decodeEnvelope(r)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	session, payload, err := h.passkeySvc.TakeSession(env.SessionToken)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "session expired — try again")
		return
	}
	ap, ok := payload.(addPasskeyPayload)
	if !ok || ap.User.ID != user.ID {
		writeJSONError(w, http.StatusBadRequest, "invalid session")
		return
	}

	cred, err := h.passkeySvc.FinishRegistration(user, session, bytes.NewReader(env.Credential))
	if err != nil {
		slog.Warn("add-passkey verification failed", "error", err)
		writeJSONError(w, http.StatusBadRequest, "passkey registration failed")
		return
	}
	if err := h.passkeySvc.Repo().CreateCredential(r.Context(), user.ID, "Added passkey", cred); err != nil {
		slog.Error("add-passkey insert failed", "error", err)
		writeJSONError(w, http.StatusInternalServerError, "could not save the passkey")
		return
	}
	writeJSONResp(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) DeletePasskey(w http.ResponseWriter, r *http.Request) {
	user, _ := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	credID := r.FormValue("credentialId")

	// Deleting the last passkey of a passkey-only account is a lockout.
	count, err := h.passkeySvc.Repo().CountByUser(r.Context(), user.ID)
	if err == nil && count <= 1 && user.PasswordHash == "" {
		http.Redirect(w, r, "/account/passkeys", http.StatusSeeOther)
		return
	}
	if err := h.passkeySvc.Repo().DeleteCredential(r.Context(), credID, user.ID); err != nil {
		slog.Warn("delete passkey failed", "error", err)
	}
	http.Redirect(w, r, "/account/passkeys", http.StatusSeeOther)
}

// decodeJSON decodes a request body into v.
func decodeJSON(r *http.Request, v any) error {
	return json.NewDecoder(r.Body).Decode(v)
}
