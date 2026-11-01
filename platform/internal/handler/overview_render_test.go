package handler

// Render test for the advertiser Overview (advertiser/overview.html), funded
// and locked. With PREVIEW_OUT set, dumps both renders for the
// printed-page browser suite.

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	platform "github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"github.com/hanishi/promovolve/platform/internal/model"
)

func TestAdvertiserOverviewRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	adv := &model.User{Email: "adv@test", Role: model.RoleAdvertiser}
	hourly := make([]hourlyBar, 24)
	for i := range hourly {
		hourly[i] = hourlyBar{Hour: i, Spend: "$0.00"}
		if i >= 7 && i <= 14 {
			hourly[i] = hourlyBar{Hour: i, Imps: int64(40 + i*9), Spend: "$0.40", HeightPct: 30 + i*4}
		}
	}
	funded := pageData{
		Title: "Overview", Nav: "overview", User: adv,
		AdvHourly: hourly, AdvHourlyTotal: 1204,
		AdvBudget:     &advertiserBudget{DailyBudget: "50.00", SpendToday: "12.40", Remaining: "37.60"},
		WalletBalance: "$1,250.00",
		Campaigns: []campaignData{
			{ID: "c1", Name: "Autumn tart", Impressions: 1204, Clicks: 38, CTR: "3.2%", SpendToday: "12.40", DailyBudget: "30.00", BudgetPct: 41, ECPM: "$10.30", LifetimeSpend: "$310.00", MaxCPM: "12.00"},
			{ID: "c2", Name: "Gift box", SpendToday: "0.00", DailyBudget: "20.00"},
		},
		ListNav: &listNav{Page: 1, TotalPages: 1, Total: 2, From: 1, To: 2},
	}
	locked := pageData{
		Title: "Overview", Nav: "overview", User: adv, WalletLocked: true,
		AdvBudget:     &advertiserBudget{DailyBudget: "0.00", SpendToday: "0.00", Remaining: "0.00", IsZero: true},
		WalletBalance: "$0.00", WalletUnfunded: true,
	}
	// An admin viewing as that unfunded advertiser: the budget dialog
	// auto-opens over the Exit view banner, so its Cancel must still work.
	viewAs := locked
	viewAs.User = &model.User{Email: "adv@test", Role: model.RoleAdvertiser, ViewAsBy: "admin@test"}
	for _, c := range []struct {
		name string
		data pageData
	}{{"advertiser-overview", funded}, {"advertiser-overview-locked", locked}, {"advertiser-overview-viewas", viewAs}} {
		for _, tlang := range []string{i18n.LangEN, i18n.LangJA} {
			var sb strings.Builder
			if err := getPage(tlang, "advertiser/overview.html").ExecuteTemplate(&sb, "layout", c.data); err != nil {
				t.Fatalf("%s %s: render failed: %v", c.name, tlang, err)
			}
			out := sb.String()
			if !strings.Contains(out, "data-overview-money") {
				t.Errorf("%s %s: no budget/wallet figures beside the chart", c.name, tlang)
			}
			// Account is folded in: the budget form lives here, and the nav
			// has no Account page to link to.
			// The budget form opens in a dialog, so the page under it never
			// moves (an inline form pushed the campaign table down).
			if dlg := strings.Index(out, `<dialog id="budget"`); dlg < 0 || !strings.Contains(out[dlg:], `action="/advertiser/budget"`) {
				t.Errorf("%s %s: no budget form in a dialog on Overview", c.name, tlang)
			}
			// Each campaign row links to its edit panel on Campaigns.
			for _, cd := range c.data.Campaigns {
				if !strings.Contains(out, `href="/advertiser/campaigns?edit=`+cd.ID+`"`) {
					t.Errorf("%s %s: no Edit creatives link for %s", c.name, tlang, cd.ID)
				}
			}
			if strings.Contains(out, `href="/advertiser/account"`) {
				t.Errorf("%s %s: still links to the Account page", c.name, tlang)
			}
			// Locked: the shut tabs are text, not links, and the page says why.
			hasCampaignsLink := strings.Contains(out, `href="/advertiser/campaigns"`)
			if c.data.WalletLocked == hasCampaignsLink {
				t.Errorf("%s %s: Campaigns link present = %v", c.name, tlang, hasCampaignsLink)
			}
			if c.data.WalletLocked != strings.Contains(out, "notice-advisory") {
				t.Errorf("%s %s: unfunded notice shown = %v", c.name, tlang, !c.data.WalletLocked)
			}
			if dir := os.Getenv("PREVIEW_OUT"); dir != "" {
				p := filepath.Join(dir, c.name+"-"+tlang+".html")
				if err := os.WriteFile(p, []byte(out), 0o644); err != nil {
					t.Fatalf("write %s: %v", p, err)
				}
			}
		}
	}
}
