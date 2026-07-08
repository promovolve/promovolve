package handler

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/hanishi/promovolve/platform/internal/model"
)

// passkeyRow is one credential on /account/passkeys.
type passkeyRow struct {
	ID       string
	Name     string
	Created  string
	LastUsed string
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
	rows := make([]passkeyRow, 0, len(stored))
	for _, sc := range stored {
		row := passkeyRow{ID: sc.ID, Name: sc.Name, Created: sc.CreatedAt.Format("2006-01-02"), LastUsed: "never"}
		if sc.LastUsedAt != nil {
			row.LastUsed = sc.LastUsedAt.Format("2006-01-02 15:04")
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
