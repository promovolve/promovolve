package handler

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"net/mail"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/hanishi/promovolve/platform/internal/billing"
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
			RequestedAt: u.CreatedAt.In(user.Location()).Format("2006-01-02 15:04"),
		})
	}
	sideRequests, err := h.orgRepo.ListPendingSideRequests(r.Context())
	if err != nil {
		slog.Error("list org side requests failed", "error", err)
	}
	h.render(w, r, "admin/requests.html", pageData{
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
		if action == "approve" {
			// Approval may have provisioned the advertiser entity for an org
			// whose timezone the operator already set — push it to the core.
			if o, _, oerr := h.orgRepo.ForUser(r.Context(), userID); oerr == nil {
				h.pushOrgTimezone(r.Context(), o)
			}
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
			RequestedAt: p.CreatedAt.In(user.Location()).Format("2006-01-02 15:04"),
		})
	}
	h.render(w, r, "admin/sites.html", pageData{
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
		m := memberships[u.ID]
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
			CreatedAt:     u.CreatedAt.In(user.Location()).Format("2006-01-02"),
			OrgDomain:     m.Domain,
			OrgRole:       string(m.OrgRole),
			OrgAdvertiser: m.HasAdvertiser,
			OrgPublisher:  m.HasPublisher,
			IsSelf:        u.ID == user.ID,
		})
	}
	orgs, err := h.orgRepo.List(r.Context())
	if err != nil {
		slog.Error("list orgs failed", "error", err)
	}
	adminByOrg, err := h.orgRepo.ViewableAdminByOrg(r.Context())
	if err != nil {
		slog.Error("list org admins failed", "error", err)
	}
	// Own search param (like ?orgPage) so the users search stays untouched.
	orgQ := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("orgQ")))
	orgRows := make([]orgAdminRow, 0, len(orgs))
	for _, o := range orgs {
		if orgQ != "" && !strings.Contains(strings.ToLower(o.Domain+" "+o.Name+" "+o.SuspendReason), orgQ) {
			continue
		}
		row := orgAdminRow{
			ID:            o.ID,
			Domain:        o.Domain,
			Name:          o.Name,
			HasAdvertiser: o.AdvertiserID != nil && *o.AdvertiserID != "",
			HasPublisher:  o.PublisherID != nil && *o.PublisherID != "",
			Suspended:     o.Suspended,
			SuspendReason: o.SuspendReason,
			SuspendedBy:   o.SuspendedBy,
			Timezone:      o.Timezone,
			ViewAsUserID:  adminByOrg[o.ID],
		}
		if o.SuspendedAt != nil {
			row.SuspendedAt = o.SuspendedAt.In(user.Location()).Format("2006-01-02")
		}
		orgRows = append(orgRows, row)
	}
	// Separate page param so paging orgs doesn't reset the users table.
	orgStart, orgEnd, orgNav := buildListNavParam(r, len(orgRows), 25, "orgPage")

	// Timezone dropdown: the curated preference list, plus any (possibly
	// exotic) zone an org already carries so a re-save never resets it.
	zones := preferenceTimezones
	for _, o := range orgs {
		if o.Timezone == "" {
			continue
		}
		found := false
		for _, z := range zones {
			if z == o.Timezone {
				found = true
				break
			}
		}
		if !found {
			zones = append(zones, o.Timezone)
		}
	}

	h.render(w, r, "admin/users.html", pageData{
		Title:         "Users",
		Nav:           "admin-users",
		User:          user,
		Error:         errMsg,
		AdminUsers:    rows,
		AdminOrgs:     orgRows[orgStart:orgEnd],
		AdminOrgsNav:  orgNav,
		AdminOrgsQ:    r.URL.Query().Get("orgQ"),
		Timezones:     zones,
		ListNav:       nav,
		Query:         r.URL.Query().Get("q"),
		RecoveryURL:   recoveryURL,
		RecoveryEmail: recoveryEmail,
	})
}

// orgAdminRow is one organization in the /admin/users organizations table.
type orgAdminRow struct {
	ID            string
	Domain        string
	Name          string
	HasAdvertiser bool
	HasPublisher  bool
	Suspended     bool
	SuspendReason string
	SuspendedAt   string
	SuspendedBy   string
	// Advertiser-account timezone (IANA; "" = UTC) — the operator-only
	// budget-day control on the organizations table.
	Timezone string
	// ViewAsUserID is a representative active org-admin to open a read-only
	// view-as session for; empty when the org has no active admin (button
	// hidden). Reuses the audited /admin/view-as flow, keyed on this user.
	ViewAsUserID string
}

