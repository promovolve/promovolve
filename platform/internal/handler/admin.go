package handler

import (
	"fmt"
	"log/slog"
	"net/http"
	"net/mail"
	"strings"

	"github.com/google/uuid"

	"github.com/hanishi/promovolve/platform/internal/model"
)

// adminRequestRow is one pending account request on /admin/requests.
type adminRequestRow struct {
	ID          string
	Company     string
	Website     string
	Contact     string
	Email       string
	Role        string
	Message     string
	RequestedAt string
}

// adminUserRow is one account on /admin/users.
type adminUserRow struct {
	ID        string
	Email     string
	Display   string
	Company   string
	Role      string
	Status    string
	Passkeys  int
	CreatedAt string
	// Org membership (empty for the platform operator / pre-org rows).
	OrgDomain string
	OrgRole   string
	// The org's sides — what the account can actually operate. Replaces the
	// user row's requested-at-signup role in the display for org users.
	OrgAdvertiser bool
	OrgPublisher  bool
	// CanViewAs marks accounts the operator may open a read-only view-as
	// session for: active ORG ADMINS only. An org admin's view is a superset
	// of every member's, so nothing is lost — and no plain member gets an
	// operator wearing their identity.
	CanViewAs bool
	// IsSelf hides the delete button on the signed-in operator's own row.
	IsSelf bool
}

func displayRole(u model.User) string {
	if u.Role == model.RoleAdmin {
		return "admin"
	}
	return string(u.RequestedSide)
}

type marginRow struct {
	Percent       string
	EffectiveFrom string
	By            string
}

func (h *Handler) AdminRequests(w http.ResponseWriter, r *http.Request) {
	h.renderAdminRequests(w, r, "")
}

func (h *Handler) renderAdminRequests(w http.ResponseWriter, r *http.Request, errMsg string) {
	user, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	_ = user

	pending, err := h.userSvc.ListByStatus(r.Context(), model.StatusPending)
	if err != nil {
		slog.Error("list pending requests failed", "error", err)
		errMsg = "could not load pending requests"
	}
	rows := make([]adminRequestRow, 0, len(pending))
	for _, u := range pending {
		rows = append(rows, adminRequestRow{
			ID:          u.ID,
			Company:     u.CompanyName,
			Website:     u.WebsiteURL,
			Contact:     u.ContactName,
			Email:       u.Email,
			Role:        string(u.RequestedSide),
			Message:     u.RequestMessage,
			RequestedAt: u.CreatedAt.Format("2006-01-02 15:04"),
		})
	}
	sideRequests, err := h.orgRepo.ListPendingSideRequests(r.Context())
	if err != nil {
		slog.Error("list org side requests failed", "error", err)
	}
	h.render(w, "admin/requests.html", pageData{
		Title:                "Account Requests",
		Nav:                  "admin-requests",
		User:                 user,
		Error:                errMsg,
		AdminRequests:        rows,
		AdminOrgSideRequests: sideRequests,
	})
}

// ApprovalDecision handles both approve and reject POSTs.
func (h *Handler) ApprovalDecision(action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
		if !ok {
			return
		}
		r.ParseForm()
		userID := r.FormValue("userId")
		if userID == "" {
			h.renderAdminRequests(w, r, "missing user id")
			return
		}

		var err error
		if action == "approve" {
			err = h.userSvc.Approve(r.Context(), userID, admin.ID)
		} else {
			err = h.userSvc.Reject(r.Context(), userID, admin.ID)
		}
		if err != nil {
			slog.Error("request decision failed", "action", action, "userId", userID, "error", err)
			h.renderAdminRequests(w, r, fmt.Sprintf("could not %s the request: %v", action, err))
			return
		}
		http.Redirect(w, r, "/admin/requests", http.StatusSeeOther)
	}
}

// adminSiteRequestRow is one pending site request on /admin/sites.
type adminSiteRequestRow struct {
	ID          string
	Domain      string
	SiteID      string
	PageURL     string
	Publisher   string // requester email; falls back to the publisher entity id
	Company     string
	RequestedAt string
}

func (h *Handler) AdminSiteRequests(w http.ResponseWriter, r *http.Request) {
	h.renderAdminSiteRequests(w, r, "")
}

