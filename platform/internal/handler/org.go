package handler

// Org membership, the side switcher, and the admin "view as" feature. All
// three ride the same actor-vs-account session split: the JWT's uid is the
// ACCOUNT being operated, while org membership rows and the optional actor
// claim say WHICH HUMAN is acting (attribution + read-only enforcement).

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/mail"
	"strings"

	"github.com/google/uuid"

	"github.com/hanishi/promovolve/platform/internal/auth"
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"github.com/hanishi/promovolve/platform/internal/model"
	"github.com/hanishi/promovolve/platform/internal/org"
	"github.com/hanishi/promovolve/platform/internal/user"
)

// --- Session guard (wraps the whole mux) -------------------------------------

// moneyPages are org-admin-only: advertiser Wallet and publisher Earnings
// (incl. the payout-method POST that lives on the earnings page). Members of
// an org get everything else; billing stays with the org's admins.
var moneyPages = map[string]bool{
	"/advertiser/wallet":       true,
	"/publisher/earnings":      true,
	"/publisher/payout-method": true,
}

// auditedPrefixes are the billing-relevant member actions that must record
// WHICH member acted (budget/campaign changes feed settlement; approval and
// site-config actions shape publisher revenue).
var auditedPrefixes = []string{
	"/advertiser/budget",
	"/advertiser/campaigns/",
	"/advertiser/block-site",
	"/advertiser/unblock-site",
	"/publisher/approval/",
	"/publisher/payout-method",
	"/publisher/sites/",
	"/org/",
}

// SessionGuard enforces, for every request before routing:
//  1. view-as sessions are strictly read-only (except the exit endpoint);
//  2. money pages require an org ADMIN — checked fresh against the DB so a
//     demotion applies immediately, not when the JWT expires;
//  3. org members' billing-relevant mutations land in the audit log.
//
// It deliberately lives on the mux, not in handlers, so no new route can
// forget it.
func (h *Handler) SessionGuard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie("token")
		if err != nil {
			next.ServeHTTP(w, r)
			return
		}
		claims, err := h.jwtSvc.Validate(cookie.Value)
		if err != nil {
			next.ServeHTTP(w, r)
			return
		}

		mutating := r.Method != http.MethodGet && r.Method != http.MethodHead
		viewAs := claims.ActorID != ""

		// The only POSTs a view-as session may make are the ones that manage
		// the session itself: leaving it, logging out, and flipping the org
		// side (which just re-mints the token, actor and read-only intact).
		if viewAs && mutating &&
			r.URL.Path != "/view-as/exit" && r.URL.Path != "/logout" && r.URL.Path != "/account/switch-side" {
			actor, err := h.userSvc.GetByID(r.Context(), claims.ActorID)
			if err != nil || actor.Status != model.StatusActive || actor.Role != model.RoleAdmin {
				// Not a live view-as session — e.g. a cookie that survived a
				// factory reset (still signed, users gone). Enforcing on it
				// would brick /setup and login POSTs until the JWT expires;
				// clear it and let normal auth deal with the request.
				h.clearDeadSessionCookie(w)
				next.ServeHTTP(w, r)
				return
			}
			glang := h.lang(r, actor)
			h.renderGuardError(w, r, pageData{
				Title:     "Read-only session",
				Error:     i18n.T(glang, "You are viewing this account as %s — changes are disabled so every action stays attributable to the account members themselves. Exit the view to act as yourself.", actor.Email),
				GuardExit: true,
			})
			return
		}

		if claims.OrgID != "" && !viewAs && (moneyPages[r.URL.Path] || !suspensionExemptPath(r.URL.Path)) {
			o, m, err := h.orgRepo.ForUser(r.Context(), claims.UserID)
			if errors.Is(err, org.ErrNotFound) {
				// Dead session (user/org gone — reset or offboarding): not a
				// member sneaking into billing. Pass through; sessionUser
				// downstream returns nil and the page redirects to login.
				h.clearDeadSessionCookie(w)
				next.ServeHTTP(w, r)
				return
			}

			// Operator suspension of the whole company: authenticated members
			// see the notice on every dashboard request — fresh DB read, so it
			// applies immediately to live sessions. Auth itself still works
			// (the user is told WHY, no mystery 403s); logout and static
			// assets stay reachable. Operators are org-less and view-as
			// sessions (an operator investigating) never reach this branch.
			if err == nil && o.Suspended && !suspensionExemptPath(r.URL.Path) {
				gu, _ := h.userSvc.GetByID(r.Context(), claims.UserID)
				glang := h.lang(r, gu)
				reason := strings.TrimSpace(o.SuspendReason)
				if reason == "" {
					reason = i18n.T(glang, "no reason was recorded")
				}
				h.renderGuardError(w, r, pageData{
					Title:      "Account suspended",
					Error:      i18n.T(glang, "The account of your organization is suspended: %s. Serving and billing are paused. Contact the platform operator to resolve this.", reason),
					LogoutOnly: true,
				})
				return
			}

			if moneyPages[r.URL.Path] && (err != nil || m.OrgRole != model.OrgRoleAdmin) {
				gu, _ := h.userSvc.GetByID(r.Context(), claims.UserID)
				glang := h.lang(r, gu)
				h.renderGuardError(w, r, pageData{
					Title: "Org admins only",
					Error: i18n.T(glang, "Billing pages — the wallet and earnings — are managed by the admins of your organization. Ask one of them if you need something changed there."),
				})
				return
			}
		}

		if claims.OrgID != "" && !viewAs && mutating && isAuditedPath(r.URL.Path) {
			detail := ""
			if strings.HasPrefix(r.Header.Get("Content-Type"), "application/x-www-form-urlencoded") {
				// ParseForm caches into r.Form, so the handler's own
				// ParseForm call still sees everything.
				if r.ParseForm() == nil {
					detail = r.Form.Encode()
					if len(detail) > 500 {
						detail = detail[:500]
					}
				}
			}
			h.auditRepo.Log(r.Context(), claims.UserID, claims.Email, claims.OrgID,
				r.Method+" "+r.URL.Path, "", detail)
		}

		next.ServeHTTP(w, r)
	})
}