// AdminSuspendOrg freezes a company: org flag first (locks the dashboard for
// members on their next request), then the serving cascade into core for
// whichever sides the org holds. Cascade failures log-and-continue — the
// button is an idempotent retry.
func (h *Handler) AdminSuspendOrg(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	orgID := r.FormValue("orgId")
	reason := strings.TrimSpace(r.FormValue("reason"))
	if orgID == "" || reason == "" {
		h.renderAdminUsers(w, r, "a suspension needs a reason — it's shown to the company's members", "", "")
		return
	}
	o, err := h.orgRepo.GetByID(r.Context(), orgID)
	if err != nil {
		h.renderAdminUsers(w, r, "unknown organization", "", "")
		return
	}
	if err := h.orgRepo.SetSuspended(r.Context(), orgID, true, reason, admin.Email); err != nil {
		h.renderAdminUsers(w, r, "suspend failed: "+err.Error(), "", "")
		return
	}
	if o.AdvertiserID != nil && *o.AdvertiserID != "" {
		if err := h.orgCore.SuspendAdvertiser(r.Context(), *o.AdvertiserID); err != nil {
			slog.Error("org suspend: advertiser cascade failed (retry via the button)",
				"org", o.Domain, "advertiserId", *o.AdvertiserID, "error", err)
		}
	}
	if o.PublisherID != nil && *o.PublisherID != "" {
		if err := h.orgCore.SuspendPublisher(r.Context(), *o.PublisherID); err != nil {
			slog.Error("org suspend: publisher cascade failed (retry via the button)",
				"org", o.Domain, "publisherId", *o.PublisherID, "error", err)
		}
	}
	h.auditRepo.Log(r.Context(), admin.ID, admin.Email, orgID, "org_suspend", o.Domain, reason)
	http.Redirect(w, r, "/admin/users", http.StatusSeeOther)
}

// AdminResumeOrg lifts a suspension. The advertiser side resumes only when
// its wallet isn't billing-suspended — governance ends, but the prepaid
// policy still applies (the settler would re-bench an unfunded wallet
// anyway; skipping avoids a serving blip).
func (h *Handler) AdminResumeOrg(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	orgID := r.FormValue("orgId")
	o, err := h.orgRepo.GetByID(r.Context(), orgID)
	if err != nil {
		h.renderAdminUsers(w, r, "unknown organization", "", "")
		return
	}
	if err := h.orgRepo.SetSuspended(r.Context(), orgID, false, "", admin.Email); err != nil {
		h.renderAdminUsers(w, r, "resume failed: "+err.Error(), "", "")
		return
	}
	if o.AdvertiserID != nil && *o.AdvertiserID != "" {
		resume := true
		if acc, err := h.billingSvc.GetAccount(r.Context(), billing.OwnerAdvertiser, *o.AdvertiserID); err == nil &&
			acc.Status == billing.StatusSuspended {
			resume = false // wallet-suspended: benched until funded, as before the org suspension
		}
		if resume {
			if err := h.orgCore.ResumeAdvertiser(r.Context(), *o.AdvertiserID); err != nil {
				slog.Error("org resume: advertiser cascade failed (settlement pass will retry)",
					"org", o.Domain, "advertiserId", *o.AdvertiserID, "error", err)
			}
		}
	}
	if o.PublisherID != nil && *o.PublisherID != "" {
		if err := h.orgCore.ResumePublisher(r.Context(), *o.PublisherID); err != nil {
			slog.Error("org resume: publisher cascade failed (retry via suspend+resume)",
				"org", o.Domain, "publisherId", *o.PublisherID, "error", err)
		}
	}
	h.auditRepo.Log(r.Context(), admin.ID, admin.Email, orgID, "org_resume", o.Domain, "")
	http.Redirect(w, r, "/admin/users", http.StatusSeeOther)
}

// AdminSetOrgTimezone sets the org's timezone (IANA; "" = UTC), the control
// for every org (advertiser, publisher, or both): it drives both sides'
// local billing/earnings days on the settler, and — for advertiser orgs —
// budget rollover and pacing on the core. Operator-only. The advertiser
// cascade is skipped for publisher-only orgs and log-and-continues like
// suspend otherwise — re-saving is the retry.
func (h *Handler) AdminSetOrgTimezone(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	orgID := r.FormValue("orgId")
	tz := strings.TrimSpace(r.FormValue("timezone"))
	if !validTimezone(tz) {
		h.renderAdminUsers(w, r, fmt.Sprintf("unknown timezone %q — use an IANA zone like Asia/Tokyo", tz), "", "")
		return
	}
	o, err := h.orgRepo.GetByID(r.Context(), orgID)
	if err != nil {
		h.renderAdminUsers(w, r, "unknown organization", "", "")
		return
	}
	if err := h.orgRepo.SetTimezone(r.Context(), orgID, tz); err != nil {
		h.renderAdminUsers(w, r, "timezone update failed: "+err.Error(), "", "")
		return
	}
	if o.AdvertiserID != nil && *o.AdvertiserID != "" {
		if err := h.orgCore.SetAdvertiserTimezone(r.Context(), *o.AdvertiserID, tz); err != nil {
			slog.Error("org timezone: advertiser cascade failed (re-save to retry)",
				"org", o.Domain, "advertiserId", *o.AdvertiserID, "error", err)
		}
	}
	h.auditRepo.Log(r.Context(), admin.ID, admin.Email, orgID, "org_timezone", o.Domain, tz)
	http.Redirect(w, r, "/admin/users", http.StatusSeeOther)
}