func (h *Handler) renderAdminSiteRequests(w http.ResponseWriter, r *http.Request, errMsg string) {
	user, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}

	pending, err := h.siteReqSvc.ListPending(r.Context())
	if err != nil {
		slog.Error("list pending site requests failed", "error", err)
		errMsg = "could not load pending site requests"
	}
	rows := make([]adminSiteRequestRow, 0, len(pending))
	for _, p := range pending {
		publisher := p.RequesterEmail
		if publisher == "" {
			publisher = p.PublisherID
		}
		rows = append(rows, adminSiteRequestRow{
			ID:          p.ID,
			Domain:      p.Domain,
			SiteID:      p.SiteID,
			PageURL:     p.PageURL,
			Publisher:   publisher,
			Company:     p.RequesterCompany,
			RequestedAt: p.CreatedAt.Format("2006-01-02 15:04"),
		})
	}
	h.render(w, "admin/sites.html", pageData{
		Title:             "Site Requests",
		Nav:               "admin-sites",
		User:              user,
		Error:             errMsg,
		AdminSiteRequests: rows,
	})
}

// SiteRequestDecision handles both approve and reject POSTs. Approval
// provisions the site on the core API; a core rejection (e.g. the site id
// belongs to another publisher) surfaces as the error banner and the row
// stays pending.
func (h *Handler) SiteRequestDecision(action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
		if !ok {
			return
		}
		r.ParseForm()
		requestID := r.FormValue("requestId")
		if requestID == "" {
			h.renderAdminSiteRequests(w, r, "missing request id")
			return
		}

		var err error
		if action == "approve" {
			err = h.siteReqSvc.Approve(r.Context(), requestID, admin.ID)
		} else {
			err = h.siteReqSvc.Reject(r.Context(), requestID, admin.ID, r.FormValue("reason"))
		}
		if err != nil {
			slog.Error("site request decision failed", "action", action, "requestId", requestID, "error", err)
			h.renderAdminSiteRequests(w, r, fmt.Sprintf("could not %s the request: %v", action, err))
			return
		}
		http.Redirect(w, r, "/admin/sites", http.StatusSeeOther)
	}
}

func (h *Handler) AdminUsers(w http.ResponseWriter, r *http.Request) {
	h.renderAdminUsers(w, r, "", "", "")
}

func (h *Handler) renderAdminUsers(w http.ResponseWriter, r *http.Request, errMsg, recoveryURL, recoveryEmail string) {
	user, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}

	users, err := h.userSvc.ListAll(r.Context())
	if err != nil {
		slog.Error("list users failed", "error", err)
		errMsg = "could not load users"
	}
	// Search across the identity fields, then paginate; the per-user
	// passkey count is fetched only for the visible page.
	q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	if q != "" {
		filtered := users[:0:0]
		for _, u := range users {
			hay := strings.ToLower(u.Email + " " + u.DisplayName + " " + u.CompanyName + " " + u.ID)
			if strings.Contains(hay, q) {
				filtered = append(filtered, u)
			}
		}
		users = filtered
	}
	memberships, err := h.orgRepo.MembershipSummaries(r.Context())
	if err != nil {
		slog.Error("list org memberships failed", "error", err)
	}
	start, end, nav := buildListNav(r, len(users), 25)
	rows := make([]adminUserRow, 0, end-start)
	for _, u := range users[start:end] {
		count, _ := h.passkeySvc.Repo().CountByUser(r.Context(), u.ID)
		m, inOrg := memberships[u.ID]
		rows = append(rows, adminUserRow{
			ID:      u.ID,
			Email:   u.Email,
			Display: u.DisplayName,
			Company: u.CompanyName,
			// Display role: the grant for admins, the requested side for
			// pending/rejected rows (active org users render org-side
			// badges from OrgAdvertiser/OrgPublisher instead).
			Role:          displayRole(u),
			Status:        string(u.Status),
			Passkeys:      count,
			CreatedAt:     u.CreatedAt.Format("2006-01-02"),
			OrgDomain:     m.Domain,
			OrgRole:       string(m.OrgRole),
			OrgAdvertiser: m.HasAdvertiser,
			OrgPublisher:  m.HasPublisher,
			CanViewAs:     inOrg && u.Status == model.StatusActive && m.OrgRole == model.OrgRoleAdmin,
			IsSelf:        u.ID == user.ID,
		})
	}
	h.render(w, "admin/users.html", pageData{
		Title:         "Users",
		Nav:           "admin-users",
		User:          user,
		Error:         errMsg,
		AdminUsers:    rows,
		ListNav:       nav,
		Query:         r.URL.Query().Get("q"),
		RecoveryURL:   recoveryURL,
		RecoveryEmail: recoveryEmail,
	})
}