// renderGuardError shows a guard rejection as a styled page for browser
// navigations; programmatic callers (htmx, fetch/JSON) keep a terse 403 body
// they can surface inline.
func (h *Handler) renderGuardError(w http.ResponseWriter, r *http.Request, data pageData) {
	if r.Header.Get("HX-Request") != "" || strings.Contains(r.Header.Get("Accept"), "application/json") {
		http.Error(w, data.Title+": "+data.Error, http.StatusForbidden)
		return
	}
	h.renderStatus(w, r, http.StatusForbidden, "guard-error.html", data)
}

// clearDeadSessionCookie expires the token cookie when its users no longer
// exist, so the browser recovers on its own instead of carrying the corpse
// around until the JWT expires.
func (h *Handler) clearDeadSessionCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name: "token", Value: "", Path: "/", MaxAge: -1,
		HttpOnly: true, SameSite: http.SameSiteLaxMode, Secure: h.secureCookies,
	})
}

// suspensionExemptPath lists what a suspended org's member may still reach:
// the exits (logout/login) and the assets the notice page itself needs.
func suspensionExemptPath(path string) bool {
	return path == "/logout" || path == "/login" ||
		strings.HasPrefix(path, "/static/") || path == "/favicon.ico"
}

func isAuditedPath(path string) bool {
	for _, p := range auditedPrefixes {
		if strings.HasPrefix(path, p) {
			return true
		}
	}
	return false
}

// --- Login-time session minting ----------------------------------------------

// issueSessionFor mints the right token for a fresh login: an org session for
// org members (active side defaults to the account's requested role when the
// org holds it), a plain token for the platform admin and pre-org accounts.
func (h *Handler) issueSessionFor(ctx context.Context, u *model.User) (token, redirect string, err error) {
	if u.Role != model.RoleAdmin {
		o, m, oerr := h.orgRepo.ForUser(ctx, u.ID)
		if oerr != nil {
			// A non-admin without an org can't operate anything — refuse the
			// session rather than minting one that loops on the role guards.
			return "", "", fmt.Errorf("account %s has no organization: %w", u.Email, oerr)
		}
		side := m.DefaultSide(o)
		tok, err := h.jwtSvc.IssueSession(u, auth.SessionOpts{Org: o, OrgRole: m.OrgRole, Side: side})
		return tok, homeFor(side), err
	}
	tok, err := h.jwtSvc.Issue(u)
	return tok, homeFor(u.Role), err
}

