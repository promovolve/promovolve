package handler

import (
	"bytes"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/mail"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/hanishi/promovolve/platform/internal/model"
	"github.com/hanishi/promovolve/platform/internal/org"
	"github.com/hanishi/promovolve/platform/internal/user"
)

// ceremonyEnvelope is the JSON shape static/passkey.js posts to every
// WebAuthn finish endpoint: the begin-issued session token plus the raw
// navigator.credentials result.
type ceremonyEnvelope struct {
	SessionToken string          `json:"sessionToken"`
	Credential   json.RawMessage `json:"credential"`
}

func decodeEnvelope(r *http.Request) (*ceremonyEnvelope, error) {
	var env ceremonyEnvelope
	if err := json.NewDecoder(r.Body).Decode(&env); err != nil {
		return nil, err
	}
	return &env, nil
}

func writeJSONResp(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeJSONError(w http.ResponseWriter, status int, msg string) {
	writeJSONResp(w, status, map[string]string{"error": msg})
}

// PasskeyLoginBegin issues assertion options. With an email in the body the
// ceremony is restricted to that account's credentials, so the browser only
// offers the matching passkey; otherwise (conditional UI / empty field) it's
// discoverable and the resident key's user handle picks the account.
// Unknown emails silently fall back to discoverable so the endpoint doesn't
// reveal which addresses have accounts.
func (h *Handler) PasskeyLoginBegin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email string `json:"email"`
	}
	json.NewDecoder(r.Body).Decode(&req)

	if email := strings.TrimSpace(strings.ToLower(req.Email)); email != "" {
		if u, err := h.userSvc.GetByEmail(r.Context(), email); err == nil {
			if assertion, token, err := h.passkeySvc.BeginLoginFor(r.Context(), u); err == nil {
				writeJSONResp(w, http.StatusOK, map[string]any{"sessionToken": token, "options": assertion})
				return
			}
		}
	}

	assertion, token, err := h.passkeySvc.BeginLogin()
	if err != nil {
		slog.Error("passkey login begin failed", "error", err)
		h.jsonErrorT(w, r, http.StatusInternalServerError, "could not start sign-in")
		return
	}
	writeJSONResp(w, http.StatusOK, map[string]any{"sessionToken": token, "options": assertion})
}

func (h *Handler) PasskeyLoginFinish(w http.ResponseWriter, r *http.Request) {
	env, err := decodeEnvelope(r)
	if err != nil {
		h.jsonErrorT(w, r, http.StatusBadRequest, "invalid request body")
		return
	}

	user, err := h.passkeySvc.FinishLogin(r.Context(), env.SessionToken, bytes.NewReader(env.Credential))
	if err != nil {
		slog.Warn("passkey login failed", "error", err)
		h.jsonErrorT(w, r, http.StatusUnauthorized, "sign-in failed")
		return
	}

	switch user.Status {
	case model.StatusActive:
		// fall through to issue the session
	case model.StatusPending:
		writeJSONResp(w, http.StatusOK, map[string]string{"status": "pending"})
		return
	default:
		// Rejected (or unknown) — indistinguishable from a failed assertion
		// on purpose.
		h.jsonErrorT(w, r, http.StatusUnauthorized, "sign-in failed")
		return
	}

	token, redirect, err := h.issueSessionFor(r.Context(), user)
	if err != nil {
		h.jsonErrorT(w, r, http.StatusInternalServerError, "failed to issue token")
		return
	}
	h.setSessionCookie(w, token)
	writeJSONResp(w, http.StatusOK, map[string]string{"redirect": redirect})
}

// homeFor is the post-login landing page per session role (a SIDE for org
// users, "admin" for operators). The bare "user" grant has no home — such a
// session shouldn't exist, so send it to login rather than looping through
// a side dashboard it can't pass the guard for.
func homeFor(role model.Role) string {
	switch role {
	case model.RolePublisher:
		return "/publisher/approval"
	case model.RoleAdvertiser:
		return "/advertiser/campaigns"
	case model.RoleAdmin:
		return "/admin/requests"
	default:
		return "/login"
	}
}

// --- Account requests -------------------------------------------------------

// requestPayload rides the ceremony session between request-account begin
// and finish.
type requestPayload struct {
	User *model.User
}

func (h *Handler) RequestAccountPage(w http.ResponseWriter, r *http.Request) {
	h.render(w, r, "request-account.html", pageData{Title: "Request access"})
}

type requestAccountRequest struct {
	Role        string `json:"role"`
	CompanyName string `json:"companyName"`
	WebsiteURL  string `json:"websiteUrl"`
	ContactName string `json:"contactName"`
	Email       string `json:"email"`
	Message     string `json:"message"`
	// Browser-detected IANA zone; seeds the org's account timezone at
	// approval (one-time — later changes are operator-only). Invalid or
	// missing values fall back to the platform default silently: the
	// requester never sees a timezone error for a hint they didn't type.
	Timezone string `json:"timezone"`
}