// InviteAdmin adds another platform operator: creates the admin account and
// mints its one-time passkey-registration link (the recovery machinery, same
// as org invites). Admin-only by route guard; no cap, no domain rules — the
// operator team vouches for its own.
func (h *Handler) InviteAdmin(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	email := strings.TrimSpace(strings.ToLower(r.FormValue("email")))
	if _, err := mail.ParseAddress(email); err != nil {
		h.renderAdminUsers(w, r, "a valid email address is required", "", "")
		return
	}
	if _, err := h.userSvc.GetByEmail(r.Context(), email); err == nil {
		h.renderAdminUsers(w, r, "that email already has an account", "", "")
		return
	}
	invitee := &model.User{
		ID:          uuid.New().String(),
		Email:       email,
		DisplayName: strings.TrimSpace(r.FormValue("displayName")),
	}
	if err := h.userSvc.CreateAdmin(r.Context(), invitee); err != nil {
		slog.Error("create admin failed", "error", err)
		h.renderAdminUsers(w, r, "could not create the admin account", "", "")
		return
	}
	raw, err := h.passkeySvc.Repo().CreateRecoveryToken(r.Context(), invitee.ID, admin.ID)
	if err != nil {
		slog.Error("admin invite link mint failed", "error", err)
		h.renderAdminUsers(w, r, "account created but the link failed — use “Recovery link” on the row", "", "")
		return
	}
	h.auditRepo.Log(r.Context(), admin.ID, admin.Email, "", "admin_invite", email, "")
	h.renderAdminUsers(w, r, "", h.absoluteURL(r, "/recover/"+raw), email)
}

// AdminDeleteUser is the operator's hard delete — cleanup of test, pending,
// and rejected accounts (Reject keeps the row; this removes it and frees the
// email). Guards: no self-delete, the platform keeps >=1 admin, and an org
// keeps >=1 admin while it has other members. Deleting an org's last member
// deletes the org too, freeing the domain for a fresh signup.
func (h *Handler) AdminDeleteUser(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	target, err := h.userSvc.GetByID(r.Context(), r.FormValue("userId"))
	if err != nil {
		h.renderAdminUsers(w, r, "user not found", "", "")
		return
	}
	if target.ID == admin.ID {
		h.renderAdminUsers(w, r, "you can't delete your own account", "", "")
		return
	}
	if target.Role == model.RoleAdmin {
		admins := 0
		if users, err := h.userSvc.ListAll(r.Context()); err == nil {
			for _, u := range users {
				if u.Role == model.RoleAdmin && u.Status == model.StatusActive {
					admins++
				}
			}
		}
		if admins <= 1 {
			h.renderAdminUsers(w, r, "the platform must keep at least one admin", "", "")
			return
		}
	}

	var orphanedOrg string
	if o, m, err := h.orgRepo.ForUser(r.Context(), target.ID); err == nil {
		members, merr := h.orgRepo.CountMembers(r.Context(), o.ID)
		if merr != nil {
			h.renderAdminUsers(w, r, "could not check the user's organization", "", "")
			return
		}
		if members == 1 {
			orphanedOrg = o.ID
		} else if m.OrgRole == model.OrgRoleAdmin {
			if n, aerr := h.orgRepo.CountAdmins(r.Context(), o.ID); aerr != nil || n <= 1 {
				h.renderAdminUsers(w, r, "that user is their org's only admin — promote another member first, or delete the other members", "", "")
				return
			}
		}
	}

	if err := h.userSvc.Delete(r.Context(), target.ID); err != nil {
		slog.Error("admin delete user failed", "userId", target.ID, "error", err)
		h.renderAdminUsers(w, r, "could not delete the user", "", "")
		return
	}
	if orphanedOrg != "" {
		if err := h.orgRepo.DeleteOrg(r.Context(), orphanedOrg); err != nil {
			slog.Error("delete orphaned org failed", "orgId", orphanedOrg, "error", err)
		}
	}
	h.auditRepo.Log(r.Context(), admin.ID, admin.Email, orphanedOrg, "admin_delete_user", target.Email, string(target.Status))
	http.Redirect(w, r, "/admin/users", http.StatusSeeOther)
}