// SwitchSide re-mints the session cookie on the org's other side. The org
// must actually hold the requested side; membership role is re-read fresh.
func (h *Handler) SwitchSide(w http.ResponseWriter, r *http.Request) {
	u, claims := h.sessionUser(r)
	if u == nil || u.Org == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	side := model.Role(r.FormValue("side"))
	if (side != model.RoleAdvertiser && side != model.RolePublisher) || !u.Org.HasSide(side) {
		http.Redirect(w, r, homeFor(u.Role), http.StatusSeeOther)
		return
	}
	_, m, err := h.orgRepo.ForUser(r.Context(), u.ID)
	if err != nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	token, err := h.jwtSvc.IssueSession(u, auth.SessionOpts{
		Org: u.Org, OrgRole: m.OrgRole, Side: side, ActorID: claims.ActorID,
	})
	if err != nil {
		http.Error(w, "failed to switch side", http.StatusInternalServerError)
		return
	}
	// Remember the choice so the next fresh login lands here — unless an
	// admin is flipping sides inside a view-as, which must never rewrite
	// the member's own preference.
	if claims.ActorID == "" {
		if err := h.orgRepo.SetPreferredSide(r.Context(), u.Org.ID, u.ID, side); err != nil {
			slog.Warn("persist preferred side failed", "error", err)
		}
	}
	h.setSessionCookie(w, token)
	http.Redirect(w, r, homeFor(side), http.StatusSeeOther)
}

// --- Org Team page -------------------------------------------------------------

type orgPageData struct {
	Domain  string
	Name    string
	Members []org.MemberRow
	// The signed-in member's own vantage point.
	SelfID     string
	IsAdmin    bool
	CanInvite  bool
	MaxMembers int
	// MissingSide is the side the org doesn't hold yet ("" when it has both);
	// SidePending marks an operator review already in flight for it.
	MissingSide string
	SidePending bool
	// One-time invite link shown right after a successful invite.
	InviteURL   string
	InviteEmail string
	Notice      string
}

func (h *Handler) OrgMembersPage(w http.ResponseWriter, r *http.Request) {
	h.renderOrgMembers(w, r, "", "", "", "")
}

func (h *Handler) renderOrgMembers(w http.ResponseWriter, r *http.Request, errMsg, notice, inviteURL, inviteEmail string) {
	u, _ := h.sessionUser(r)
	if u == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	if u.Org == nil {
		http.Redirect(w, r, homeFor(u.Role), http.StatusSeeOther)
		return
	}
	members, err := h.orgRepo.Members(r.Context(), u.Org.ID)
	if err != nil {
		slog.Error("list org members failed", "error", err)
		errMsg = "could not load your team"
	}
	maxMembers := h.orgRepo.MaxMembers(r.Context())
	data := &orgPageData{
		Domain:      u.Org.Domain,
		Name:        u.Org.Name,
		Members:     members,
		SelfID:      u.ID,
		IsAdmin:     u.IsOrgAdmin(),
		CanInvite:   u.IsOrgAdmin() && len(members) < maxMembers,
		MaxMembers:  maxMembers,
		InviteURL:   inviteURL,
		InviteEmail: inviteEmail,
		Notice:      notice,
	}
	if !u.Org.HasSide(model.RoleAdvertiser) {
		data.MissingSide = string(model.RoleAdvertiser)
	} else if !u.Org.HasSide(model.RolePublisher) {
		data.MissingSide = string(model.RolePublisher)
	}
	if data.MissingSide != "" {
		pending, err := h.orgRepo.HasPendingSideRequest(r.Context(), u.Org.ID, model.Role(data.MissingSide))
		if err == nil {
			data.SidePending = pending
		}
	}
	h.render(w, r, "org/members.html", pageData{
		Title:   "Team",
		Nav:     "team",
		User:    u,
		Error:   errMsg,
		OrgPage: data,
	})
}

