package handler

// Validation + render coverage for the operator org-timezone control on
// /admin/users (AdminSetOrgTimezone). The persist/audit path needs a live
// Postgres and is exercised via the gated integration setups instead.

import (
	"io"
	"testing"

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
			OrgAdvertiser: true,
		}},
	}
	if err := getPage("admin/users.html").ExecuteTemplate(io.Discard, "layout", data); err != nil {
		t.Errorf("admin/users.html failed to render: %v", err)
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
	if err := getPage("admin/settings.html").ExecuteTemplate(io.Discard, "layout", data); err != nil {
		t.Errorf("admin/settings.html failed to render: %v", err)
	}
}
