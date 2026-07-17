package handler

// Billing dashboard pages (docs/design/BILLING.md Phase 4): the admin
// Billing section (overview + reconciliation, top-ups, payout queue,
// adjustments, journal, settlement health), the advertiser Wallet page,
// and the publisher Earnings page. All money is formatted to display
// strings here; templates only add the currency symbol.

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/hanishi/promovolve/platform/internal/billing"
	"github.com/hanishi/promovolve/platform/internal/model"
)

// ---------------------------------------------------------------------------
// display structs

// The admin billing section is four pages sharing a tab header
// (layout.html "billing-tabs"): Overview (read-only money position +
// settlement health), Top-ups (record form + money-in history), Payouts
// (queue + paginated history), and Journal (paginated ledger +
// adjustments).

type adminBillingData struct {
	Cash, Wallets, Payables, Revenue string
	// Clearing is settlement gross in transit between advertiser and
	// publisher local billing days — normally near zero; a balance that
	// never drains means unmapped traffic awaiting an operator fix.
	Clearing     string
	ReconOK      bool
	DriftCount   int
	Cursors      []settlementCursorRow
	Windows      []settlementHealthRow
	HealthBehind bool // some entity's next window is overdue
	// Pagination for the per-entity cursors table — one row per billing
	// entity, so it grows unbounded with account count.
	ListNav *listNav
}

// settlementCursorRow is one entity's settled-until cursor on the health
// panel.
type settlementCursorRow struct {
	Owner        string // "advertiser" | "publisher"
	Label        string // org email/label, falls back to the raw id
	Timezone     string // "UTC" when unset
	SettledUntil string
	Behind       bool
}

type adminTopupsData struct {
	Nonce  string
	Topups []topupRow // topup + refund transactions, the money-in ledger
}

// topupRow is one money-in event as the operator thinks of it: whose wallet,
// how much, signed. The balancing platform-cash leg is deliberately omitted —
// the Journal tab is the double-entry view.
type topupRow struct {
	Created   string
	Kind      string
	KindBadge string
	Wallet    string // org label of the advertiser wallet
	Amount    string // signed dollars
	Negative  bool   // refunds render red
	Memo      string
}

type adminPayoutsData struct {
	Nonce   string
	Queue   []payoutQueueRow
	Payouts []payoutRow
}

type adminJournalData struct {
	Nonce   string
	Journal []journalRow
	// KindFilter is the active ?kind= chip ("" = all kinds).
	KindFilter string
}

type adminAccountsData struct {
	Query string
	Rows  []accountIndexRow
}

type accountIndexRow struct {
	Type, ID, Label, Balance, Status, StatusBadge, URL string
}

type adminAccountDetailData struct {
	Type, ID, Label, Balance, Status, StatusBadge string
	IsPublisher                                   bool
	LifetimePaid                                  string // publisher only
	Activity                                      []walletActivityRow
	Statement                                     []statementRow
	Months                                        []monthlyRow
	Payouts                                       []payoutRow // publisher only
}

const billingPageSize = 25

type payoutQueueRow struct {
	PublisherID, PublisherLabel, Payable, MinPayout, Method string
	Over                                                    bool
}

type payoutRow struct {
	ID, PublisherID, Amount, Period, Status, StatusBadge, ExternalRef, Created string
	// PublisherLabel is the publisher's email when the platform knows it;
	// falls back to the raw ID (e.g. seeded/test accounts).
	PublisherLabel string
	Pending        bool
}

// journalRow is one ledger transaction: a human summary up front, the raw
// double-entry legs behind an expand.
type journalRow struct {
	Created, Kind, KindBadge, Memo string
	Summary                        string
	Legs                           []journalLeg
}

type journalLeg struct {
	Label    string
	Amount   string
	Negative bool
}

// settlementHealthRow is one settled window in the health journal.
type settlementHealthRow struct {
	Owner         string
	Label         string
	LocalDay      string // the window's local-day label + zone
	Window        string // instant range, for auditing
	Rows, Skipped int
	Gross         string
}

type walletPageData struct {
	Balance, Status       string
	Timezone              string // billing/budget day zone; "" hides the mention (UTC default)
	Suspended, LowBalance bool
	Activity              []walletActivityRow
	Statement             []statementRow
	Months                []monthlyRow
}

type walletActivityRow struct{ Date, Kind, KindBadge, Memo, Amount string }

type statementRow struct {
	Day, CampaignID, Campaign, SiteID string
	Impressions                       int64
	Amount                            string
}

type monthlyRow struct {
	Month           string
	Impressions     int64
	Gross, Fee, Net string
}

type earningsPageData struct {
	Accrued, LifetimePaid            string
	Timezone                         string // earnings-day zone; "" hides the mention (UTC default)
	Payouts                          []payoutRow
	Months                           []monthlyRow
	Method, MethodDetails, MinPayout string
	PayoutFloor                      string // platform minimum, shown as the form hint
}

// ---------------------------------------------------------------------------
// formatting helpers

func usd(micros int64) string { return fmt.Sprintf("%.2f", billing.Dollars(micros)) }

func signedUSD(micros int64) string {
	if micros < 0 {
		return "−$" + usd(-micros)
	}
	return "+$" + usd(micros)
}

// parseDollars converts a positive dollar form value to micros.
func parseDollars(s string) (int64, error) {
	v, err := strconv.ParseFloat(strings.TrimSpace(s), 64)
	if err != nil || v <= 0 || v > 1e7 {
		return 0, errors.New("amount must be a positive dollar value")
	}
	return billing.DollarsToMicros(v), nil
}