// MintRecoveryLink creates a one-time passkey re-registration URL for a
// locked-out user and shows it to the admin for manual delivery.
func (h *Handler) MintRecoveryLink(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	userID := r.FormValue("userId")
	target, err := h.userSvc.GetByID(r.Context(), userID)
	if err != nil {
		h.renderAdminUsers(w, r, "user not found", "", "")
		return
	}

	raw, err := h.passkeySvc.Repo().CreateRecoveryToken(r.Context(), target.ID, admin.ID)
	if err != nil {
		slog.Error("mint recovery link failed", "error", err)
		h.renderAdminUsers(w, r, "could not create a recovery link", "", "")
		return
	}

	scheme := "http"
	if h.secureCookies || r.TLS != nil {
		scheme = "https"
	}
	url := fmt.Sprintf("%s://%s/recover/%s", scheme, r.Host, raw)
	h.renderAdminUsers(w, r, "", url, target.Email)
}

func (h *Handler) AdminSettings(w http.ResponseWriter, r *http.Request) {
	h.renderAdminSettings(w, r, "")
}

func (h *Handler) renderAdminSettings(w http.ResponseWriter, r *http.Request, errMsg string) {
	user, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}

	current := h.settingsSvc.CurrentMarginBps(r.Context())
	history, err := h.settingsSvc.History(r.Context())
	if err != nil {
		slog.Error("margin history failed", "error", err)
		errMsg = "could not load settings history"
	}

	// Resolve created_by UUIDs to emails (few rows, tiny cache).
	emails := map[string]string{}
	rows := make([]marginRow, 0, len(history))
	for _, e := range history {
		by := "—"
		if e.CreatedBy != nil {
			if cached, ok := emails[*e.CreatedBy]; ok {
				by = cached
			} else if u, err := h.userSvc.GetByID(r.Context(), *e.CreatedBy); err == nil {
				emails[*e.CreatedBy] = u.Email
				by = u.Email
			}
		}
		rows = append(rows, marginRow{
			Percent:       formatBps(e.MarginBps),
			EffectiveFrom: e.EffectiveFrom.Format("2006-01-02 15:04"),
			By:            by,
		})
	}

	payoutFloor := ""
	if floor, err := h.billingSvc.PayoutFloorMicros(r.Context()); err == nil {
		payoutFloor = usd(floor)
	}

	h.render(w, "admin/settings.html", pageData{
		Title:         "Platform Settings",
		Nav:           "admin-settings",
		User:          user,
		Error:         errMsg,
		MarginPct:     formatBps(current),
		MarginHistory: rows,
		PayoutFloor:   payoutFloor,
	})
}

// UpdatePayoutFloor sets the platform-wide minimum payout. Publishers can
// raise their own threshold above it but never below (billing.PayoutQueue
// and the payout-method form both clamp to it).
func (h *Handler) UpdatePayoutFloor(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	micros, err := parseDollars(r.FormValue("payoutFloor"))
	if err != nil {
		h.renderAdminSettings(w, r, "payout floor must be a positive dollar amount")
		return
	}
	if err := h.billingSvc.SetPayoutFloorMicros(r.Context(), micros, admin.ID); err != nil {
		slog.Error("set payout floor failed", "error", err)
		h.renderAdminSettings(w, r, "could not update the payout floor")
		return
	}
	http.Redirect(w, r, "/admin/settings", http.StatusSeeOther)
}

func (h *Handler) UpdateMargin(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	bps, err := parseMarginPercent(r.FormValue("marginPercent"))
	if err != nil {
		h.renderAdminSettings(w, r, err.Error())
		return
	}
	if err := h.settingsSvc.SetMargin(r.Context(), bps, admin.ID); err != nil {
		slog.Error("set margin failed", "error", err)
		h.renderAdminSettings(w, r, "could not update the margin")
		return
	}
	http.Redirect(w, r, "/admin/settings", http.StatusSeeOther)
}

// formatBps renders basis points as a percent string ("1550" → "15.5").
func formatBps(bps int) string {
	s := fmt.Sprintf("%.2f", float64(bps)/100)
	s = strings.TrimRight(s, "0")
	return strings.TrimRight(s, ".")
}