// OrgInvite creates a member account (same email domain only, capped at the
// operator-set org member limit) and
// mints its one-time passkey registration link — the recovery-link machinery
// reused as the invite flow. Operator approval is deliberately NOT involved:
// the org admin vouches for their own colleague.
func (h *Handler) OrgInvite(w http.ResponseWriter, r *http.Request) {
	u, _ := h.sessionUser(r)
	if u == nil || u.Org == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	if !u.IsOrgAdmin() {
		h.renderOrgMembers(w, r, "only org admins can invite members", "", "", "")
		return
	}
	r.ParseForm()
	email := strings.TrimSpace(strings.ToLower(r.FormValue("email")))
	name := strings.TrimSpace(r.FormValue("displayName"))
	if _, err := mail.ParseAddress(email); err != nil {
		h.renderOrgMembers(w, r, "a valid email address is required", "", "", "")
		return
	}
	if org.DomainOf(email) != u.Org.Domain {
		h.renderOrgMembers(w, r, fmt.Sprintf("invitees must use your organization's email domain (@%s)", u.Org.Domain), "", "", "")
		return
	}
	n, err := h.orgRepo.CountMembers(r.Context(), u.Org.ID)
	if max := h.orgRepo.MaxMembers(r.Context()); err != nil || n >= max {
		h.renderOrgMembers(w, r, fmt.Sprintf("your organization already has the maximum of %d members", max), "", "", "")
		return
	}

	invitee := &model.User{
		ID:          uuid.New().String(),
		Email:       email,
		DisplayName: name,
		CompanyName: u.Org.Name,
		Status:      model.StatusActive,
	}
	// The invitee's landing side starts as the inviter's active side.
	if err := h.userSvc.InviteMember(r.Context(), invitee, u.Org.ID, u.ID, u.ActiveSide); err != nil {
		if errors.Is(err, user.ErrDuplicateEmail) {
			h.renderOrgMembers(w, r, "that email already has an account or pending request", "", "", "")
			return
		}
		slog.Error("org invite failed", "error", err)
		h.renderOrgMembers(w, r, "could not create the invite", "", "", "")
		return
	}

	raw, err := h.passkeySvc.Repo().CreateRecoveryToken(r.Context(), invitee.ID, u.ID)
	if err != nil {
		slog.Error("invite link mint failed", "error", err)
		h.renderOrgMembers(w, r, "the account was created but the invite link failed — use “New invite link” on the member row", "", "", "")
		return
	}
	h.auditRepo.Log(r.Context(), u.ID, u.Email, u.Org.ID, "org_invite", email, "")
	h.renderOrgMembers(w, r, "", "", h.absoluteURL(r, "/recover/"+raw), email)
}

// OrgInviteLink re-mints a one-time registration link for a member with no
// passkey yet (lost the first link, or the original mint failed).
func (h *Handler) OrgInviteLink(w http.ResponseWriter, r *http.Request) {
	u, _ := h.sessionUser(r)
	if u == nil || u.Org == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	if !u.IsOrgAdmin() {
		h.renderOrgMembers(w, r, "only org admins can mint invite links", "", "", "")
		return
	}
	r.ParseForm()
	target, err := h.memberInOrg(r.Context(), u.Org.ID, r.FormValue("userId"))
	if err != nil {
		h.renderOrgMembers(w, r, err.Error(), "", "", "")
		return
	}
	raw, err := h.passkeySvc.Repo().CreateRecoveryToken(r.Context(), target.ID, u.ID)
	if err != nil {
		slog.Error("invite link mint failed", "error", err)
		h.renderOrgMembers(w, r, "could not create the link", "", "", "")
		return
	}
	h.auditRepo.Log(r.Context(), u.ID, u.Email, u.Org.ID, "org_invite_link", target.Email, "")
	h.renderOrgMembers(w, r, "", "", h.absoluteURL(r, "/recover/"+raw), target.Email)
}

