package handler

// Render test for the Campaign Budget Optimization surface (GH #73, #98):
// the account budget-mode control and what follows from it on the
// campaigns page. Budget mode is a property of the ACCOUNT: while it is
// Optimized every campaign's daily budget input is disabled (not submitted,
// so the PATCH omits it) and every campaign carries the Optimized badge;
// there is no per-campaign strategy control. Executes the real templates in
// both languages.

import (
	"bytes"
	"strings"
	"testing"

	platform "github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"github.com/hanishi/promovolve/platform/internal/model"
)

func TestCboTemplatesRender(t *testing.T) {
	SetFS(platform.Templates, platform.Static)

	adv := &model.User{Email: "adv@test", Role: model.RoleAdvertiser}
	nav := &listNav{Page: 1, TotalPages: 1, Total: 2, From: 1, To: 2}
	rows := []campaignData{
		{ID: "camp-a", Name: "A", Status: "active", DailyBudget: "70.00", MaxCPM: "5.00"},
		{ID: "camp-b", Name: "B", Status: "active", DailyBudget: "30.00", MaxCPM: "5.00"},
	}
	optimized := &advertiserBudget{DailyBudget: "100.00", Remaining: "60.00", SpendToday: "40.00", BudgetMode: "optimized"}
	manual := &advertiserBudget{DailyBudget: "100.00", Remaining: "60.00", SpendToday: "40.00", BudgetMode: "manual"}

	render := func(lang, name string, data pageData) string {
		var out bytes.Buffer
		if err := getPage(lang, name).ExecuteTemplate(&out, "layout", data); err != nil {
			t.Fatalf("%s (%s) failed to render: %v", name, lang, err)
		}
		return out.String()
	}
	disabled := func(html, value string) bool {
		return strings.Contains(html, `value="`+value+`"`+"\n                      disabled")
	}

	for _, lang := range []string{i18n.LangEN, i18n.LangJA} {
		badge := i18n.T(lang, "Optimized")
		badges := func(html string) int { return strings.Count(html, ">\n            "+badge+"\n          </span>") }

		// Optimized account: no per-campaign control, both budgets disabled, both badged.
		html := render(lang, "advertiser/campaigns.html",
			pageData{Title: "Campaigns", Nav: "campaigns", User: adv, AdvBudget: optimized, ListNav: nav, Campaigns: rows})
		if strings.Contains(html, `name="strategy"`) {
			t.Errorf("%s: a per-campaign strategy control is rendered (#98)", lang)
		}
		for _, v := range []string{"70.00", "30.00"} {
			if !disabled(html, v) {
				t.Errorf("%s: budget input %s is not disabled under an optimized account", lang, v)
			}
		}
		if n := badges(html); n != 2 {
			t.Errorf("%s: expected an Optimized badge on every campaign, got %d", lang, n)
		}
		if !strings.Contains(html, i18n.T(lang, "Optimized budget mode")) {
			t.Errorf("%s: account-budget line does not show the optimized mode", lang)
		}

		// Manual account: budgets editable, no badges.
		html = render(lang, "advertiser/campaigns.html",
			pageData{Title: "Campaigns", Nav: "campaigns", User: adv, AdvBudget: manual, ListNav: nav, Campaigns: rows})
		for _, v := range []string{"70.00", "30.00"} {
			if disabled(html, v) {
				t.Errorf("%s: budget input %s is disabled under a manual account", lang, v)
			}
		}
		if n := badges(html); n != 0 {
			t.Errorf("%s: expected no Optimized badge under a manual account, got %d", lang, n)
		}

		html = render(lang, "advertiser/account.html", pageData{Title: "Account", Nav: "account", User: adv, AdvBudget: optimized})
		if !strings.Contains(html, `name="budgetMode" value="optimized" checked`) {
			t.Errorf("%s: account page does not pre-select the optimized mode", lang)
		}
		if strings.Contains(html, `name="budgetMode" value="manual" checked`) {
			t.Errorf("%s: account page pre-selects manual while optimized", lang)
		}
	}

	// Manual account, no budget yet: the form must still render, defaulting to manual.
	html := render(i18n.LangEN, "advertiser/account.html", pageData{Title: "Account", Nav: "account", User: adv, BudgetUnset: true})
	if !strings.Contains(html, `name="budgetMode" value="manual" checked`) {
		t.Errorf("account page without a budget does not default to manual")
	}
}
