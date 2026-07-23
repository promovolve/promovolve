package handler

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"net/mail"
	"sort"
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

// adminFraudFlagRow is one open fraud flag on /admin/fraud
// (docs/design/FRAUD_PREVENTION.md, Layer 3).
type adminFraudFlagRow struct {
	ID        int64
	SiteID    string
	Signal    string // human-labeled below
	SignalLbl string
	Severity  string // formatted
	WindowDay string
	Evidence  string
	FlaggedAt string
	// Status is "" for open flags (the only ones core lists today);
	// rendered as a badge only when non-open, so a future core change
	// that returns resolved rows is visible instead of silently blending
	// in with the open queue.
	Status string
}

// adminSuspectSiteRow is one site's live suspect-marked traffic for the
// current UTC day on /admin/fraud — the Layer-0/1 hygiene and
// engagement-guard marks as they land, before (and whether or not) the
// Layer-2 detector trips a flag.
type adminSuspectSiteRow struct {
	SiteID    string
	Total     int64  // all events today, clean + suspect
	Suspect   int64  // suspect-marked events today
	SharePct  string // suspect/total, formatted percent
	Breakdown string // "bot_ua 120 / chain 40 / timing 12"
}

// adminSuspendedSiteRow is one currently-suspended site shown on
// /admin/sites — one row per site (a site with several flags is collapsed).
// Confirmed==true means fraud was upheld (still frozen, money already
// clawed back); Confirmed==false means it's auto-suspended awaiting review.
type adminSuspendedSiteRow struct {
	SiteID    string
	SignalLbl string // most-recent flag's signal
	Evidence  string // most-recent flag's evidence
	WindowDay string // most-recent flag's day, scopes any payout
	FlaggedAt string
	Confirmed bool
}

// fraudSignalLabel gives the operator a plain-English name for a signal id.
func fraudSignalLabel(signal string) string {
	switch signal {
	case "suspect_share":
		return "Bot / suspect traffic share"
	case "imp_per_pageview":
		return "Impressions without pageviews"
	case "ctr_spike":
		return "Click-rate spike"
	default:
		return signal
	}
}

// AdminFraudFlags renders the fraud review queue.
func (h *Handler) AdminFraudFlags(w http.ResponseWriter, r *http.Request) {
	h.renderAdminFraudFlags(w, r, "")
}

func (h *Handler) renderAdminFraudFlags(w http.ResponseWriter, r *http.Request, errMsg string) {
	user, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	flags, err := h.orgCore.ListFraudFlags(r.Context())
	if err != nil {
		slog.Error("list fraud flags failed", "error", err)
		errMsg = "could not load fraud flags"
	}
	rows := make([]adminFraudFlagRow, 0, len(flags))
	for _, f := range flags {
		flaggedAt := f.FlaggedAt
		if t, perr := time.Parse(time.RFC3339, f.FlaggedAt); perr == nil {
			flaggedAt = t.In(user.Location()).Format("2006-01-02 15:04")
		}
		status := f.Status
		if status == "open" {
			status = "" // the queue IS the open list; only surprises get a badge
		}
		rows = append(rows, adminFraudFlagRow{
			ID:        f.ID,
			SiteID:    f.SiteID,
			Signal:    f.Signal,
			SignalLbl: fraudSignalLabel(f.Signal),
			Severity:  fmt.Sprintf("%.1f", f.Severity),
			WindowDay: f.WindowDay,
			Evidence:  f.Evidence,
			FlaggedAt: flaggedAt,
			Status:    status,
		})
	}
	h.render(w, r, "admin/fraud.html", pageData{
		Title:             "Fraud Review",
		Nav:               "admin-fraud",
		User:              user,
		Error:             errMsg,
		AdminFraudFlags:   rows,
		AdminSuspectSites: h.suspectActivityRows(r.Context()),
	})
}