// OrgMemberAction handles promote / demote / remove. Rules (user decisions
// 2026-07-08): any admin may promote or demote any other admin; the org must
// always keep >=1 admin (enforced atomically in SetMemberRole); self-removal
// is blocked; removing a member deletes their account outright.
func (h *Handler) OrgMemberAction(action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		u, _ := h.sessionUser(r)
		if u == nil || u.Org == nil {
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}
		if !u.IsOrgAdmin() {
			h.renderOrgMembers(w, r, "only org admins can manage members", "", "", "")
			return
		}
		r.ParseForm()
		target, err := h.memberInOrg(r.Context(), u.Org.ID, r.FormValue("userId"))
		if err != nil {
			h.renderOrgMembers(w, r, err.Error(), "", "", "")
			return
		}

		switch action {
		case "promote":
			err = h.orgRepo.SetMemberRole(r.Context(), u.Org.ID, target.ID, model.OrgRoleAdmin)
		case "demote":
			err = h.orgRepo.SetMemberRole(r.Context(), u.Org.ID, target.ID, model.OrgRoleMember)
		case "remove":
			if target.ID == u.ID {
				h.renderOrgMembers(w, r, "you can't remove yourself", "", "", "")
				return
			}
			_, m, merr := h.orgRepo.ForUser(r.Context(), target.ID)
			if merr == nil && m.OrgRole == model.OrgRoleAdmin {
				if n, aerr := h.orgRepo.CountAdmins(r.Context(), u.Org.ID); aerr != nil || n <= 1 {
					h.renderOrgMembers(w, r, "an org must keep at least one admin", "", "", "")
					return
				}
			}
			err = h.userSvc.Delete(r.Context(), target.ID)
		}
		if err != nil {
			h.renderOrgMembers(w, r, err.Error(), "", "", "")
			return
		}
		h.auditRepo.Log(r.Context(), u.ID, u.Email, u.Org.ID, "org_member_"+action, target.Email, "")
		http.Redirect(w, r, "/org/members", http.StatusSeeOther)
	}
}

// memberInOrg loads a target user and verifies they belong to the given org.
func (h *Handler) memberInOrg(ctx context.Context, orgID, userID string) (*model.User, error) {
	if userID == "" {
		return nil, errors.New("missing user id")
	}
	target, err := h.userSvc.GetByID(ctx, userID)
	if err != nil {
		return nil, errors.New("member not found")
	}
	_, m, err := h.orgRepo.ForUser(ctx, userID)
	if err != nil || m.OrgID != orgID {
		return nil, errors.New("that user is not in your organization")
	}
	return target, nil
}

// OrgRequestSide files the org's request for its missing side. Never
// automatic: it lands in the platform operator's review queue.
func (h *Handler) OrgRequestSide(w http.ResponseWriter, r *http.Request) {
	u, _ := h.sessionUser(r)
	if u == nil || u.Org == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	if !u.IsOrgAdmin() {
		h.renderOrgMembers(w, r, "only org admins can request the other side", "", "", "")
		return
	}
	r.ParseForm()
	side := model.Role(r.FormValue("side"))
	if side != model.RoleAdvertiser && side != model.RolePublisher {
		h.renderOrgMembers(w, r, "choose advertiser or publisher", "", "", "")
		return
	}
	if u.Org.HasSide(side) {
		h.renderOrgMembers(w, r, "your organization already has that side", "", "", "")
		return
	}
	if err := h.orgRepo.CreateSideRequest(r.Context(), u.Org.ID, side, u.ID, strings.TrimSpace(r.FormValue("message"))); err != nil {
		h.renderOrgMembers(w, r, err.Error(), "", "", "")
		return
	}
	h.auditRepo.Log(r.Context(), u.ID, u.Email, u.Org.ID, "org_request_side", string(side), "")
	h.renderOrgMembers(w, r, "", "Request submitted — the platform operator will review it.", "", "")
}

// --- Admin: view-as + org side request queue ----------------------------------

