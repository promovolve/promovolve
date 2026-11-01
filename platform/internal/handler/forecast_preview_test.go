package handler

import (
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"github.com/hanishi/promovolve/platform/internal/model"
)

// TestCampaignsForecastRenders renders the campaigns page with forecast
// strips in every state (spending, budget reached, paused, untargeted, no
// delivery). With PREVIEW_OUT set it also writes advertiser-forecast-<lang>
// for a screenshot pass.
func TestCampaignsForecastRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	now := time.Date(2026, 10, 21, 15, 40, 0, 0, time.UTC)
	shape := func(scale float64, upTo int) (h [24]float64) {
		curve := []float64{.3, .2, .1, .1, .1, .2, .5, .8, 1, 1.2, 1.3, 1.1, 1, 1, 1.2, .9, 1, 1.1, 1.3, 1.4, 1.2, .9, .6, .4}
		for i := 0; i < upTo; i++ {
			h[i] = curve[i] * scale
		}
		return
	}
	for _, lang := range []string{i18n.LangEN, i18n.LangJA} {
		pct := func(spend, budget string) float64 {
			sv, _ := strconv.ParseFloat(spend, 64)
			bv, _ := strconv.ParseFloat(budget, 64)
			if bv <= 0 {
				return 0
			}
			return math.Min(100, sv/bv*100)
		}
		row := func(id, name, status, spend, budget string, hours [24]float64, b float64, untargeted bool) campaignData {
			f := buildForecast(lang, hours, b, now, status == "active")
			if untargeted {
				f.Alert, f.Outlook = "advisory", i18n.T(lang, "Not bidding: no target categories")
			}
			return campaignData{ID: id, Name: name, Status: status, SpendToday: spend, DailyBudget: budget,
				MaxCPM: "4.20", Clicks: 386, CTR: "0.94%", Untargeted: untargeted, Forecast: f, BudgetPct: pct(spend, budget)}
		}
		var acct [24]float64
		for i, v := range shape(1.1, 16) {
			acct[i] = v * 3
		}
		acctF := buildForecast(lang, acct, 400, now, true)
		data := pageData{
			Title: "Campaigns", Nav: "campaigns",
			User:      &model.User{Email: "adv@test", Role: model.RoleAdvertiser},
			AdvBudget: &advertiserBudget{DailyBudget: "400.00", Remaining: "343.00", SpendToday: "$57.00", BudgetMode: "manual"},
			AdvAvgCTR: "0.85%", AdvForecast: &acctF, AdvForecastAsOf: "15:40 JST", AdvBalance: "$482.00", AdvRunwayDays: 8,
			ListNav: &listNav{Page: 1, TotalPages: 1, Total: 5, From: 1, To: 5},
			Campaigns: []campaignData{
				row("c-14", "Autumn ryokan stays — Kyoto", "active", "8.20", "15.00", shape(.55, 16), 15, false),
				row("c-11", "Hokkaido ski packages", "active", "10.00", "10.00", shape(.8, 15), 8, false),
				row("c-09", "Okinawa family resorts", "paused", "0.00", "6.00", shape(0, 0), 6, false),
				row("c-07", "Onsen weekend deals", "active", "0.00", "8.00", shape(0, 0), 8, true),
				row("c-05", "Tokyo city hotels", "active", "6.10", "30.00", shape(.4, 16), 30, false),
			},
		}
		var sb strings.Builder
		if err := getPage(lang, "advertiser/campaigns.html").ExecuteTemplate(&sb, "layout", data); err != nil {
			t.Fatalf("%s: render: %v", lang, err)
		}
		html := sb.String()
		if n := strings.Count(html, `role="img"`); n != 6 {
			t.Errorf("%s: %d forecast strips, want 6 (account + 5 campaigns)", lang, n)
		}
		if dir := os.Getenv("PREVIEW_OUT"); dir != "" {
			if err := os.WriteFile(filepath.Join(dir, "advertiser-forecast-"+lang+".html"), []byte(html), 0o644); err != nil {
				t.Fatal(err)
			}
		}
	}
}

// TestWalletForecastRenders renders the wallet with its outlook strip;
// with PREVIEW_OUT set it writes advertiser-wallet-<lang>.
func TestWalletForecastRenders(t *testing.T) {
	SetFS(platform.Templates, platform.Static)
	today := time.Date(2026, 10, 21, 15, 40, 0, 0, time.UTC)
	charged := map[string]float64{}
	for i, v := range []float64{52, 61, 0, 48, 55, 70, 66, 59, 31, 44, 58, 63, 72, 57} {
		charged[today.AddDate(0, 0, -(i+1)).Format("2006-01-02")] = v
	}
	for _, lang := range []string{i18n.LangEN, i18n.LangJA} {
		f, _ := walletForecast(lang, charged, 482, 57, today)
		data := pageData{Title: "Wallet", Nav: "wallet", User: &model.User{Email: "adv@test", Role: model.RoleAdvertiser},
			Wallet: &walletPageData{Balance: "$482.00", Pending: "$57.00", Projected: "$425.00", Timezone: "Asia/Tokyo", Forecast: &f,
				Activity:  []walletActivityRow{{Date: "2026-10-18 10:02", Kind: "Top-up", Memo: "bank transfer", Amount: "+$300.00"}},
				Statement: []statementRow{{Day: "2026-10-20", CampaignID: "c-14", Campaign: "Autumn ryokan stays — Kyoto", SiteID: "travel-example-jp", Impressions: 4120, Amount: "31.45"}},
				Months:    []monthlyRow{{Month: "2026-10", Impressions: 81220, Gross: "702.10"}},
			}}
		var sb strings.Builder
		if err := getPage(lang, "advertiser/wallet.html").ExecuteTemplate(&sb, "layout", data); err != nil {
			t.Fatalf("%s: render: %v", lang, err)
		}
		if dir := os.Getenv("PREVIEW_OUT"); dir != "" {
			if err := os.WriteFile(filepath.Join(dir, "advertiser-wallet-"+lang+".html"), []byte(sb.String()), 0o644); err != nil {
				t.Fatal(err)
			}
		}
	}
}