// formNonce is embedded in mutating billing forms and becomes the ledger
// idempotency key, so a double-clicked submit posts once.
func formNonce() string {
	b := make([]byte, 12)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// validNonce rejects missing/short nonces: with an empty nonce the
// idempotency key degenerates toward a constant, and every submission
// after the first would be a silent success-looking no-op.
func validNonce(n string) bool { return len(n) >= 16 }

// usdTile formats a signed amount for the big dashboard tiles, keeping the
// minus sign outside the currency symbol (−$12.34, not $-12.34).
func usdTile(micros int64) string {
	if micros < 0 {
		return "−$" + usd(-micros)
	}
	return "$" + usd(micros)
}

func kindBadge(k billing.TxnKind) string {
	switch k {
	case billing.TxnTopup:
		return "badge-success"
	case billing.TxnPayout:
		return "badge-info"
	case billing.TxnRefund:
		return "badge-danger"
	case billing.TxnAdjustment:
		return "badge-warning"
	default:
		return "badge-neutral"
	}
}

func statusBadge(s billing.AccountStatus) string {
	switch s {
	case billing.StatusSuspended:
		return "badge-danger"
	case billing.StatusLowBalance:
		return "badge-warning"
	default:
		return "badge-success"
	}
}

func payoutBadge(s billing.PayoutStatus) string {
	switch s {
	case billing.PayoutPaid:
		return "badge-success"
	case billing.PayoutPending:
		return "badge-warning"
	default:
		return "badge-neutral"
	}
}

func toPayoutRows(payouts []billing.Payout, loc *time.Location) []payoutRow {
	rows := make([]payoutRow, 0, len(payouts))
	for _, p := range payouts {
		rows = append(rows, payoutRow{
			ID:          p.ID,
			PublisherID: p.PublisherID,
			Amount:      usd(p.AmountMicros),
			Period:      p.PeriodStart.Format("2006-01-02") + " → " + p.PeriodEnd.Format("2006-01-02"),
			Status:      string(p.Status),
			StatusBadge: payoutBadge(p.Status),
			ExternalRef: p.ExternalRef,
			Created:     p.CreatedAt.In(loc).Format("2006-01-02 15:04"),
			Pending:     p.Status == billing.PayoutPending,
		})
	}
	return rows
}

func toMonthlyRows(months []billing.MonthlySpendRow) []monthlyRow {
	rows := make([]monthlyRow, 0, len(months))
	for _, m := range months {
		rows = append(rows, monthlyRow{
			Month:       m.Month,
			Impressions: m.Impressions,
			Gross:       usd(m.GrossMicros),
			Fee:         usd(m.FeeMicros),
			Net:         usd(m.NetMicros),
		})
	}
	return rows
}

// ---------------------------------------------------------------------------
// Admin billing

func (h *Handler) AdminBilling(w http.ResponseWriter, r *http.Request) {
	h.renderAdminBilling(w, r, "")
}

func (h *Handler) renderAdminBilling(w http.ResponseWriter, r *http.Request, errMsg string) {
	user, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	ctx := r.Context()
	data := &adminBillingData{}

	rec, err := h.billingSvc.Reconcile(ctx)
	if err != nil {
		slog.Error("billing reconcile failed", "error", err)
	} else {
		data.Cash = usdTile(rec.CashMicros)
		data.Wallets = usdTile(rec.WalletsMicros)
		data.Payables = usdTile(rec.PayableMicros)
		data.Revenue = usdTile(rec.RevenueMicros)
		data.Clearing = usdTile(rec.ClearingMicros)
		data.ReconOK = rec.OK
		data.DriftCount = len(rec.Drift)
	}

	labels := h.ownerLabels(ctx)
	if cursors, err := h.billingSvc.SettlementCursors(ctx); err == nil {
		all := make([]settlementCursorRow, 0, len(cursors))
		for _, c := range cursors {
			tz := c.Timezone
			if tz == "" {
				tz = "UTC"
			}
			all = append(all, settlementCursorRow{
				Owner:        string(c.OwnerType),
				Label:        labelOr(labels, c.OwnerID),
				Timezone:     tz,
				SettledUntil: c.SettledUntil.In(user.Location()).Format("2006-01-02 15:04"),
				Behind:       c.Behind,
			})
			// The health badge reflects ALL entities, not just the page shown.
			data.HealthBehind = data.HealthBehind || c.Behind
		}
		// Own page param ("cpage") so it never collides with another list on
		// the page; buildListNavParam clamps out-of-range pages.
		offset, end, nav := buildListNavParam(r, len(all), billingPageSize, "cpage")
		data.Cursors = all[offset:end]
		data.ListNav = nav
	}
	if windows, err := h.billingSvc.RecentSettlementWindows(ctx, 20); err == nil {
		for _, wd := range windows {
			tz := wd.Timezone
			if tz == "" {
				tz = "UTC"
			}
			data.Windows = append(data.Windows, settlementHealthRow{
				Owner:    string(wd.OwnerType),
				Label:    labelOr(labels, wd.OwnerID),
				LocalDay: wd.LocalDate.Format("2006-01-02") + " " + tz,
				Window: wd.WindowFrom.UTC().Format("01-02 15:04") + "Z → " +
					wd.WindowTo.UTC().Format("01-02 15:04") + "Z",
				Rows:    wd.RowsSettled,
				Skipped: wd.RowsSkipped,
				Gross:   usd(wd.GrossMicros),
			})
		}
	}

	h.render(w, r, "admin/billing.html", pageData{
		Title:        "Billing",
		Nav:          "admin-billing",
		Tab:          "overview",
		User:         user,
		Error:        errMsg,
		AdminBilling: data,
	})
}

func toJournalRows(txns []billing.Transaction, labels map[string]string, loc *time.Location) []journalRow {
	rows := make([]journalRow, 0, len(txns))
	for _, t := range txns {
		// Customer legs first, platform legs last, so every row reads
		// "who → platform" the same way regardless of DB entry order.
		entries := append([]billing.Entry(nil), t.Entries...)
		sort.SliceStable(entries, func(i, j int) bool {
			return entries[i].OwnerType != billing.OwnerPlatform && entries[j].OwnerType == billing.OwnerPlatform
		})
		legs := make([]journalLeg, 0, len(entries))
		for _, e := range entries {
			delta := billing.NaturalDelta(e.OwnerType, e.OwnerID, e.AmountMicros)
			legs = append(legs, journalLeg{
				Label:    legLabel(e, labels),
				Amount:   signedUSD(delta),
				Negative: delta < 0,
			})
		}
		rows = append(rows, journalRow{
			Created:   t.CreatedAt.In(loc).Format("2006-01-02 15:04"),
			Kind:      string(t.Kind),
			KindBadge: kindBadge(t.Kind),
			Memo:      t.Memo,
			Summary:   summarizeTxn(t, labels),
			Legs:      legs,
		})
	}
	return rows
}

// summarizeTxn turns one transaction's legs into the sentence an operator
// would say happened; the raw double-entry stays available on expand.
func summarizeTxn(t billing.Transaction, labels map[string]string) string {
	var advID, pubID string
	var advDelta, pubDelta int64
	for _, e := range t.Entries {
		d := billing.NaturalDelta(e.OwnerType, e.OwnerID, e.AmountMicros)
		switch e.OwnerType {
		case billing.OwnerAdvertiser:
			advID, advDelta = e.OwnerID, d
		case billing.OwnerPublisher:
			pubID, pubDelta = e.OwnerID, d
		}
	}
	abs := func(v int64) string {
		if v < 0 {
			v = -v
		}
		return "$" + usd(v)
	}
	switch t.Kind {
	case billing.TxnTopup:
		if advID != "" {
			return fmt.Sprintf("Credited %s to %s's wallet", abs(advDelta), labelOr(labels, advID))
		}
	case billing.TxnRefund:
		if advID != "" {
			return fmt.Sprintf("Refunded %s from %s's wallet", abs(advDelta), labelOr(labels, advID))
		}
	case billing.TxnSettlement:
		if advID != "" && pubID != "" {
			fee := -advDelta - pubDelta
			return fmt.Sprintf("%s paid %s — %s to %s, %s platform fee",
				labelOr(labels, advID), abs(advDelta), abs(pubDelta), labelOr(labels, pubID), abs(fee))
		}
	case billing.TxnPayout:
		if pubID != "" && pubDelta < 0 {
			return fmt.Sprintf("Paid out %s to %s", abs(pubDelta), labelOr(labels, pubID))
		}
		if pubID != "" {
			return fmt.Sprintf("Payout cancelled — %s back on %s's balance", abs(pubDelta), labelOr(labels, pubID))
		}
	case billing.TxnAdjustment:
		id, delta := advID, advDelta
		if id == "" {
			id, delta = pubID, pubDelta
		}
		if id != "" && delta >= 0 {
			return fmt.Sprintf("Credited %s to %s (from platform revenue)", abs(delta), labelOr(labels, id))
		}
		if id != "" {
			return fmt.Sprintf("Charged %s from %s (to platform revenue)", abs(delta), labelOr(labels, id))
		}
	}
	return string(t.Kind)
}

// legLabel names one ledger leg for humans. The old "{ownerType} {label}"
// concatenation produced gems like "advertiser publisher (@publisher.com)"
// once labels became org-based — the account type now reads as a possessive
// ("X's advertiser wallet"), which also explains why both legs of a top-up
// are positive: the customer's wallet AND the platform's cash both grew.
func legLabel(e billing.Entry, labels map[string]string) string {
	switch e.OwnerType {
	case billing.OwnerPlatform:
		return "platform " + e.OwnerID // the 'cash' / 'revenue' singletons
	case billing.OwnerAdvertiser:
		return labelOr(labels, e.OwnerID) + "'s advertiser wallet"
	default:
		return labelOr(labels, e.OwnerID) + "'s publisher earnings"
	}
}

func (h *Handler) AdminBillingTopups(w http.ResponseWriter, r *http.Request) {
	h.renderAdminTopups(w, r, "")
}

func (h *Handler) renderAdminTopups(w http.ResponseWriter, r *http.Request, errMsg string) {
	user, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	ctx := r.Context()
	data := &adminTopupsData{Nonce: formNonce()}

	moneyIn := []billing.TxnKind{billing.TxnTopup, billing.TxnRefund}
	total, err := h.billingSvc.CountTransactions(ctx, moneyIn...)
	if err != nil {
		slog.Error("count topups failed", "error", err)
	}
	offset, _, nav := buildListNav(r, total, billingPageSize)
	if txns, err := h.billingSvc.ListTransactions(ctx, billingPageSize, offset, moneyIn...); err == nil {
		labels := h.ownerLabels(ctx)
		for _, t := range txns {
			row := topupRow{
				Created:   t.CreatedAt.In(user.Location()).Format("2006-01-02 15:04"),
				Kind:      string(t.Kind),
				KindBadge: kindBadge(t.Kind),
				Memo:      t.Memo,
			}
			for _, e := range t.Entries {
				if e.OwnerType == billing.OwnerAdvertiser {
					delta := billing.NaturalDelta(e.OwnerType, e.OwnerID, e.AmountMicros)
					row.Wallet = labelOr(labels, e.OwnerID)
					row.Amount = signedUSD(delta)
					row.Negative = delta < 0
					break
				}
			}
			data.Topups = append(data.Topups, row)
		}
	}

	h.render(w, r, "admin/billing-topups.html", pageData{
		Title:       "Billing · Top-ups",
		Nav:         "admin-billing",
		Tab:         "topups",
		User:        user,
		Error:       errMsg,
		AdminTopups: data,
		ListNav:     nav,
	})
}

func (h *Handler) AdminBillingPayouts(w http.ResponseWriter, r *http.Request) {
	h.renderAdminPayouts(w, r, "")
}

func (h *Handler) renderAdminPayouts(w http.ResponseWriter, r *http.Request, errMsg string) {
	user, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	ctx := r.Context()
	data := &adminPayoutsData{Nonce: formNonce()}
	labels := h.ownerLabels(ctx)

	if queue, err := h.billingSvc.PayoutQueue(ctx); err == nil {
		for _, q := range queue {
			data.Queue = append(data.Queue, payoutQueueRow{
				PublisherID:    q.PublisherID,
				PublisherLabel: labelOr(labels, q.PublisherID),
				Payable:        usd(q.PayableMicros),
				MinPayout:      usd(q.MinPayoutMicros),
				Method:         q.Method,
				Over:           q.OverThreshold,
			})
		}
	}

	total, err := h.billingSvc.CountPayouts(ctx, "")
	if err != nil {
		slog.Error("count payouts failed", "error", err)
	}
	offset, _, nav := buildListNav(r, total, billingPageSize)
	if payouts, err := h.billingSvc.ListPayouts(ctx, "", billingPageSize, offset); err == nil {
		data.Payouts = toPayoutRows(payouts, user.Location())
		for i := range data.Payouts {
			data.Payouts[i].PublisherLabel = labelOr(labels, data.Payouts[i].PublisherID)
		}
	}

	h.render(w, r, "admin/billing-payouts.html", pageData{
		Title:        "Billing · Payouts",
		Nav:          "admin-billing",
		Tab:          "payouts",
		User:         user,
		Error:        errMsg,
		AdminPayouts: data,
		ListNav:      nav,
	})
}

func (h *Handler) AdminBillingJournal(w http.ResponseWriter, r *http.Request) {
	h.renderAdminJournal(w, r, "")
}

func (h *Handler) renderAdminJournal(w http.ResponseWriter, r *http.Request, errMsg string) {
	user, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	ctx := r.Context()
	data := &adminJournalData{Nonce: formNonce()}
	labels := h.ownerLabels(ctx)

	// ?kind= narrows to one transaction kind; anything unrecognized = all.
	var kinds []billing.TxnKind
	switch k := billing.TxnKind(r.URL.Query().Get("kind")); k {
	case billing.TxnTopup, billing.TxnSettlement, billing.TxnPayout, billing.TxnAdjustment, billing.TxnRefund:
		kinds = []billing.TxnKind{k}
		data.KindFilter = string(k)
	}

	total, err := h.billingSvc.CountTransactions(ctx, kinds...)
	if err != nil {
		slog.Error("count transactions failed", "error", err)
	}
	offset, _, nav := buildListNav(r, total, billingPageSize)
	if txns, err := h.billingSvc.ListTransactions(ctx, billingPageSize, offset, kinds...); err == nil {
		data.Journal = toJournalRows(txns, labels, user.Location())
	}

	h.render(w, r, "admin/billing-journal.html", pageData{
		Title:        "Billing · Journal",
		Nav:          "admin-billing",
		Tab:          "journal",
		User:         user,
		Error:        errMsg,
		AdminJournal: data,
		ListNav:      nav,
	})
}

// AdminBillingAccounts is the searchable index of every advertiser wallet
// and publisher payable.
func (h *Handler) AdminBillingAccounts(w http.ResponseWriter, r *http.Request) {
	user, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	ctx := r.Context()
	labels := h.ownerLabels(ctx)
	q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	data := &adminAccountsData{Query: r.URL.Query().Get("q")}

	var rows []accountIndexRow
	if accounts, err := h.billingSvc.ListAccounts(ctx); err == nil {
		for _, a := range accounts {
			label := labelOr(labels, a.OwnerID)
			if q != "" && !strings.Contains(strings.ToLower(label), q) &&
				!strings.Contains(strings.ToLower(a.OwnerID), q) {
				continue
			}
			rows = append(rows, accountIndexRow{
				Type:        string(a.OwnerType),
				ID:          a.OwnerID,
				Label:       label,
				Balance:     usdTile(a.BalanceMicros),
				Status:      string(a.Status),
				StatusBadge: statusBadge(a.Status),
				URL:         "/admin/billing/accounts/" + string(a.OwnerType) + "/" + a.OwnerID,
			})
		}
	}
	// Paginate the search-filtered set. buildListNav preserves the ?q= term in
	// the prev/next links, so paging within a search keeps the filter.
	offset, end, nav := buildListNav(r, len(rows), billingPageSize)
	data.Rows = rows[offset:end]

	h.render(w, r, "admin/billing-accounts.html", pageData{
		Title:         "Billing · Accounts",
		Nav:           "admin-billing",
		Tab:           "accounts",
		User:          user,
		AdminAccounts: data,
		ListNav:       nav,
	})
}

// AdminBillingAccount is the per-account drill-down: identity, balance,
// money-in activity, settled statement, monthly totals, payouts.
func (h *Handler) AdminBillingAccount(w http.ResponseWriter, r *http.Request) {
	user, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	ctx := r.Context()
	ownerType := billing.OwnerType(r.PathValue("ownerType"))
	ownerID := r.PathValue("ownerID")
	if ownerType != billing.OwnerAdvertiser && ownerType != billing.OwnerPublisher || ownerID == "" {
		http.NotFound(w, r)
		return
	}

	labels := h.ownerLabels(ctx)
	data := &adminAccountDetailData{
		Type:        string(ownerType),
		ID:          ownerID,
		Label:       labelOr(labels, ownerID),
		Balance:     "$0.00",
		Status:      string(billing.StatusActive),
		StatusBadge: "badge-neutral",
		IsPublisher: ownerType == billing.OwnerPublisher,
	}
	if acc, err := h.billingSvc.GetAccount(ctx, ownerType, ownerID); err == nil {
		data.Balance = usdTile(acc.BalanceMicros)
		data.Status = string(acc.Status)
		data.StatusBadge = statusBadge(acc.Status)
	}
	if activity, err := h.billingSvc.ListAccountActivity(ctx, ownerType, ownerID, 20); err == nil {
		for _, a := range activity {
			data.Activity = append(data.Activity, walletActivityRow{
				Date:      a.CreatedAt.Format("2006-01-02 15:04"),
				Kind:      string(a.Kind),
				KindBadge: kindBadge(a.Kind),
				Memo:      a.Memo,
				Amount:    signedUSD(a.AmountMicros),
			})
		}
	}
	switch ownerType {
	case billing.OwnerAdvertiser:
		if settlements, err := h.billingSvc.ListAdvertiserSettlements(ctx, ownerID, 30); err == nil {
			names := h.campaignNames("/v1/advertisers/"+ownerID+"/campaigns?limit=100", nil)
			for _, st := range settlements {
				data.Statement = append(data.Statement, statementRow{
					Day:         st.LocalDate.Format("2006-01-02"),
					CampaignID:  st.CampaignID,
					Campaign:    labelOr(names, st.CampaignID),
					SiteID:      st.SiteID,
					Impressions: st.Impressions,
					Amount:      usd(st.GrossMicros), // advertisers pay gross
				})
			}
		}
	case billing.OwnerPublisher:
		if settlements, err := h.billingSvc.ListPublisherSettlements(ctx, ownerID, 30); err == nil {
			for _, st := range settlements {
				data.Statement = append(data.Statement, statementRow{
					Day:         st.LocalDate.Format("2006-01-02"),
					CampaignID:  st.CampaignID,
					Campaign:    st.CampaignID,
					SiteID:      st.SiteID,
					Impressions: st.Impressions,
					Amount:      usd(st.NetMicros), // publishers earn net
				})
			}
		}
	}
	if months, err := h.billingSvc.MonthlySettled(ctx, ownerType, ownerID, 12); err == nil {
		data.Months = toMonthlyRows(months)
	}
	if ownerType == billing.OwnerPublisher {
		if paid, err := h.billingSvc.LifetimePaid(ctx, ownerID); err == nil {
			data.LifetimePaid = usd(paid)
		}
		if payouts, err := h.billingSvc.ListPayouts(ctx, ownerID, 20, 0); err == nil {
			data.Payouts = toPayoutRows(payouts, user.Location())
			for i := range data.Payouts {
				data.Payouts[i].PublisherLabel = data.Label
			}
		}
	}

	h.render(w, r, "admin/billing-account.html", pageData{
		Title:        "Billing · " + data.Label,
		Nav:          "admin-billing",
		Tab:          "accounts",
		User:         user,
		AdminAccount: data,
	})
}

// orgSuspendedMsg returns a refusal message when the entity's org is
// operator-suspended — NEW money movements (top-ups, payouts, adjustments)
// are frozen with the rest of the relationship. Bookkeeping of pre-existing
// records (mark-paid, cancel) stays allowed. Lookup errors refuse too:
// fail-closed for money.
func (h *Handler) orgSuspendedMsg(ctx context.Context, entityID string) string {
	suspended, err := h.orgRepo.IsEntitySuspended(ctx, entityID)
	if err != nil {
		slog.Error("org suspension lookup failed", "entityId", entityID, "error", err)
		return "could not verify the organization's status — no money was moved; try again"
	}
	if suspended {
		return "this organization is suspended — resume it from the Users page before moving money"
	}
	return ""
}

func (h *Handler) AdminRecordTopup(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	if !validNonce(r.FormValue("nonce")) {
		h.renderAdminTopups(w, r, "stale or invalid form — reload the page and try again")
		return
	}
	advertiserID := strings.TrimSpace(r.FormValue("advertiserId"))
	amount, err := parseDollars(r.FormValue("amount"))
	if advertiserID == "" || err != nil {
		h.renderAdminTopups(w, r, "top-up needs an advertiser and a positive amount")
		return
	}
	if !h.ownerExists(r.Context(), billing.OwnerAdvertiser, advertiserID) {
		h.renderAdminTopups(w, r, "unknown advertiser: "+advertiserID)
		return
	}
	if msg := h.orgSuspendedMsg(r.Context(), advertiserID); msg != "" {
		h.renderAdminTopups(w, r, msg)
		return
	}
	res, err := h.billingSvc.RecordTopup(r.Context(), billing.TopupParams{
		AdvertiserID: advertiserID,
		AmountMicros: amount,
		Memo:         strings.TrimSpace(r.FormValue("memo")),
		CreatedBy:    admin.ID,
		// The key includes the identifying fields, not just the per-render
		// nonce: a double-click (same values) dedupes, but a stale
		// back-button resubmit with a DIFFERENT advertiser or amount must
		// post rather than silently no-op behind a success redirect.
		IdempotencyKey: fmt.Sprintf("topup:%s:%s:%d", r.FormValue("nonce"), advertiserID, amount),
	})
	if err != nil {
		h.renderAdminTopups(w, r, "top-up failed: "+err.Error())
		return
	}
	if res.Reactivated {
		// The wallet was suspended and is funded again — resume serving now
		// rather than waiting for the next settlement pass.
		if err := h.settler.ResumeServing(r.Context(), advertiserID); err != nil {
			slog.Error("core resume after top-up failed; settlement pass will retry",
				"advertiserId", advertiserID, "error", err)
		}
	}
	http.Redirect(w, r, "/admin/billing/topups", http.StatusSeeOther)
}

func (h *Handler) AdminCreatePayout(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	if !validNonce(r.FormValue("nonce")) {
		h.renderAdminPayouts(w, r, "stale or invalid form — reload the page and try again")
		return
	}
	publisherID := strings.TrimSpace(r.FormValue("publisherId"))
	if publisherID == "" {
		h.renderAdminPayouts(w, r, "payout needs a publisher")
		return
	}
	if msg := h.orgSuspendedMsg(r.Context(), publisherID); msg != "" {
		h.renderAdminPayouts(w, r, msg)
		return
	}
	// Payout periods are publisher-local days, matching the earnings
	// statement; "yesterday" is computed in the publisher org's zone.
	loc := time.UTC
	if tz, err := h.orgRepo.TimezoneByEntity(r.Context(), publisherID); err == nil && tz != "" {
		if l, lerr := time.LoadLocation(tz); lerr == nil {
			loc = l
		}
	}
	y, m, d := time.Now().In(loc).Date()
	end := time.Date(y, m, d, 0, 0, 0, 0, time.UTC).AddDate(0, 0, -1)
	start, found, err := h.billingSvc.PayoutPeriodStart(r.Context(), publisherID)
	if err != nil || !found || start.After(end) {
		start = end
	}
	if _, err := h.billingSvc.CreatePayout(r.Context(), billing.PayoutParams{
		PublisherID:    publisherID,
		PeriodStart:    start,
		PeriodEnd:      end,
		CreatedBy:      admin.ID,
		IdempotencyKey: fmt.Sprintf("payout:%s:%s", r.FormValue("nonce"), publisherID),
	}); err != nil {
		h.renderAdminPayouts(w, r, "payout failed: "+err.Error())
		return
	}
	http.Redirect(w, r, "/admin/billing/payouts", http.StatusSeeOther)
}

func (h *Handler) AdminMarkPayoutPaid(w http.ResponseWriter, r *http.Request) {
	if _, _, ok := h.requireRole(w, r, model.RoleAdmin); !ok {
		return
	}
	r.ParseForm()
	ref := strings.TrimSpace(r.FormValue("externalRef"))
	if ref == "" {
		h.renderAdminPayouts(w, r, "mark paid needs the transfer reference")
		return
	}
	if err := h.billingSvc.MarkPayoutPaid(r.Context(), r.FormValue("payoutId"), ref); err != nil {
		h.renderAdminPayouts(w, r, "mark paid failed: "+err.Error())
		return
	}
	http.Redirect(w, r, "/admin/billing/payouts", http.StatusSeeOther)
}

func (h *Handler) AdminCancelPayout(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	if err := h.billingSvc.CancelPayout(r.Context(), r.FormValue("payoutId"), admin.ID); err != nil {
		h.renderAdminPayouts(w, r, "cancel failed: "+err.Error())
		return
	}
	http.Redirect(w, r, "/admin/billing/payouts", http.StatusSeeOther)
}

// AdminAdjust posts a manual money movement: a revenue-funded credit, a
// charge back to revenue, or an advertiser refund (money leaves via cash).
func (h *Handler) AdminAdjust(w http.ResponseWriter, r *http.Request) {
	admin, _, ok := h.requireRole(w, r, model.RoleAdmin)
	if !ok {
		return
	}
	r.ParseForm()
	if !validNonce(r.FormValue("nonce")) {
		h.renderAdminJournal(w, r, "stale or invalid form — reload the page and try again")
		return
	}
	action := r.FormValue("action")
	ownerTypeStr, ownerID, _ := strings.Cut(r.FormValue("owner"), "|")
	ownerType := billing.OwnerType(ownerTypeStr)
	ownerID = strings.TrimSpace(ownerID)
	memo := strings.TrimSpace(r.FormValue("memo"))
	amount, err := parseDollars(r.FormValue("amount"))
	if ownerID == "" || memo == "" || err != nil {
		h.renderAdminJournal(w, r, "adjustment needs an account, a positive amount, and a memo")
		return
	}
	if ownerType != billing.OwnerAdvertiser && ownerType != billing.OwnerPublisher {
		h.renderAdminJournal(w, r, "adjustment target must be an advertiser or publisher")
		return
	}
	if !h.ownerExists(r.Context(), ownerType, ownerID) {
		h.renderAdminJournal(w, r, "unknown "+string(ownerType)+" ID: "+ownerID+" — no money was moved")
		return
	}
	if msg := h.orgSuspendedMsg(r.Context(), ownerID); msg != "" {
		h.renderAdminJournal(w, r, msg)
		return
	}
	idem := fmt.Sprintf("adj:%s:%s:%s:%s:%d", r.FormValue("nonce"), action, ownerType, ownerID, amount)

	switch action {
	case "credit", "charge":
		// Credits are funded by (charges returned to) platform revenue, so
		// giving margin away shows up as negative captured revenue.
		sign := int64(-1)
		if action == "charge" {
			sign = 1
		}
		_, _, err = h.billingSvc.Adjust(r.Context(), billing.AdjustParams{
			Kind: billing.TxnAdjustment,
			Legs: []billing.Leg{
				{OwnerType: ownerType, OwnerID: ownerID, AmountMicros: sign * amount},
				{OwnerType: billing.OwnerPlatform, OwnerID: billing.PlatformRevenue, AmountMicros: -sign * amount},
			},
			Memo:           memo,
			CreatedBy:      admin.ID,
			IdempotencyKey: idem,
		})
	case "refund":
		if ownerType != billing.OwnerAdvertiser {
			h.renderAdminJournal(w, r, "refunds return advertiser wallet funds; pick an advertiser")
			return
		}
		_, err = h.billingSvc.RefundAdvertiser(r.Context(), ownerID, amount, memo, admin.ID, idem)
	default:
		h.renderAdminJournal(w, r, "unknown adjustment type")
		return
	}
	if err != nil {
		h.renderAdminJournal(w, r, "adjustment failed: "+err.Error())
		return
	}
	http.Redirect(w, r, "/admin/billing/journal", http.StatusSeeOther)
}

// campaignNames resolves an advertiser's campaign IDs to display names via
// the core API; path is either the claims-scoped "me" form or an explicit
// advertiser path (admin views, claims nil). Failures degrade to raw IDs.
func (h *Handler) campaignNames(path string, claims *model.Claims) map[string]string {
	names := map[string]string{}
	body, err := h.coreGet(path, claims)
	if err != nil {
		return names
	}
	var resp struct {
		Data []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"data"`
	}
	if json.Unmarshal(body, &resp) == nil {
		for _, c := range resp.Data {
			if c.Name != "" {
				names[c.ID] = c.Name
			}
		}
	}
	return names
}

// entityRegistry is the billing pages' view of who owns which core entity:
// per-side id→label maps. Orgs are the source of truth — a side added via an
// org side-request exists only on the orgs row, never on a platform_users
// row — and the label is always the ORG ("name (@domain)"): the wallet is
// the org's money, so labeling it with one member's email would both read
// inconsistently next to side-request entities and mislead once an org has
// several members. User emails are only a fallback for org-less legacy rows.
type entityRegistry struct {
	advertisers map[string]string
	publishers  map[string]string
}

func (r entityRegistry) bySide(side billing.OwnerType) map[string]string {
	if side == billing.OwnerPublisher {
		return r.publishers
	}
	return r.advertisers
}

func (h *Handler) loadEntityRegistry(ctx context.Context) entityRegistry {
	reg := entityRegistry{advertisers: map[string]string{}, publishers: map[string]string{}}
	sides, err := h.orgRepo.EntitySides(ctx)
	if err != nil {
		slog.Error("list org entity sides failed", "error", err)
	}
	for _, e := range sides {
		label := "@" + e.Domain
		if e.Name != "" {
			label = e.Name + " (@" + e.Domain + ")"
		}
		if e.Side == model.RolePublisher {
			reg.publishers[e.EntityID] = label
		} else {
			reg.advertisers[e.EntityID] = label
		}
	}
	if users, err := h.userSvc.ListAll(ctx); err == nil {
		for _, u := range users {
			if u.PublisherID != nil && *u.PublisherID != "" {
				if _, ok := reg.publishers[*u.PublisherID]; !ok {
					reg.publishers[*u.PublisherID] = u.Email
				}
			}
			if u.AdvertiserID != nil && *u.AdvertiserID != "" {
				if _, ok := reg.advertisers[*u.AdvertiserID]; !ok {
					reg.advertisers[*u.AdvertiserID] = u.Email
				}
			}
		}
	}
	return reg
}

// SearchBillingAccounts feeds the admin billing comboboxes (top-up and
// adjustment). The search itself runs in Postgres (org name/domain/entity
// id/member email, LIMIT applied there); ?side=advertiser narrows for
// top-ups and refunds.
func (h *Handler) SearchBillingAccounts(w http.ResponseWriter, r *http.Request) {
	user, _ := h.sessionUser(r)
	if user == nil || user.Role != model.RoleAdmin {
		http.Error(w, `{"error":"forbidden"}`, http.StatusForbidden)
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	side := model.Role(r.URL.Query().Get("side"))
	if side != model.RoleAdvertiser && side != model.RolePublisher {
		side = ""
	}
	sides, err := h.orgRepo.SearchEntitySides(r.Context(), q, side, 8)
	if err != nil {
		slog.Error("billing account search failed", "error", err)
		http.Error(w, `{"data":[]}`, http.StatusOK)
		return
	}
	type accountHit struct {
		ID    string `json:"id"`
		Side  string `json:"side"`
		Label string `json:"label"`
	}
	hits := make([]accountHit, 0, len(sides))
	for _, e := range sides {
		label := "@" + e.Domain
		if e.Name != "" {
			label = e.Name + " (@" + e.Domain + ")"
		}
		hits = append(hits, accountHit{ID: e.EntityID, Side: string(e.Side), Label: label})
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"data": hits})
}

// ownerLabels maps core advertiser AND publisher IDs to display labels —
// admins shouldn't have to read ULIDs. IDs are ULIDs, so one map is
// collision-free across both types. Unknown IDs are left as-is.
func (h *Handler) ownerLabels(ctx context.Context) map[string]string {
	reg := h.loadEntityRegistry(ctx)
	labels := reg.advertisers
	for id, l := range reg.publishers {
		labels[id] = l
	}
	return labels
}

func labelOr(labels map[string]string, id string) string {
	if l, ok := labels[id]; ok {
		return l
	}
	return id
}

// ownerExists checks a manual-money target against the entity registry,
// so a typo'd ID can't strand funds in an orphan ledger account (the books
// would still balance, which is exactly why nothing else would catch it).
func (h *Handler) ownerExists(ctx context.Context, ownerType billing.OwnerType, ownerID string) bool {
	_, ok := h.loadEntityRegistry(ctx).bySide(ownerType)[ownerID]
	return ok
}

// walletSummary returns the balance display and notice for embedding on
// the advertiser Account page next to the Daily Budget — the two get
// confused ("budget is set but nothing serves"): budget is a pacing
// throttle, the wallet is the actual money. A never-funded advertiser has
// no account row yet; that must still render as $0.00 + unfunded notice,
// not silence.
func (h *Handler) walletSummary(ctx context.Context, advertiserID string) (balance string, unfunded bool) {
	if advertiserID == "" {
		return "$0.00", true
	}
	acc, err := h.billingSvc.GetAccount(ctx, billing.OwnerAdvertiser, advertiserID)
	if err != nil {
		return "$0.00", true // no row yet = never funded
	}
	return usdTile(acc.BalanceMicros), acc.BalanceMicros <= 0
}

// walletNotice summarizes an advertiser's wallet status for the banner on
// the Account/Stats pages: "suspended", "low", or "" when all is well.
func (h *Handler) walletNotice(ctx context.Context, advertiserID string) string {
	if h.billingSvc == nil || advertiserID == "" {
		return ""
	}
	acc, err := h.billingSvc.GetAccount(ctx, billing.OwnerAdvertiser, advertiserID)
	if err != nil {
		return ""
	}
	switch acc.Status {
	case billing.StatusSuspended:
		return "suspended"
	case billing.StatusLowBalance:
		return "low"
	}
	return ""
}

// ---------------------------------------------------------------------------
// Advertiser wallet

func (h *Handler) AdvertiserWallet(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	ctx := r.Context()
	advertiserID := claims.AdvertiserID
	data := &walletPageData{Balance: "$0.00", Status: string(billing.StatusActive)}
	if tz, err := h.orgRepo.TimezoneByEntity(ctx, advertiserID); err == nil {
		data.Timezone = tz
	}

	if acc, err := h.billingSvc.GetAccount(ctx, billing.OwnerAdvertiser, advertiserID); err == nil {
		data.Balance = usdTile(acc.BalanceMicros)
		data.Status = string(acc.Status)
		data.Suspended = acc.Status == billing.StatusSuspended
		data.LowBalance = acc.Status == billing.StatusLowBalance
	}
	if activity, err := h.billingSvc.ListAccountActivity(ctx, billing.OwnerAdvertiser, advertiserID, 20); err == nil {
		for _, a := range activity {
			data.Activity = append(data.Activity, walletActivityRow{
				Date:      a.CreatedAt.Format("2006-01-02 15:04"),
				Kind:      string(a.Kind),
				KindBadge: kindBadge(a.Kind),
				Memo:      a.Memo,
				Amount:    signedUSD(a.AmountMicros),
			})
		}
	}
	if settlements, err := h.billingSvc.ListAdvertiserSettlements(ctx, advertiserID, 30); err == nil {
		names := h.campaignNames("/v1/advertisers/me/campaigns?limit=100", claims)
		for _, st := range settlements {
			data.Statement = append(data.Statement, statementRow{
				Day:         st.LocalDate.Format("2006-01-02"),
				CampaignID:  st.CampaignID,
				Campaign:    labelOr(names, st.CampaignID),
				SiteID:      st.SiteID,
				Impressions: st.Impressions,
				Amount:      usd(st.GrossMicros), // advertisers pay gross
			})
		}
	}
	if months, err := h.billingSvc.MonthlySettled(ctx, billing.OwnerAdvertiser, advertiserID, 12); err == nil {
		data.Months = toMonthlyRows(months)
	}

	h.render(w, r, "advertiser/wallet.html", pageData{
		Title:  "Wallet",
		Nav:    "wallet",
		User:   user,
		Wallet: data,
	})
}

// ---------------------------------------------------------------------------
// Publisher earnings

func (h *Handler) PublisherEarnings(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	ctx := r.Context()
	publisherID := claims.PublisherID
	data := &earningsPageData{Accrued: "0.00", LifetimePaid: "0.00"}
	if tz, err := h.orgRepo.TimezoneByEntity(ctx, publisherID); err == nil {
		data.Timezone = tz
	}

	if acc, err := h.billingSvc.GetAccount(ctx, billing.OwnerPublisher, publisherID); err == nil {
		data.Accrued = usd(acc.BalanceMicros)
	}
	if paid, err := h.billingSvc.LifetimePaid(ctx, publisherID); err == nil {
		data.LifetimePaid = usd(paid)
	}
	if payouts, err := h.billingSvc.ListPayouts(ctx, publisherID, 20, 0); err == nil {
		data.Payouts = toPayoutRows(payouts, user.Location())
	}
	if months, err := h.billingSvc.MonthlySettled(ctx, billing.OwnerPublisher, publisherID, 12); err == nil {
		data.Months = toMonthlyRows(months)
	}
	floor, ferr := h.billingSvc.PayoutFloorMicros(ctx)
	if ferr != nil {
		floor = billing.DefaultMinPayoutMicros
	}
	data.PayoutFloor = usd(floor)
	if method, err := h.billingSvc.GetPayoutMethod(ctx, publisherID); err == nil {
		data.Method = method.Method
		data.MethodDetails = method.Details
		// Display the effective threshold: the platform floor overrides a
		// lower stored preference.
		data.MinPayout = usd(max(method.MinPayoutMicros, floor))
	}

	h.render(w, r, "publisher/earnings.html", pageData{
		Title:    "Earnings",
		Nav:      "earnings",
		User:     user,
		Earnings: data,
	})
}

func (h *Handler) UpdatePayoutMethod(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	floor, ferr := h.billingSvc.PayoutFloorMicros(r.Context())
	if ferr != nil {
		floor = billing.DefaultMinPayoutMicros
	}
	minPayout, err := parseDollars(r.FormValue("minPayout"))
	if err != nil || minPayout < floor {
		// Publishers can raise their threshold above the platform floor,
		// never below it.
		minPayout = floor
	}
	if err := h.billingSvc.UpsertPayoutMethod(r.Context(), billing.PayoutMethod{
		PublisherID:     claims.PublisherID,
		Method:          strings.TrimSpace(r.FormValue("method")),
		Details:         strings.TrimSpace(r.FormValue("details")),
		MinPayoutMicros: minPayout,
	}); err != nil {
		slog.Error("payout method update failed", "publisherId", claims.PublisherID, "error", err)
	}
	http.Redirect(w, r, "/publisher/earnings", http.StatusSeeOther)
}