// suspectActivityRows aggregates today's live suspect counts per site for
// the /admin/fraud panel. Best-effort: on error it logs and returns nil —
// the flag queue must render even if the activity query fails.
func (h *Handler) suspectActivityRows(ctx context.Context) []adminSuspectSiteRow {
	activity, err := h.billingSvc.SuspectActivityToday(ctx)
	if err != nil {
		slog.Error("suspect activity query failed", "error", err)
		return nil
	}
	type agg struct {
		total, suspect int64
		byReason       map[string]int64
	}
	sites := map[string]*agg{}
	for _, row := range activity {
		a := sites[row.SiteID]
		if a == nil {
			a = &agg{byReason: map[string]int64{}}
			sites[row.SiteID] = a
		}
		a.total += row.Count
		if row.Reason != "clean" {
			a.suspect += row.Count
			a.byReason[row.Reason] += row.Count
		}
	}
	out := make([]adminSuspectSiteRow, 0, len(sites))
	for siteID, a := range sites {
		if a.suspect == 0 {
			continue // clean sites don't belong on a fraud page
		}
		reasons := make([]string, 0, len(a.byReason))
		for reason := range a.byReason {
			reasons = append(reasons, reason)
		}
		sort.Slice(reasons, func(i, j int) bool {
			if a.byReason[reasons[i]] != a.byReason[reasons[j]] {
				return a.byReason[reasons[i]] > a.byReason[reasons[j]]
			}
			return reasons[i] < reasons[j]
		})
		parts := make([]string, 0, len(reasons))
		for _, reason := range reasons {
			parts = append(parts, fmt.Sprintf("%s %d", reason, a.byReason[reason]))
		}
		out = append(out, adminSuspectSiteRow{
			SiteID:    siteID,
			Total:     a.total,
			Suspect:   a.suspect,
			SharePct:  fmt.Sprintf("%.1f", 100*float64(a.suspect)/float64(a.total)),
			Breakdown: strings.Join(parts, " / "),
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Suspect > out[j].Suspect })
	return out
}

// FraudFlagDecision handles release (false positive) and confirm (real
// fraud) POSTs. Confirm composes the resolve with a surgical single-site
// suspend — the same "resolve locally, enforce on core" shape AdminSuspendOrg
// uses. Release only labels the flag for threshold tuning.
func (h *Handler) FraudFlagDecision(action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
		if !ok {
			return
		}
		r.ParseForm()
		idStr := r.FormValue("flagId")
		siteID := r.FormValue("siteId")
		windowDay := r.FormValue("windowDay") // "2006-01-02"; scopes the settled clawback
		id, perr := strconv.ParseInt(idStr, 10, 64)
		if perr != nil || idStr == "" {
			h.renderAdminFraudFlags(w, r, "missing or bad flag id")
			return
		}

		status := "released"
		if action == "confirm" {
			status = "confirmed"
		}
		if err := h.orgCore.ResolveFraudFlag(r.Context(), id, status, admin.Email); err != nil {
			slog.Error("resolve fraud flag failed", "action", action, "flagId", id, "error", err)
			h.renderAdminFraudFlags(w, r, fmt.Sprintf("could not %s the flag: %v", action, err))
			return
		}
		if action == "confirm" && siteID != "" {
			// Enforcement: keep the one fraudulent site frozen. The detector
			// already auto-suspended it when the flag fired, so this is
			// normally a no-op reassertion (idempotent) — it also covers the
			// case where that auto-suspend delivery was lost. A failure here
			// is logged but the flag stays confirmed.
			if err := h.orgCore.SuspendSite(r.Context(), siteID); err != nil {
				slog.Error("suspend site after fraud confirm failed", "siteId", siteID, "error", err)
				h.renderAdminFraudFlags(w, r,
					fmt.Sprintf("flag confirmed, but suspending %s failed: %v — retry from Sites", siteID, err))
				return
			}
		}
		if action == "release" && siteID != "" {
			// False positive: undo the detector's auto-suspend so the site
			// serves again. (The flag is now 'released', so the detector
			// won't re-suspend it for the same day.)
			if err := h.orgCore.ResumeSite(r.Context(), siteID); err != nil {
				slog.Error("resume site after fraud release failed", "siteId", siteID, "error", err)
				h.renderAdminFraudFlags(w, r,
					fmt.Sprintf("flag released, but resuming %s failed: %v — retry from Sites", siteID, err))
				return
			}
		}

		// Money reversal (fraud Layer 3.1). Release pays the held cells to
		// the publisher; Confirm claws them back to the advertiser. A failure
		// here leaves the flag resolved + (for confirm) the site suspended —
		// the operator retries the money step; nothing is double-applied
		// because both paths are idempotent on the settlement/clawback key.
		if siteID != "" && h.billingSvc != nil {
			if msg := h.resolveFraudMoney(r.Context(), action, siteID, windowDay, admin.Email); msg != "" {
				h.renderAdminFraudFlags(w, r, msg)
				return
			}
		}

		if h.auditRepo != nil {
			h.auditRepo.Log(r.Context(), admin.ID, admin.Email, "", "fraud_flag_"+action, siteID, idStr)
		}
		http.Redirect(w, r, "/admin/fraud", http.StatusSeeOther)
	}
}

