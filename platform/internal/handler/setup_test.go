package handler

// Validation coverage for the setup wizard's begin request — in particular
// that the default advertiser timezone survives validate() into the ceremony
// payload (it rides the passkey session to SetupFinish, which persists it).
// The create/persist path needs a live Postgres and is exercised via the
// gated integration setups instead.

import (
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"io"
	"testing"

	platform "github.com/hanishi/promovolve/platform"
)

func TestSetupValidateCarriesTimezone(t *testing.T) {
	req := setupBeginRequest{
		Email:         "admin@example.com",
		DisplayName:   "Admin",
		MarginPercent: "15",
		PayoutFloor:   "50",
		Timezone:      " Asia/Tokyo ",
	}
	admin, bps, floor, tz, err := req.validate()
	if err != nil {
		t.Fatalf("validate() unexpected error: %v", err)
	}
	if admin.Email != "admin@example.com" || bps != 1500 || floor != 50_000_000 {
		t.Errorf("validate() = (%q, %d, %d), want (admin@example.com, 1500, 50000000)",
			admin.Email, bps, floor)
	}
	if tz != "Asia/Tokyo" {
		t.Errorf("validate() timezone = %q, want %q (trimmed)", tz, "Asia/Tokyo")
	}

	// Blank means UTC — the wizard's "UTC (default)" option.
	req.Timezone = ""
	if _, _, _, tz, err = req.validate(); err != nil || tz != "" {
		t.Errorf("validate() with blank timezone = (%q, %v), want (\"\", nil)", tz, err)
	}

	req.Timezone = "Asia/Nowhere"
	if _, _, _, _, err = req.validate(); err == nil {
		t.Error("validate() with unknown timezone expected error, got none")
	}
}

func TestSetupTemplateRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)

	data := pageData{
		Title:     "Set up PromoVolve",
		DevAuth:   true, // render the dev form's timezone select too
		Error:     "an error banner",
		Timezones: preferenceTimezones,
	}
	if err := getPage(i18n.LangEN, "setup.html").ExecuteTemplate(io.Discard, "layout", data); err != nil {
		t.Errorf("setup.html failed to render: %v", err)
	}
}