// validTimezone accepts "" (= UTC, the default) or any loadable IANA zone.
func validTimezone(tz string) bool {
	if tz == "" {
		return true
	}
	_, err := time.LoadLocation(tz)
	return err == nil
}

// pushOrgTimezone forwards a non-default org timezone to the core advertiser
// — for the approval flows, where the advertiser entity may be provisioned
// AFTER the operator already set the org's zone. Log-and-continue like the
// suspend cascade; the /admin/users timezone control is the retry.
func (h *Handler) pushOrgTimezone(ctx context.Context, o *model.Org) {
	if o == nil || o.Timezone == "" || o.AdvertiserID == nil || *o.AdvertiserID == "" {
		return
	}
	if err := h.orgCore.SetAdvertiserTimezone(ctx, *o.AdvertiserID, o.Timezone); err != nil {
		slog.Error("org timezone: core push failed (retry via the /admin/users timezone control)",
			"org", o.Domain, "advertiserId", *o.AdvertiserID, "error", err)
	}
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
			EffectiveFrom: e.EffectiveFrom.In(user.Location()).Format("2006-01-02 15:04"),
			By:            by,
		})
	}

	payoutFloor := ""
	if floor, err := h.billingSvc.PayoutFloorMicros(r.Context()); err == nil {
		payoutFloor = usd(floor)
	}

	// Timezone dropdown: the curated preference list, plus the (possibly
	// exotic) current default so a re-save never resets it.
	defaultTZ := h.orgRepo.DefaultTimezone(r.Context())
	zones := preferenceTimezones
	if defaultTZ != "" {
		found := false
		for _, z := range zones {
			if z == defaultTZ {
				found = true
				break
			}
		}
		if !found {
			zones = append([]string{defaultTZ}, zones...)
		}
	}

	h.render(w, r, "admin/settings.html", pageData{
		Title:              "Platform Settings",
		Nav:                "admin-settings",
		User:               user,
		Error:              errMsg,
		MarginPct:          formatBps(current),
		MarginHistory:      rows,
		PayoutFloor:        payoutFloor,
		OrgMaxMembers:      h.orgRepo.MaxMembers(r.Context()),
		DefaultOrgTimezone: defaultTZ,
		Timezones:          zones,
	})
}

// UpdateOrgMaxMembers sets the operator-tunable member cap per org. The
// cap gates NEW invites/approvals only — orgs already above it keep
// their members.
func (h *Handler) UpdateOrgMaxMembers(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	n, err := strconv.Atoi(strings.TrimSpace(r.FormValue("orgMaxMembers")))
	if err != nil || n < 1 || n > 100 {
		h.renderAdminSettings(w, r, "member cap must be a whole number between 1 and 100")
		return
	}
	if err := h.orgRepo.SetMaxMembers(r.Context(), n, admin.ID); err != nil {
		slog.Error("set org member cap failed", "error", err)
		h.renderAdminSettings(w, r, "could not update the member cap")
		return
	}
	http.Redirect(w, r, "/admin/settings", http.StatusSeeOther)
}

// UpdateDefaultOrgTimezone sets the advertiser-account timezone seeded onto
// NEW organizations at creation (IANA; "" = UTC). Creation-time only —
// existing orgs keep their zone; the per-org control on /admin/users is the
// override.
func (h *Handler) UpdateDefaultOrgTimezone(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	tz := strings.TrimSpace(r.FormValue("timezone"))
	if !validTimezone(tz) {
		h.renderAdminSettings(w, r, fmt.Sprintf("unknown timezone %q — use an IANA zone like Asia/Tokyo", tz))
		return
	}
	if err := h.orgRepo.SetDefaultTimezone(r.Context(), tz, admin.ID); err != nil {
		slog.Error("set default org timezone failed", "error", err)
		h.renderAdminSettings(w, r, "could not update the default timezone")
		return
	}
	http.Redirect(w, r, "/admin/settings", http.StatusSeeOther)
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