// resolveFraudMoney applies the L3.1 money reversal for a fraud decision.
// Release drains every held cell for the site to the publisher (false
// positive → pay normally); Confirm claws the held cells back to the
// advertiser AND reverses any cells that had already settled before the
// flag landed (a Layer-2 catch that arrived post-settlement). Returns a
// non-empty operator message only on failure.
func (h *Handler) resolveFraudMoney(ctx context.Context, action, siteID, windowDay, by string) string {
	switch action {
	case "release":
		n, err := h.billingSvc.ReleaseSiteHolds(ctx, siteID, by)
		if err != nil {
			slog.Error("release fraud holds failed", "siteId", siteID, "error", err)
			return fmt.Sprintf("flag released, but paying out held earnings failed: %v — retry", err)
		}
		if n > 0 {
			slog.Info("released fraud holds", "siteId", siteID, "cells", n)
		}
	case "confirm":
		held, err := h.billingSvc.ClawbackSiteHolds(ctx, siteID, by)
		if err != nil {
			slog.Error("clawback fraud holds failed", "siteId", siteID, "error", err)
			return fmt.Sprintf("flag confirmed + site suspended, but clawing back held earnings failed: %v — retry", err)
		}
		// Also reverse anything that settled before the hold could catch it
		// (a Layer-2 catch that landed after the publisher window settled).
		// Scope this to the flagged day ±1 (a generous margin covering any
		// single timezone offset) rather than a broad sweep, so a site that
		// was honest for weeks and fraudulent for one day is not stripped of
		// its legitimate earnings. The precise, common case is the held path;
		// this only backstops the post-settlement race.
		var settled int
		if from, to, ok := flagDayRange(windowDay); ok {
			settled, err = h.billingSvc.ClawbackSettledWindow(ctx, siteID, from, to, by)
			if err != nil {
				slog.Error("clawback settled window failed", "siteId", siteID, "error", err)
				return fmt.Sprintf("flag confirmed + site suspended; held cells clawed back, but reversing already-settled earnings failed: %v — retry", err)
			}
		}
		if held+settled > 0 {
			slog.Info("clawed back fraud earnings", "siteId", siteID, "heldCells", held, "settledCells", settled)
		}
	}
	return ""
}

// flagDayRange turns a flag's window day ("2006-01-02") into the instant
// range [day-1, day+2) UTC — the flagged local day plus a one-day margin on
// each side so any single-timezone-offset settlement window that carried
// the fraudulent traffic is covered. Returns ok=false for an unparseable
// day (older flags without the field), which simply skips the settled
// backstop and relies on the held path.
func flagDayRange(windowDay string) (from, to time.Time, ok bool) {
	d, err := time.Parse("2006-01-02", windowDay)
	if err != nil {
		return time.Time{}, time.Time{}, false
	}
	return d.AddDate(0, 0, -1), d.AddDate(0, 0, 2), true
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
		Title:               "Site Requests",
		Nav:                 "admin-sites",
		User:                user,
		Error:               errMsg,
		AdminSiteRequests:   rows,
		AdminSuspendedSites: h.suspendedSiteRows(r.Context(), user),
	})
}

