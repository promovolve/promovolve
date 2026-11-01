package handler

// Validation + render coverage for the operator org-timezone control on
// /admin/users (AdminSetOrgTimezone). The persist/audit path needs a live
// Postgres and is exercised via the gated integration setups instead.

import (
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/hanishi/promovolve/platform/internal/i18n"

	platform "github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/model"
)

func TestValidTimezone(t *testing.T) {
	for _, tz := range []string{"", "UTC", "Asia/Tokyo", "America/New_York", "Europe/Paris"} {
		if !validTimezone(tz) {
			t.Errorf("validTimezone(%q) = false, want true", tz)
		}
	}
	for _, tz := range []string{"JST", "Tokyo", "Asia/Nowhere", "GMT+9", "asia/tokyo "} {
		if validTimezone(tz) {
			t.Errorf("validTimezone(%q) = true, want false", tz)
		}
	}
}

func TestAdminUsersTemplateRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)

	admin := &model.User{Email: "admin@test", Role: model.RoleAdmin}
	data := pageData{
		Title: "Users", Nav: "admin-users", User: admin, Error: "an error banner",
		Timezones: preferenceTimezones,
		AdminOrgs: []orgAdminRow{
			// Advertiser side present, exotic zone selected, active admin →
			// View-as button.
			{ID: "org-1", Domain: "adv.example.com", Name: "Adv Co",
				HasAdvertiser: true, Timezone: "Asia/Tokyo", ViewAsUserID: "u1"},
			// Publisher-only orgs now also get the editable timezone select
			// (the zone drives their earnings/billing day post-settlement).
			{ID: "org-2", Domain: "pub.example.com", Name: "Pub Co", HasPublisher: true},
			{ID: "org-3", Domain: "gone.example.com", Suspended: true,
				SuspendReason: "invoices unpaid", SuspendedBy: "admin@test", SuspendedAt: "2026-07-14"},
		},
		AdminUsers: []adminUserRow{{
			ID: "u1", Email: "adv@adv.example.com", Display: "Adv Admin", Role: "user",
			Status: "active", OrgDomain: "adv.example.com", OrgRole: "admin",
			OrgAdvertiser: true, Passkeys: 2, CreatedAt: "2026-07-01",
		}, {
			ID: "u2", Email: "new.publisher.with.a.long.address@pub.example.com", Display: "Pending Person", Company: "Pub Co",
			Role: "publisher", Status: "pending", CreatedAt: "2026-10-30",
		}, {
			ID: "u3", Email: "no@nope.example.com", Role: "advertiser", Status: "rejected", CreatedAt: "2026-09-02",
		}},
		ListNav: &listNav{Page: 1, TotalPages: 1, Total: 3, From: 1, To: 3},
	}
	for _, tlang := range []string{i18n.LangEN, i18n.LangJA} {
		var sb strings.Builder
		if err := getPage(tlang, "admin/users.html").ExecuteTemplate(&sb, "layout", data); err != nil {
			t.Errorf("admin/users.html failed to render: %v", err)
		}
		out := sb.String()
		// A user's status is an account state, not a campaign's: "active"
		// must not read 配信中 (serving).
		if tlang == i18n.LangJA && (strings.Contains(out, "配信中") || !strings.Contains(out, "承認済み")) {
			t.Errorf("[ja] user status should read 承認済み, not 配信中")
		}
		// Each organization's controls live in its own Manage dialog, so the
		// list stays one line per organization.
		for _, o := range data.AdminOrgs {
			dlg := strings.Index(out, `<dialog id="org-`+o.ID+`"`)
			if dlg < 0 {
				t.Errorf("[%s] %s: no Manage dialog", tlang, o.ID)
				continue
			}
			body := out[dlg : dlg+strings.Index(out[dlg:], "</dialog>")]
			want := "/admin/orgs/suspend"
			if o.Suspended {
				want = "/admin/orgs/resume"
			}
			for _, action := range []string{"/admin/orgs/timezone", want} {
				if !strings.Contains(body, `action="`+action+`"`) {
					t.Errorf("[%s] %s: dialog lacks %s", tlang, o.ID, action)
				}
			}
		}
		if strings.Count(out, `action="/admin/orgs/timezone"`) != len(data.AdminOrgs) {
			t.Errorf("[%s] timezone forms outside the dialogs", tlang)
		}
		// Adding an operator is rare: a dialog off the page header.
		if dlg := strings.Index(out, `<dialog id="invite-admin"`); dlg < 0 || !strings.Contains(out[dlg:], `action="/admin/users/invite-admin"`) {
			t.Errorf("[%s] no invite-admin dialog", tlang)
		}
		// With PREVIEW_OUT set, dump the render for the printed-page suite.
		if dir := os.Getenv("PREVIEW_OUT"); dir != "" {
			if err := os.WriteFile(filepath.Join(dir, "admin-users-"+tlang+".html"), []byte(sb.String()), 0o644); err != nil {
				t.Fatalf("write preview: %v", err)
			}
		}
	}
}

func TestAdminSettingsTemplateRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)

	admin := &model.User{Email: "admin@test", Role: model.RoleAdmin}
	data := pageData{
		Title: "Platform Settings", Nav: "admin-settings", User: admin,
		MarginPct: "15", PayoutFloor: "50", OrgMaxMembers: 10,
		// The default-timezone select renders with the current default
		// selected (exotic zones are prepended by the handler).
		DefaultOrgTimezone: "Asia/Tokyo",
		Timezones:          preferenceTimezones,
		MarginHistory: []marginRow{
			{Percent: "15", EffectiveFrom: "2026-07-14 12:00", By: "admin@test"},
		},
	}
	for _, tlang := range []string{i18n.LangEN, i18n.LangJA} {
		if err := getPage(tlang, "admin/settings.html").ExecuteTemplate(io.Discard, "layout", data); err != nil {
			t.Errorf("admin/settings.html failed to render: %v", err)
		}
	}
}
