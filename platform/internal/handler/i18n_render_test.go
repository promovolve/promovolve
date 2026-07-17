package handler

import (
	"io"
	"strings"
	"testing"

	platform "github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"github.com/hanishi/promovolve/platform/internal/model"
)

// The batch-1 i18n surface must execute cleanly in BOTH languages —
// catches parse errors from the {{t}} sweep (bad quoting, backtick
// contexts) and printf-arg mismatches at execution time.
func TestI18nSweptPagesRenderBothLangs(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	user := &model.User{ID: "u1", Email: "u@example.com", Role: model.RoleAdvertiser, Locale: "ja"}
	cases := []struct {
		name string
		data pageData
	}{
		{"login.html", pageData{Title: "Sign in", Mode: "choose"}},
		{"recover.html", pageData{Title: "Account recovery", RecoveryToken: "tok"}},
		{"request-account.html", pageData{Title: "Request access"}},
		{"setup.html", pageData{Title: "Setup", Timezones: []string{"UTC", "Asia/Tokyo"}}},
		{"guard-error.html", pageData{Title: "Read-only session", Error: "nope", GuardExit: true}},
		{"help.html", pageData{Title: "Help", User: user}},
		{"account-preferences.html", pageData{
			Title: "Preferences", User: user,
			Timezones: []string{"UTC", "Asia/Tokyo"}, Saved: true,
			LandingSide: "advertiser", LandingSides: []string{"advertiser", "publisher"},
		}},
	}
	for _, lang := range []string{i18n.LangEN, i18n.LangJA} {
		for _, c := range cases {
			if err := getPage(lang, c.name).ExecuteTemplate(io.Discard, "layout", c.data); err != nil {
				t.Errorf("%s [%s]: %v", c.name, lang, err)
			}
		}
	}
}

// The Japanese build of the preferences page must actually be Japanese —
// guards against the funcMap silently binding the wrong language.
func TestI18nPreferencesRendersJapanese(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	user := &model.User{ID: "u1", Email: "u@example.com", Role: model.RoleAdvertiser, Locale: "ja"}
	var sb strings.Builder
	data := pageData{Title: "Preferences", User: user, Timezones: []string{"UTC"}}
	if err := getPage(i18n.LangJA, "account-preferences.html").ExecuteTemplate(&sb, "layout", data); err != nil {
		t.Fatalf("render: %v", err)
	}
	out := sb.String()
	for _, want := range []string{"環境設定", "言語", "自動(ブラウザに従う)", `lang="ja"`} {
		if !strings.Contains(out, want) {
			t.Errorf("ja render missing %q", want)
		}
	}
	if strings.Contains(out, "Save preferences") {
		t.Error("ja render still contains untranslated Save preferences")
	}
}

func TestI18nResolve(t *testing.T) {
	cases := []struct{ pref, accept, want string }{
		{"", "", "en"},
		{"", "ja,en-US;q=0.9", "ja"},
		{"", "ja-JP", "ja"},
		{"", "en-US,en;q=0.9,ja;q=0.8", "en"},
		{"", "fr-FR,de;q=0.9", "en"},
		{"en", "ja", "en"},
		{"ja", "en-US", "ja"},
		{"de", "ja", "ja"}, // bogus pref → auto
	}
	for _, c := range cases {
		if got := i18n.Resolve(c.pref, c.accept); got != c.want {
			t.Errorf("Resolve(%q, %q) = %q, want %q", c.pref, c.accept, got, c.want)
		}
	}
}