// suspendedSiteRows lists every currently-suspended site (auto-suspended
// with an open flag, OR confirmed-fraud and still frozen), one row per
// site, newest first. This is distinct from Fraud Review, which shows only
// open flags awaiting a decision — a confirmed site stays here (resumable)
// after it leaves that queue. Best-effort: on error it logs and returns
// nil so the site-request queue still renders.
func (h *Handler) suspendedSiteRows(ctx context.Context, user *model.User) []adminSuspendedSiteRow {
	if h.billingSvc == nil {
		return nil
	}
	flags, err := h.billingSvc.ListSuspendedSites(ctx)
	if err != nil {
		slog.Error("list suspended sites failed", "error", err)
		return nil
	}
	// Collapse to one row per site: flags arrive newest-first, so the first
	// one seen for a site is the most recent; a confirmed flag marks the
	// whole site confirmed.
	bySite := map[string]*adminSuspendedSiteRow{}
	order := make([]string, 0, len(flags))
	for _, f := range flags {
		row := bySite[f.SiteID]
		if row == nil {
			flaggedAt := f.FlaggedAt.In(user.Location()).Format("2006-01-02 15:04")
			row = &adminSuspendedSiteRow{
				SiteID:    f.SiteID,
				SignalLbl: fraudSignalLabel(f.Signal),
				Evidence:  f.Evidence,
				WindowDay: f.WindowDay.Format("2006-01-02"),
				FlaggedAt: flaggedAt,
			}
			bySite[f.SiteID] = row
			order = append(order, f.SiteID)
		}
		if f.Status == "confirmed" {
			row.Confirmed = true
		}
	}
	rows := make([]adminSuspendedSiteRow, 0, len(order))
	for _, siteID := range order {
		rows = append(rows, *bySite[siteID])
	}
	return rows
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

// ResumeSuspendedSite reinstates a fraud-auto-suspended site from the Site
// Requests page — the "false positive" path. It resolves the flag as
// released, un-freezes serving, and pays out any held earnings (a false
// positive earned normally), then returns to /admin/sites. Mirrors the
// fraud-page Release action but keeps the operator in the sites context.
func (h *Handler) ResumeSuspendedSite(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	windowDay := r.FormValue("windowDay")
	if siteID == "" {
		h.renderAdminSiteRequests(w, r, "missing site id")
		return
	}
	// Clear every active flag for the site (a site can carry several), so
	// the detector won't re-suspend it for the same day (the status guard
	// blocks re-open of a released flag).
	if h.billingSvc != nil {
		if _, err := h.billingSvc.ResolveSiteFlags(r.Context(), siteID, admin.Email); err != nil {
			slog.Error("resolve site flags on resume failed", "siteId", siteID, "error", err)
			h.renderAdminSiteRequests(w, r, fmt.Sprintf("could not clear flags for %s: %v", siteID, err))
			return
		}
	}
	if err := h.orgCore.ResumeSite(r.Context(), siteID); err != nil {
		slog.Error("resume suspended site failed", "siteId", siteID, "error", err)
		h.renderAdminSiteRequests(w, r, fmt.Sprintf("could not resume %s: %v", siteID, err))
		return
	}
	// False positive → pay out any held earnings, same as fraud-page Release.
	if h.billingSvc != nil {
		if msg := h.resolveFraudMoney(r.Context(), "release", siteID, windowDay, admin.Email); msg != "" {
			h.renderAdminSiteRequests(w, r, msg)
			return
		}
	}
	if h.auditRepo != nil {
		h.auditRepo.Log(r.Context(), admin.ID, admin.Email, "", "fraud_site_resume", siteID, r.FormValue("flagId"))
	}
	http.Redirect(w, r, "/admin/sites", http.StatusSeeOther)
}

// RestoreSuspendedSiteEarnings is the "the confirm was wrong — this
// publisher earned it" path for a confirmed-fraud site: it reverses the
// clawback (re-charges the advertiser, pays the publisher net + platform
// fee), then resumes serving and clears the flags. Distinct from plain
// Resume, which reinstates serving but leaves a real fraud's earnings
// reversed. Restore runs first so a failure there aborts before anything
// is un-suspended.
func (h *Handler) RestoreSuspendedSiteEarnings(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	if siteID == "" {
		h.renderAdminSiteRequests(w, r, "missing site id")
		return
	}
	if h.billingSvc == nil {
		h.renderAdminSiteRequests(w, r, "billing unavailable")
		return
	}
	cells, gross, err := h.billingSvc.RestoreSiteEarnings(r.Context(), siteID, admin.Email)
	if err != nil {
		slog.Error("restore site earnings failed", "siteId", siteID, "error", err)
		h.renderAdminSiteRequests(w, r, fmt.Sprintf("could not restore earnings for %s: %v — retry", siteID, err))
		return
	}
	if cells > 0 {
		slog.Info("restored fraud-clawed earnings", "siteId", siteID, "cells", cells, "grossMicros", gross)
	}
	if _, err := h.billingSvc.ResolveSiteFlags(r.Context(), siteID, admin.Email); err != nil {
		slog.Error("resolve site flags on restore failed", "siteId", siteID, "error", err)
		h.renderAdminSiteRequests(w, r, fmt.Sprintf("earnings restored, but clearing flags for %s failed: %v — retry", siteID, err))
		return
	}
	if err := h.orgCore.ResumeSite(r.Context(), siteID); err != nil {
		slog.Error("resume site after restore failed", "siteId", siteID, "error", err)
		h.renderAdminSiteRequests(w, r, fmt.Sprintf("earnings restored, but resuming %s failed: %v — retry", siteID, err))
		return
	}
	if h.auditRepo != nil {
		h.auditRepo.Log(r.Context(), admin.ID, admin.Email, "", "fraud_site_restore", siteID, fmt.Sprintf("%d cells", cells))
	}
	http.Redirect(w, r, "/admin/sites", http.StatusSeeOther)
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
