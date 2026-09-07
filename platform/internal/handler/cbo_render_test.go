package handler

// Render test for the Campaign Budget Optimization surface (GH #73): the
// account budget-mode control, the campaign strategy control in the create
// and edit forms, and the Optimized badge in the list. Executes the real
// templates in both languages and asserts the markup the handlers rely on
// (a disabled budget input is not submitted, so the PATCH omits it).

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
	budget := &advertiserBudget{DailyBudget: "100.00", Remaining: "60.00", SpendToday: "40.00", BudgetMode: "optimized"}

	campaigns := pageData{
		Title: "Campaigns", Nav: "campaigns", User: adv, AdvBudget: budget, ListNav: nav,
		Campaigns: []campaignData{
			{ID: "camp-auto", Name: "Auto", Status: "active", DailyBudget: "70.00", MaxCPM: "5.00", Strategy: "auto"},
			{ID: "camp-fixed", Name: "Fixed", Status: "active", DailyBudget: "30.00", MaxCPM: "5.00", Strategy: "fixed"},
		},
	}
	account := pageData{Title: "Account", Nav: "account", User: adv, AdvBudget: budget}

	for _, lang := range []string{i18n.LangEN, i18n.LangJA} {
		var out bytes.Buffer
		if err := getPage(lang, "advertiser/campaigns.html").ExecuteTemplate(&out, "layout", campaigns); err != nil {
			t.Fatalf("campaigns.html (%s) failed to render: %v", lang, err)
		}
		html := out.String()
		if strings.Count(html, `name="strategy" value="auto"`) != 3 { // create form + one edit form per campaign
			t.Errorf("%s: expected 3 auto strategy radios, got %d", lang, strings.Count(html, `name="strategy" value="auto"`))
		}
		// The auto campaign's edit form: budget disabled + greyed, auto checked.
		if !strings.Contains(html, `value="70.00"`+"\n                      disabled") {
			t.Errorf("%s: auto campaign's budget input is not disabled", lang)
		}
		if !strings.Contains(html, `value="auto" checked`) {
			t.Errorf("%s: auto campaign's strategy radio is not checked", lang)
		}
		// The fixed campaign's edit form keeps its budget editable.
		if strings.Contains(html, `value="30.00"`+"\n                      disabled") {
			t.Errorf("%s: fixed campaign's budget input is disabled", lang)
		}
		// One Optimized badge in the list (the fixed campaign gets none) and
		// the account-budget line shows the mode.
		badge := i18n.T(lang, "Optimized")
		if strings.Count(html, ">\n            "+badge+"\n          </span>") != 1 {
			t.Errorf("%s: expected exactly one Optimized badge in the list", lang)
		}
		if !strings.Contains(html, i18n.T(lang, "Optimized budget mode")) {
			t.Errorf("%s: account-budget line does not show the optimized mode", lang)
		}

		out.Reset()
		if err := getPage(lang, "advertiser/account.html").ExecuteTemplate(&out, "layout", account); err != nil {
			t.Fatalf("account.html (%s) failed to render: %v", lang, err)
		}
		html = out.String()
		if !strings.Contains(html, `name="budgetMode" value="optimized" checked`) {
			t.Errorf("%s: account page does not pre-select the optimized mode", lang)
		}
		if strings.Contains(html, `name="budgetMode" value="manual" checked`) {
			t.Errorf("%s: account page pre-selects manual while optimized", lang)
		}
	}

	// Manual account, no budget yet: the form must still render, defaulting to manual.
	var out bytes.Buffer
	if err := getPage(i18n.LangEN, "advertiser/account.html").ExecuteTemplate(&out, "layout",
		pageData{Title: "Account", Nav: "account", User: adv, BudgetUnset: true}); err != nil {
		t.Fatalf("account.html without a budget failed to render: %v", err)
	}
	if !strings.Contains(out.String(), `name="budgetMode" value="manual" checked`) {
		t.Errorf("account page without a budget does not default to manual")
	}
}
