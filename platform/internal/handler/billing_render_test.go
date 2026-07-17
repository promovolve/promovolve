package handler

// Render smoke test: executes the billing page templates against the real
// embedded layout with representative data, so template/data mismatches
// (renamed fields, missing defines) fail in CI instead of at first click.

import (
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"io"
	"testing"

	platform "github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/model"
)

func TestBillingTemplatesRender(t *testing.T) {
	SetFS(platform.Templates, platform.Static)

	admin := &model.User{Email: "admin@test", Role: model.RoleAdmin}
	adv := &model.User{Email: "adv@test", Role: model.RoleAdvertiser}
	pub := &model.User{Email: "pub@test", Role: model.RolePublisher}
	nav := &listNav{Page: 1, TotalPages: 2, Total: 30, From: 1, To: 25, NextURL: "?page=2"}

	cases := []struct {
		name string
		data pageData
	}{
		{"admin/billing.html", pageData{
			Title: "Billing", Nav: "admin-billing", Tab: "overview", User: admin,
			AdminBilling: &adminBillingData{
				Cash: "1.00", Wallets: "1.00", Payables: "0.00", Revenue: "0.00",
				Clearing: "0.00",
				ReconOK:  true,
				Cursors: []settlementCursorRow{{
					Owner: "advertiser", Label: "Acme (@acme.com)", Timezone: "Asia/Tokyo",
					SettledUntil: "2026-07-04 09:00", Behind: true,
				}},
				Windows: []settlementHealthRow{{
					Owner: "advertiser", Label: "Acme (@acme.com)", LocalDay: "2026-07-04 Asia/Tokyo",
					Window: "07-03 15:00Z → 07-04 15:00Z", Rows: 1, Skipped: 1, Gross: "0.23",
				}},
				HealthBehind: true,
			},
		}},
		{"admin/billing-topups.html", pageData{
			Title: "Billing", Nav: "admin-billing", Tab: "topups", User: admin, ListNav: nav,
			AdminTopups: &adminTopupsData{
				Nonce:  "n",
				Topups: []topupRow{{Created: "2026-07-05", Kind: "topup", KindBadge: "badge-success", Memo: "wire", Wallet: "Acme (@acme.com)", Amount: "+$5.00"}},
			},
		}},
		{"admin/billing-payouts.html", pageData{
			Title: "Billing", Nav: "admin-billing", Tab: "payouts", User: admin, ListNav: nav,
			AdminPayouts: &adminPayoutsData{
				Nonce: "n",
				Queue: []payoutQueueRow{{PublisherID: "PUB1", Payable: "1.00", MinPayout: "50.00", Method: "bank", Over: true}},
				Payouts: []payoutRow{{
					ID: "id", PublisherID: "PUB1", Amount: "1.00", Period: "a → b",
					Status: "pending", StatusBadge: "badge-warning", Created: "2026-07-05", Pending: true,
				}},
			},
		}},
		{"admin/billing-journal.html", pageData{
			Title: "Billing", Nav: "admin-billing", Tab: "journal", User: admin, ListNav: nav,
			AdminJournal: &adminJournalData{
				Nonce: "n",
				Journal: []journalRow{{
					Created: "2026-07-05", Kind: "topup", KindBadge: "badge-success", Memo: "m",
					Summary: "Credited $1.00 to Acme (@acme.com)'s wallet",
					Legs: []journalLeg{
						{Label: "Acme (@acme.com)'s advertiser wallet", Amount: "+$1.00"},
						{Label: "platform cash", Amount: "+$1.00"},
					},
				}},
			},
		}},
		{"admin/billing-accounts.html", pageData{
			Title: "Billing", Nav: "admin-billing", Tab: "accounts", User: admin,
			AdminAccounts: &adminAccountsData{
				Query: "sport",
				Rows: []accountIndexRow{{
					Type: "publisher", ID: "PUB1", Label: "pub@x.co", Balance: "$1.00",
					Status: "active", StatusBadge: "badge-success", URL: "/admin/billing/accounts/publisher/PUB1",
				}},
			},
		}},
		{"admin/billing-account.html", pageData{
			Title: "Billing", Nav: "admin-billing", Tab: "accounts", User: admin,
			AdminAccount: &adminAccountDetailData{
				Type: "publisher", ID: "PUB1", Label: "pub@x.co", Balance: "$1.00",
				Status: "active", StatusBadge: "badge-success", IsPublisher: true, LifetimePaid: "2.00",
				Activity:  []walletActivityRow{{Date: "d", Kind: "payout", KindBadge: "badge-info", Memo: "m", Amount: "−$1.00"}},
				Statement: []statementRow{{Day: "d", CampaignID: "cid", Campaign: "Camp", SiteID: "s", Impressions: 1, Amount: "0.01"}},
				Months:    []monthlyRow{{Month: "2026-07", Impressions: 1, Gross: "0.01", Fee: "0.00", Net: "0.01"}},
				Payouts: []payoutRow{{
					ID: "id", PublisherID: "PUB1", PublisherLabel: "pub@x.co", Amount: "1.00",
					Period: "a → b", Status: "paid", StatusBadge: "badge-success", Created: "2026-07-05",
				}},
			},
		}},
		{"advertiser/wallet.html", pageData{
			Title: "Wallet", Nav: "wallet", User: adv,
			Wallet: &walletPageData{
				Balance: "4.77", Status: "low_balance", LowBalance: true, Timezone: "Asia/Tokyo",
				Activity:  []walletActivityRow{{Date: "d", Kind: "topup", KindBadge: "badge-success", Memo: "m", Amount: "+$5.00"}},
				Statement: []statementRow{{Day: "d", CampaignID: "c", SiteID: "s", Impressions: 1, Amount: "0.01"}},
				Months:    []monthlyRow{{Month: "2026-07", Impressions: 1, Gross: "0.01", Fee: "0.00", Net: "0.01"}},
			},
		}},
		{"publisher/earnings.html", pageData{
			Title: "Earnings", Nav: "earnings", User: pub,
			Earnings: &earningsPageData{
				Accrued: "0.20", LifetimePaid: "0.00", Timezone: "Asia/Tokyo",
				Payouts: []payoutRow{},
				Months:  []monthlyRow{{Month: "2026-07", Impressions: 1, Gross: "0.23", Fee: "0.03", Net: "0.20"}},
				Method:  "bank", MethodDetails: "details", MinPayout: "50.00", PayoutFloor: "50.00",
			},
		}},
		{"admin/settings.html", pageData{
			Title: "Settings", Nav: "admin-settings", User: admin,
			MarginPct: "15", PayoutFloor: "50.00",
			MarginHistory: []marginRow{{Percent: "15", EffectiveFrom: "2026-07-03", By: "a@b.c"}},
		}},
	}

	for _, c := range cases {
		for _, tlang := range []string{i18n.LangEN, i18n.LangJA} {
			if err := getPage(tlang, c.name).ExecuteTemplate(io.Discard, "layout", c.data); err != nil {
				t.Errorf("%s failed to render: %v", c.name, err)
			}
		}
	}
}