func (h *Handler) RequestAccountBegin(w http.ResponseWriter, r *http.Request) {
	var req requestAccountRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		h.jsonErrorT(w, r, http.StatusBadRequest, "invalid request body")
		return
	}

	role := model.Role(req.Role)
	if role != model.RoleAdvertiser && role != model.RolePublisher {
		h.jsonErrorT(w, r, http.StatusBadRequest, "choose publisher or advertiser")
		return
	}
	email := strings.TrimSpace(strings.ToLower(req.Email))
	if _, err := mail.ParseAddress(email); err != nil {
		h.jsonErrorT(w, r, http.StatusBadRequest, "a valid email address is required")
		return
	}
	company := strings.TrimSpace(req.CompanyName)
	contact := strings.TrimSpace(req.ContactName)
	website := strings.TrimSpace(req.WebsiteURL)
	if company == "" || contact == "" || website == "" {
		h.jsonErrorT(w, r, http.StatusBadRequest, "company name, website, and contact name are required")
		return
	}

	// Orgs are anchored to company email domains: freemail can't found one,
	// and a claimed domain means "get invited", not "file a second request" —
	// the operator never sees duplicate requests for one company.
	domain := org.DomainOf(email)
	if org.IsFreemailDomain(domain) {
		h.jsonErrorT(w, r, http.StatusBadRequest, "please use your company email address — accounts can't be created with free email providers")
		return
	}
	if _, err := h.orgRepo.GetByDomain(r.Context(), domain); err == nil {
		h.jsonErrorT(w, r, http.StatusConflict, "this email domain is already registered with an organization — ask your organization's admin for an invite")
		return
	}

	// Pre-check the email before the passkey ceremony so the applicant isn't
	// left with a dangling passkey when the insert would fail anyway.
	if _, err := h.userSvc.GetByEmail(r.Context(), email); err == nil {
		h.jsonErrorT(w, r, http.StatusConflict, "this email is already registered or has a pending request")
		return
	}

	tz := strings.TrimSpace(req.Timezone)
	if tz != "" {
		if _, err := time.LoadLocation(tz); err != nil {
			tz = ""
		}
	}

	pending := &model.User{
		ID:             uuid.New().String(),
		Email:          email,
		DisplayName:    company,
		Role:           model.RoleUser,
		RequestedSide:  role,
		Status:         model.StatusPending,
		CompanyName:    company,
		WebsiteURL:     website,
		ContactName:    contact,
		RequestMessage: strings.TrimSpace(req.Message),
		Timezone:       tz,
	}

	creation, token, err := h.passkeySvc.BeginRegistration(r.Context(), pending, requestPayload{User: pending})
	if err != nil {
		slog.Error("request-account begin failed", "error", err)
		h.jsonErrorT(w, r, http.StatusInternalServerError, "could not start passkey registration")
		return
	}
	writeJSONResp(w, http.StatusOK, map[string]any{"sessionToken": token, "options": creation})
}

func (h *Handler) RequestAccountFinish(w http.ResponseWriter, r *http.Request) {
	env, err := decodeEnvelope(r)
	if err != nil {
		h.jsonErrorT(w, r, http.StatusBadRequest, "invalid request body")
		return
	}
	session, payload, err := h.passkeySvc.TakeSession(env.SessionToken)
	if err != nil {
		h.jsonErrorT(w, r, http.StatusBadRequest, "session expired — try again")
		return
	}
	rp, ok := payload.(requestPayload)
	if !ok {
		h.jsonErrorT(w, r, http.StatusBadRequest, "invalid session")
		return
	}

	cred, err := h.passkeySvc.FinishRegistration(rp.User, session, bytes.NewReader(env.Credential))
	if err != nil {
		slog.Warn("request-account passkey verification failed", "error", err)
		h.jsonErrorT(w, r, http.StatusBadRequest, "passkey registration failed")
		return
	}

	err = h.userSvc.CreatePendingUser(r.Context(), rp.User, func(tx pgx.Tx) error {
		return h.passkeySvc.Repo().CreateCredentialTx(r.Context(), tx, rp.User.ID, "Signup passkey", cred)
	})
	if err != nil {
		if errors.Is(err, user.ErrDuplicateEmail) {
			h.jsonErrorT(w, r, http.StatusConflict, "this email is already registered or has a pending request")
			return
		}
		slog.Error("request-account create failed", "error", err)
		h.jsonErrorT(w, r, http.StatusInternalServerError, "could not submit your request")
		return
	}
	writeJSONResp(w, http.StatusOK, map[string]string{"status": "pending"})
}

// setSessionCookie mirrors the password flow's cookie. Secure is keyed off
// devAuth's sibling signal: any non-localhost RP ID means we're deployed
// behind HTTPS (WebAuthn refuses insecure contexts anyway).
func (h *Handler) setSessionCookie(w http.ResponseWriter, token string) {
	http.SetCookie(w, &http.Cookie{
		Name:     "token",
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   h.secureCookies,
	})
}