// AdminViewAs mints a READ-ONLY session as the target user: pages render
// exactly as that user sees them, every mutation is blocked by SessionGuard,
// and the use itself is audited. Full impersonation stays out by design
// (billing attribution + the passkey "only you can be you" promise);
// mint-session remains the documented break-glass.
func (h *Handler) AdminViewAs(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	target, err := h.userSvc.GetByID(r.Context(), r.FormValue("userId"))
	if err != nil || target.Status != model.StatusActive {
		h.renderAdminUsers(w, r, "user not found or not active", "", "")
		return
	}
	o, m, err := h.orgRepo.ForUser(r.Context(), target.ID)
	if err != nil {
		h.renderAdminUsers(w, r, "that user has no organization to view", "", "")
		return
	}
	if m.OrgRole != model.OrgRoleAdmin {
		// Only org admins are viewable: their vantage covers everything a
		// member sees, without an operator borrowing a member's identity.
		h.renderAdminUsers(w, r, "only an org's admins can be viewed — their view covers everything members see", "", "")
		return
	}
	// Land where the member themself would land, preference included.
	side := m.DefaultSide(o)
	token, err := h.jwtSvc.IssueSession(target, auth.SessionOpts{
		Org: o, OrgRole: m.OrgRole, Side: side, ActorID: admin.ID,
	})
	if err != nil {
		h.renderAdminUsers(w, r, "could not start the view-as session", "", "")
		return
	}
	h.auditRepo.Log(r.Context(), admin.ID, admin.Email, o.ID, "view_as", target.Email, "")
	slog.Info("admin view-as session started", "admin", admin.Email, "target", target.Email)
	h.setSessionCookie(w, token)
	http.Redirect(w, r, homeFor(side), http.StatusSeeOther)
}

// ViewAsExit returns the operator to their own admin session.
func (h *Handler) ViewAsExit(w http.ResponseWriter, r *http.Request) {
	cookie, err := r.Cookie("token")
	if err != nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	claims, err := h.jwtSvc.Validate(cookie.Value)
	if err != nil || claims.ActorID == "" {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	actor, err := h.userSvc.GetByID(r.Context(), claims.ActorID)
	if err != nil || actor.Status != model.StatusActive || actor.Role != model.RoleAdmin {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	token, err := h.jwtSvc.Issue(actor)
	if err != nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	h.auditRepo.Log(r.Context(), actor.ID, actor.Email, claims.OrgID, "view_as_exit", claims.Email, "")
	h.setSessionCookie(w, token)
	http.Redirect(w, r, "/admin/users", http.StatusSeeOther)
}

// OrgSideDecision approves/rejects an org's request for its other side.
func (h *Handler) OrgSideDecision(action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
		if !ok {
			return
		}
		r.ParseForm()
		requestID := r.FormValue("requestId")
		var err error
		if action == "approve" {
			err = h.userSvc.ApproveOrgSide(r.Context(), requestID, admin.ID)
		} else {
			err = h.userSvc.RejectOrgSide(r.Context(), requestID, admin.ID)
		}
		if err != nil {
			slog.Error("org side decision failed", "action", action, "requestId", requestID, "error", err)
			h.renderAdminRequests(w, r, fmt.Sprintf("could not %s the side request: %v", action, err))
			return
		}
		if action == "approve" {
			// A side approval may have provisioned the advertiser entity for
			// an org whose timezone was already set — push it to the core.
			if sr, serr := h.orgRepo.GetSideRequest(r.Context(), requestID); serr == nil {
				if o, oerr := h.orgRepo.GetByID(r.Context(), sr.OrgID); oerr == nil {
					h.pushOrgTimezone(r.Context(), o)
				}
			}
		}
		http.Redirect(w, r, "/admin/requests", http.StatusSeeOther)
	}
}

// absoluteURL rebuilds a link on this deployment's origin (mirrors
// MintRecoveryLink's scheme heuristic).
func (h *Handler) absoluteURL(r *http.Request, path string) string {
	scheme := "http"
	if h.secureCookies || r.TLS != nil {
		scheme = "https"
	}
	return fmt.Sprintf("%s://%s%s", scheme, r.Host, path)
}
