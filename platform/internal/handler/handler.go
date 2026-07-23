package handler

import (
	"context"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html/template"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/hanishi/promovolve/platform/internal/audit"
	"github.com/hanishi/promovolve/platform/internal/auth"
	"github.com/hanishi/promovolve/platform/internal/billing"
	"github.com/hanishi/promovolve/platform/internal/i18n"
	"github.com/hanishi/promovolve/platform/internal/model"
	"github.com/hanishi/promovolve/platform/internal/org"
	"github.com/hanishi/promovolve/platform/internal/passkey"
	"github.com/hanishi/promovolve/platform/internal/settings"
	"github.com/hanishi/promovolve/platform/internal/setup"
	"github.com/hanishi/promovolve/platform/internal/siterequest"
)

// coreClient bounds request/response calls to the core API so a slow or hung
// core endpoint can't block a user-facing request indefinitely. The worst
// offender is /design → import-urls, which re-fetches page images server-side;
// a third-party image that a bot-manager tarpits would otherwise hang the
// whole "Generating creative…" navigation until an upstream proxy 504s.
// (SSE/streaming calls intentionally do NOT use this client.)
var coreClient = &http.Client{Timeout: 30 * time.Second}

var (
	templateFSVar embed.FS
	staticFSVar   embed.FS
	// staticVersion is a content hash of the embedded static assets,
	// computed once at boot. Templates reference assets through the
	// `asset` func as /static/x.js?v=<hash>, which lets the static handler
	// serve them immutable-cacheable: a new build changes the hash, so
	// clients never see stale files and never re-download fresh ones.
	staticVersion string
)

func SetFS(templates, static embed.FS) {
	templateFSVar = templates
	staticFSVar = static
	h := sha256.New()
	fs.WalkDir(static, ".", func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		b, err := static.ReadFile(path)
		if err == nil {
			h.Write([]byte(path))
			h.Write(b)
		}
		return nil
	})
	staticVersion = hex.EncodeToString(h.Sum(nil))[:10]
}

// StaticVersion exposes the asset hash to the router (cache-header split).
func StaticVersion() string { return staticVersion }

type Handler struct {
	coreAPIURL      string
	bannerScriptURL string
	jwtSvc          *auth.JWTService
	userSvc         UserService
	passkeySvc      *passkey.Service
	setupSvc        *setup.Service
	settingsSvc     *settings.Service
	billingSvc      *billing.Service
	siteReqSvc      *siterequest.Service
	orgRepo         *org.Repository
	auditRepo       *audit.Repository
	// settler is only used to resume core serving immediately after an
	// admin tops up a suspended wallet; the job itself runs from main.
	settler *billing.Settler
	// orgCore reaches the core /v1/internal endpoints for the operator
	// org suspend/resume cascade.
	orgCore *billing.HTTPCoreClient
	// devAuth mirrors config.DevAuth: renders the legacy password forms and
	// accepts password POSTs on /login. Never true in production.
	devAuth bool
	// secureCookies marks session cookies Secure; true whenever the WebAuthn
	// RP ID is a real domain (deployed behind HTTPS).
	secureCookies bool
}

// Deps bundles constructor dependencies — the list outgrew positional args.
type Deps struct {
	CoreAPIURL      string
	BannerScriptURL string
	JWTSvc          *auth.JWTService
	UserSvc         UserService
	PasskeySvc      *passkey.Service
	SetupSvc        *setup.Service
	SettingsSvc     *settings.Service
	BillingSvc      *billing.Service
	SiteReqSvc      *siterequest.Service
	OrgRepo         *org.Repository
	AuditRepo       *audit.Repository
	Settler         *billing.Settler
	// OrgCore reaches the core's /v1/internal endpoints (X-Internal-Key) for
	// the org suspend/resume cascade — the operator-privileged channel.
	OrgCore       *billing.HTTPCoreClient
	DevAuth       bool
	SecureCookies bool
}

type UserService interface {
	Register(ctx context.Context, email, password, displayName string, role model.Role) (*model.User, error)
	Authenticate(ctx context.Context, email, password string) (*model.User, error)
	GetByID(ctx context.Context, id string) (*model.User, error)
	GetByEmail(ctx context.Context, email string) (*model.User, error)
	// Account-request lifecycle
	CreatePendingUser(ctx context.Context, u *model.User, saveExtra func(tx pgx.Tx) error) error
	Approve(ctx context.Context, userID, reviewerID string) error
	Reject(ctx context.Context, userID, reviewerID string) error
	ListByStatus(ctx context.Context, status model.UserStatus) ([]model.User, error)
	ListAll(ctx context.Context) ([]model.User, error)
	// Per-user preferences (display name, timezone)
	Update(ctx context.Context, u *model.User) error
	// Org membership + operator management
	CreateAdmin(ctx context.Context, u *model.User) error
	InviteMember(ctx context.Context, u *model.User, orgID, invitedBy string, side model.Role) error
	Delete(ctx context.Context, id string) error
	ApproveOrgSide(ctx context.Context, requestID, reviewerID string) error
	RejectOrgSide(ctx context.Context, requestID, reviewerID string) error
}

var funcMap = template.FuncMap{
	// asset builds a version-stamped /static URL (see staticVersion).
	"asset": func(name string) string {
		return "/static/" + name + "?v=" + staticVersion
	},
	// stripQuery renders a URL without its query/fragment — display trim
	// for UTM-laden landing URLs; pair with queryCount so the reader can
	// tell params were dropped (the full URL stays in href/title).
	"stripQuery": func(raw string) string {
		u, err := url.Parse(raw)
		if err != nil {
			return raw
		}
		u.RawQuery = ""
		u.Fragment = ""
		return u.String()
	},
	"queryCount": func(raw string) int {
		u, err := url.Parse(raw)
		if err != nil {
			return 0
		}
		return len(u.Query())
	},
	"slice": func(s string, start, end int) string {
		if end > len(s) {
			end = len(s)
		}
		if start > len(s) {
			start = len(s)
		}
		return s[start:end]
	},
	"add": func(nums ...int) int {
		s := 0
		for _, n := range nums {
			s += n
		}
		return s
	},
	// dict builds a map from alternating key/value args so a sub-template
	// can receive multiple named values (e.g. the unified inbox rows).
	"dict": func(values ...any) (map[string]any, error) {
		if len(values)%2 != 0 {
			return nil, fmt.Errorf("dict: odd number of args")
		}
		m := make(map[string]any, len(values)/2)
		for i := 0; i < len(values); i += 2 {
			key, ok := values[i].(string)
			if !ok {
				return nil, fmt.Errorf("dict: key %d is not a string", i)
			}
			m[key] = values[i+1]
		}
		return m, nil
	},
}

func New(d Deps) *Handler {
	return &Handler{
		coreAPIURL:      d.CoreAPIURL,
		bannerScriptURL: d.BannerScriptURL,
		jwtSvc:          d.JWTSvc,
		userSvc:         d.UserSvc,
		passkeySvc:      d.PasskeySvc,
		setupSvc:        d.SetupSvc,
		settingsSvc:     d.SettingsSvc,
		billingSvc:      d.BillingSvc,
		siteReqSvc:      d.SiteReqSvc,
		orgRepo:         d.OrgRepo,
		auditRepo:       d.AuditRepo,
		settler:         d.Settler,
		orgCore:         d.OrgCore,
		devAuth:         d.DevAuth,
		secureCookies:   d.SecureCookies,
	}
}

// funcMapFor returns the shared funcMap plus the language-bound helpers:
// "t" translates (i18n.T with lang curried in) and "lang" reports the
// language for <html lang="{{lang}}">. Binding at parse time is what lets
// templates be cached per (page, lang) while {{t "..."}} works under any
// dot — inside dict-fed partials and the standalone editor's non-pageData
// data alike.
func funcMapFor(lang string) template.FuncMap {
	m := make(template.FuncMap, len(funcMap)+2)
	for k, v := range funcMap {
		m[k] = v
	}
	m["t"] = func(key string, args ...any) string { return i18n.T(lang, key, args...) }
	m["lang"] = func() string { return lang }
	return m
}

// parsePage parses the layout + a specific page template for one language.
func parsePage(lang, page string) *template.Template {
	return template.Must(
		template.New("").Funcs(funcMapFor(lang)).ParseFS(
			templateFSVar,
			"templates/layout.html",
			"templates/"+page,
		),
	)
}

// Parsed page templates, keyed lang+":"+name (each has its own "content"
// block). Lazy-filled under pagesMu — the map was previously written from
// request goroutines with no lock, a latent data race that adding the
// language dimension would have widened.
var (
	pagesMu sync.Mutex
	pages   = map[string]*template.Template{}
)

func getPage(lang, name string) *template.Template {
	key := lang + ":" + name
	pagesMu.Lock()
	defer pagesMu.Unlock()
	if t, ok := pages[key]; ok {
		return t
	}
	t := parsePage(lang, name)
	pages[key] = t
	return t
}

// getPageStandalone returns a template parsed WITHOUT layout.html, for pages
// that render their own full HTML document (e.g. the isolated Fabric editor).
func getPageStandalone(lang, name string) *template.Template {
	key := lang + ":standalone:" + name
	pagesMu.Lock()
	defer pagesMu.Unlock()
	if t, ok := pages[key]; ok {
		return t
	}
	t := template.Must(
		template.New("").Funcs(funcMapFor(lang)).ParseFS(templateFSVar, "templates/"+name),
	)
	pages[key] = t
	return t
}

// jsonErrorT is writeJSONError through the catalog. JSON error toasts
// mostly surface on the pre-login pages (sign-in, request-account,
// recovery, setup), where there is no user preference yet — so the
// language follows the browser (Accept-Language), deliberately.
func (h *Handler) jsonErrorT(w http.ResponseWriter, r *http.Request, status int, key string, args ...any) {
	writeJSONError(w, status, i18n.T(h.lang(r, nil), key, args...))
}

// lang resolves the request language: the signed-in user's preference
// (platform_users.locale, "" = auto) falling back to Accept-Language.
// Unauthenticated pages pass u == nil and get pure browser negotiation.
func (h *Handler) lang(r *http.Request, u *model.User) string {
	pref := ""
	if u != nil {
		pref = u.Locale
	}
	return i18n.Resolve(pref, r.Header.Get("Accept-Language"))
}

type pageData struct {
	Title string
	Nav   string
	User  *model.User
	Error string
	// Billing (docs/design/BILLING.md Phase 4)
	AdminBilling  *adminBillingData
	AdminTopups   *adminTopupsData
	AdminPayouts  *adminPayoutsData
	AdminJournal  *adminJournalData
	AdminAccounts *adminAccountsData
	AdminAccount  *adminAccountDetailData
	Wallet        *walletPageData
	Earnings      *earningsPageData
	// WalletNotice ("suspended" | "low" | "") drives the wallet banner on
	// the advertiser Account and Stats pages.
	WalletNotice string
	// Wallet summary embedded on the advertiser Account page, so budget
	// (pacing) and wallet (funds) are never seen in isolation.
	WalletBalance  string
	WalletUnfunded bool
	// OrgMaxMembers is the operator-set member cap per organization.
	OrgMaxMembers int
	// PayoutFloor is the platform-wide minimum payout (dollars string) on
	// the admin settings page.
	PayoutFloor string
	// DefaultOrgTimezone is the timezone seeded onto NEW orgs at creation
	// ("" = UTC), on the admin settings page.
	DefaultOrgTimezone string
	// Login
	Mode      string
	Role      string
	RoleLabel string
	// DevAuth renders the legacy password forms (DEV_AUTH=true only).
	DevAuth bool
	// Admin pages
	AdminRequests []adminRequestRow
	// Admin site-request queue (/admin/sites)
	AdminSiteRequests []adminSiteRequestRow
	// Sites the fraud detector auto-suspended, pending operator review
	// (also /admin/sites)
	AdminSuspendedSites []adminSuspendedSiteRow
	// Admin fraud-review queue (/admin/fraud)
	AdminFraudFlags []adminFraudFlagRow
	// Live per-site suspect-marked traffic today (also /admin/fraud)
	AdminSuspectSites []adminSuspectSiteRow
	AdminUsers      []adminUserRow
	AdminOrgs       []orgAdminRow
	AdminOrgsNav    *listNav
	AdminOrgsQ      string
	// Pending org side requests on /admin/requests (an existing org asking
	// for its other side — advertiser or publisher).
	AdminOrgSideRequests []model.OrgSideRequest
	// Org Team page (/org/members)
	OrgPage *orgPageData
	// GuardExit shows the "Exit view" button on the guard-error page (the
	// blocked action was a view-as mutation, so leaving the view is the fix).
	GuardExit bool
	// In-app Help (/advertiser/help, /publisher/help): the rendered guide
	// and its section anchors.
	HelpHTML      template.HTML
	HelpTOC       []helpTOCEntry
	MarginPct     string
	MarginHistory []marginRow
	RecoveryURL   string
	RecoveryEmail string
	// Self-service passkey management (/account/passkeys)
	Passkeys []passkeyRow
	// Recovery page
	RecoveryToken string
	// Publisher approval
	Tab        string
	SiteID     string
	Sites      []site
	Pending    []pendingCreative
	Serving    []servingCreative
	Flagged    []flaggedCreative
	SiteGroups []siteGroup
	// Publisher sites
	SitesData []siteData
	// Pending/denied site requests (platform rows, no core entity yet)
	SiteRequests []siteRequestRow
	// Per-site floor-RL observation log (decision history)
	FloorObservations *floorObservationsData
	// Publisher stats
	Stats *publisherStats
	// Advertiser
	Campaigns []campaignData
	AdvBudget *advertiserBudget
	// Aggregates for the campaigns-page summary tiles. Empty string =
	// no data yet (tile renders an em-dash).
	AdvAvgCTR     string // impression-weighted, e.g. "1.2%"
	AdvAvgWinRate string // bid-weighted, e.g. "45.2%"
	// Stats page: today's delivery per UTC hour (always 24 entries when
	// the projection responded) + the day's impression total that gates
	// rendering the chart.
	AdvHourly      []hourlyBar
	AdvHourlyTotal int64
	// Advertiser report page (report.go)
	Report *reportPageData
	// Publisher report (/publisher/report): per-site category breakdown.
	PubReport *publisherReportPageData
	// Sort + pagination state for whichever list the page renders.
	ListNav *listNav
	// Query echoes a page's ?q= search box value back into the input.
	Query          string
	CampaignID     string
	BlockedDomains []string
	// Publisher-side: advertiser landing-page domains this publisher blocks.
	BlockedAdvertiserDomains []string
	ServedSites              []servedSite
	// Sidebar nav gating: disable Campaigns when no daily budget,
	// disable Creatives when no campaigns exist.
	BudgetUnset      bool
	NoCampaigns      bool
	Creatives        []creativeData
	HasPendingRender bool
	// Creative × media stacked-bar chart (creatives page); nil = no data.
	MediaChart *creativeMediaChart
	// Going rates beside Max CPM (campaigns page); nil = no data yet.
	MarketRates *marketRatesData
	// IANA zone the campaign schedule inputs/displays are denominated
	// in — the advertiser's account timezone, "UTC" when unset.
	ScheduleTz string
	LandingURL string
	// CDN URL for the <expandable-magazine-banner> web component.
	// Used by templates that need to load the banner script to render
	// a live creative (e.g., the publisher approval page).
	BannerScriptURL string
	// Trusted Advertisers page (auto-approve management)
	TrustedToggles []trustedSiteToggle
	TrustedAnchors []trustedAnchorRow
	// Guard-error variant: suspension notice — hide "Go back", offer Logout.
	LogoutOnly bool
	// Preferences page
	Saved        bool // "saved ✓" banner after a successful POST-redirect
	Timezones    []string
	LandingSide  string
	LandingSides []string
}

type site struct {
	ID       string
	Domain   string
	FloorCpm float64
}

type siteGroup struct {
	Site    site
	Pending []pendingCreative
	Serving []servingCreative
	Flagged []flaggedCreative
	// Auto-approve: per-site opt-in toggle plus the trust anchors earned by
	// manual approvals (campaigns / landing registrable-domains). Anchors
	// persist while the toggle is off — dormant, not deleted.
	AutoApprove      bool
	TrustedCampaigns []trustAnchorRow
	TrustedDomains   []string
}

// One trusted campaign in the auto-approve panel. Name resolves from the
// campaigns visible in this page load; falls back to the raw ID.
type trustAnchorRow struct {
	ID   string
	Name string
}

// Per-site toggle on the Trusted Advertisers page.
type trustedSiteToggle struct {
	SiteID  string
	Domain  string
	Enabled bool
}

// One row of the Trusted Advertisers table.
type trustedAnchorRow struct {
	SiteID      string
	SiteDomain  string
	Type        string // display: "Campaign" | "Landing domain"
	AnchorType  string // raw value for the remove form
	AnchorValue string
	Display     string // resolved campaign name, or the domain itself
	Advertiser  string
	Since       string
	// Non-empty when this campaign anchor's landing domain is itself a
	// trusted domain on the same site: the domain row already covers it,
	// so the table nests it under that row.
	CoveredBy string
	// True when the site's auto-approve toggle is off: the trust is kept
	// but not acting ("paused" — deliberately not "removed").
	Paused bool
}

type pendingPlacement struct {
	URL          string
	SlotID       string
	CPM          string
	Category     string
	CategoryName string
	// True when this placement was filled via the filler auction
	// (page had no contextual match; campaign opted into unmatched
	// traffic). UI renders a distinct badge instead of a category.
	Filler bool
}

type pendingCreative struct {
	CreativeID        string
	CPM               string
	Category          string
	CategoryName      string
	AssetURL          string
	Width             int
	Height            int
	AdProductCategory string
	AdProductName     string
	CampaignID        string
	AdvertiserID      string
	// Display names resolved from core: publishers approving creatives
	// should see the brand and campaign name, not ULIDs (and not emails,
	// which stay operator-only). Fall back to the raw IDs.
	CampaignName   string
	AdvertiserName string
	LandingDomain  string
	// Full landing-page URL the CTA points to. The approval preview wires
	// this onto the <magazine-preview> as `landing-url` so the publisher
	// can click the CTA and open the actual LP in a new tab. Empty → inert.
	LandingURL string
	SiteID     string
	Placements []pendingPlacement
	// Raw expandable-banner pagesJson. When present the approval UI
	// renders the live <expandable-magazine-banner> — publisher can
	// click-expand into the magazine view the reader would see.
	// When empty the UI falls back to the static AssetURL thumbnail.
	PagesJSON string
	// Banner-level config blob (paper weight/back colour, reading dir).
	// Passed to the preview's <magazine-preview> as the `config` attr so
	// the creative's chosen expand effect (e.g. CRT) plays. Empty → the
	// banner uses its default config (fade).
	BannerConfigJSON string
	// Distinct IAB category names across all placements. The "Why
	// matched" explainer enumerates these so publishers see the full
	// set of classifications when a creative landed on pages of
	// different topics.
	DistinctCategoryNames []string
	// True when at least one placement is filler — the creative won
	// on a page with no contextual match and is serving only because
	// the advertiser opted in to unmatched traffic. Drives the badge
	// + alternate "Why matched" copy in the approval UI.
	Filler bool
	// Advertiser-given creative name. Rows are labeled by landing domain,
	// so without it two creatives of one advertiser look identical.
	CreativeName string
	// ISO-8601 creation time of the creative (from core). The Creative
	// Inbox orders newest-first and auto-selects the most recent; the
	// template emits this as data-ts for the client-side pick.
	CreatedAt string
	// Humanized time since the creative FIRST entered this publisher's
	// approval queue (e.g. "3h", "2d 5h"). Unlike CreatedAt this survives
	// the pending row's TTL purge/re-queue cycle on the core, so it is the
	// honest queue age. Empty when the core has no tracking row.
	WaitingFor string
	// Re-queue cycles since first seen — how many re-auctions refreshed
	// the pending entry while it sat unreviewed.
	RequeueCount int
}

type servingCreative struct {
	CreativeID string
	CampaignID string
	Category   string
	CPM        string
	AssetURL   string
	Width      int
	Height     int
	// BelowFloor is true when the creative's max bid is below the site's
	// current floor CPM. Such creatives stay in ServeIndex (so dog-ear pins
	// keep honoring them) but do not win normal auctions — they're served
	// only via pins. The UI badges these "Pin-only" instead of "Serving".
	BelowFloor bool
	FloorCPM   string
	// Benched is non-empty when this approved creative sits in the
	// ServeIndex but cannot actually deliver right now: the advertiser is
	// wallet-suspended, or advertiser/campaign daily budget is exhausted.
	// Budget exhaustion deliberately does NOT evict (it self-reverses at
	// day rollover), so without this the tab said "Serving" for benched
	// creatives — overstating.
	Benched string
	// Per-(url, slot) impression counts over the lookback window. Backed
	// by tracking_events on the core API — ServeIndex is slot-keyed with
	// no URL so this is the only source of "where it's actually being
	// delivered" attribution.
	Placements []servingPlacement
	// Banner pagesJson + config so the unified inbox renders the live
	// <magazine-preview> for serving creatives (same as pending).
	PagesJSON        string
	BannerConfigJSON string
	AdvertiserID     string
	AdvertiserName   string
	CampaignName     string
	LandingDomain    string
	LandingURL       string
	// True when this approval came from the auto-approve trust path
	// rather than an explicit publisher click ("Auto-approved" badge).
	AutoApproved bool
	// Advertiser-given creative name (see pendingCreative.CreativeName).
	CreativeName string
}

type servingPlacement struct {
	URL          string
	SlotID       string
	Impressions  int64
	Category     string
	CategoryName string
}

type flaggedCreative struct {
	CreativeID string
	CampaignID string
	CPM        string
	Reason     string
	AssetURL   string
	Width      int
	Height     int
	// Banner pagesJson + config so the unified inbox renders the live
	// <magazine-preview> for flagged creatives.
	PagesJSON        string
	BannerConfigJSON string
	AdvertiserID     string
	AdvertiserName   string
	CampaignName     string
	LandingDomain    string
	LandingURL       string
}

func (h *Handler) render(w http.ResponseWriter, r *http.Request, name string, data pageData) {
	h.renderStatus(w, r, http.StatusOK, name, data)
}

func (h *Handler) renderStatus(w http.ResponseWriter, r *http.Request, status int, name string, data pageData) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
	if status != http.StatusOK {
		w.WriteHeader(status)
	}
	lang := h.lang(r, data.User)
	// Page titles are set as plain English at ~40 call sites; translate
	// centrally so handlers never think about language. English keys fall
	// through untouched; already-translated strings miss the catalog and
	// pass through unchanged, so this is safe to layer.
	data.Title = i18n.T(lang, data.Title)
	t := getPage(lang, name)
	if err := t.ExecuteTemplate(w, "layout", data); err != nil {
		slog.Error("template render failed", "error", err, "template", name)
		http.Error(w, "render error: "+err.Error(), http.StatusInternalServerError)
	}
}

// ImageProxy proxies external images to bypass CORS when drawing onto the editor canvas.
func (h *Handler) ImageProxy(w http.ResponseWriter, r *http.Request) {
	imgURL := r.URL.Query().Get("url")
	if imgURL == "" {
		http.Error(w, "url required", http.StatusBadRequest)
		return
	}
	req, err := http.NewRequest("GET", imgURL, nil)
	if err != nil {
		http.Error(w, "invalid url", http.StatusBadRequest)
		return
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (compatible; Promovolve/1.0)")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		http.Error(w, "fetch failed", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	if ct := resp.Header.Get("Content-Type"); ct != "" {
		w.Header().Set("Content-Type", ct)
	}
	w.Header().Set("Cache-Control", "public, max-age=3600")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	io.Copy(w, resp.Body)
}

// coreGet fetches JSON from the core API
func (h *Handler) coreGet(path string, claims *model.Claims) ([]byte, error) {
	url := h.coreAPIURL + rewriteMePath(path, claims)
	// Use the timeout-bounded client (not http.DefaultClient) so a slow or
	// recovering core never hangs a publisher dashboard read indefinitely.
	resp, err := coreClient.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

// coreGetRaw fetches from the core API and returns the live response so the
// caller can stream binary bodies (e.g. an image) and propagate status +
// content-type. Caller must Close the body.
func (h *Handler) coreGetRaw(path string, claims *model.Claims) (*http.Response, error) {
	url := h.coreAPIURL + rewriteMePath(path, claims)
	return coreClient.Get(url)
}

// corePost sends a POST to the core API
// corePostChecked is corePost that FAILS on non-2xx. corePost's
// body-with-nil-error contract on 4xx/5xx is load-bearing for callers
// that parse core error bodies — but write paths that redirect on
// "success" need a real error or a mid-rollout 503 silently discards
// the user's work (the occasionally-vanishing publish).
func (h *Handler) corePostChecked(path string, claims *model.Claims, body string) ([]byte, error) {
	url := h.coreAPIURL + rewriteMePath(path, claims)
	resp, err := coreClient.Post(url, "application/json", strings.NewReader(body))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return respBody, fmt.Errorf("core returned %d: %s", resp.StatusCode, truncStr(string(respBody), 200))
	}
	return respBody, nil
}

func (h *Handler) corePost(path string, claims *model.Claims, body string) ([]byte, error) {
	url := h.coreAPIURL + rewriteMePath(path, claims)
	resp, err := coreClient.Post(url, "application/json", strings.NewReader(body))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

// coreDelete sends a DELETE to the core API
func (h *Handler) coreDelete(path string, claims *model.Claims, body string) ([]byte, error) {
	url := h.coreAPIURL + rewriteMePath(path, claims)
	req, err := http.NewRequest("DELETE", url, strings.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

// corePatch sends a PATCH to the core API
func (h *Handler) corePatch(path string, claims *model.Claims, body string) ([]byte, error) {
	url := h.coreAPIURL + rewriteMePath(path, claims)
	req, err := http.NewRequest("PATCH", url, strings.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

// corePut sends a PUT to the core API
func (h *Handler) corePut(path string, claims *model.Claims, body string) ([]byte, error) {
	url := h.coreAPIURL + rewriteMePath(path, claims)
	req, err := http.NewRequest("PUT", url, strings.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

// requireRole checks auth and role, redirects if wrong. Returns user, claims, and ok.
func (h *Handler) requireRole(w http.ResponseWriter, r *http.Request, role model.Role) (*model.User, *model.Claims, bool) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return nil, nil, false
	}
	if user.Role != role {
		// Redirect to the user's own dashboard
		http.Redirect(w, r, homeFor(user.Role), http.StatusSeeOther)
		return nil, nil, false
	}
	return user, claims, true
}

// RoleGuard wraps a handler with role checking
func (h *Handler) RoleGuard(role model.Role, handler http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user, _ := h.sessionUser(r)
		isSSE := r.Header.Get("Accept") == "text/event-stream" ||
			strings.Contains(r.URL.Path, "/sse/")
		if user == nil {
			if isSSE {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
			} else {
				http.Redirect(w, r, "/login", http.StatusSeeOther)
			}
			return
		}
		if user.Role != role {
			if isSSE {
				http.Error(w, "forbidden", http.StatusForbidden)
			} else {
				http.Redirect(w, r, homeFor(user.Role), http.StatusSeeOther)
			}
			return
		}
		handler(w, r)
	}
}

func rewriteMePath(path string, claims *model.Claims) string {
	if claims != nil {
		path = strings.Replace(path, "/publishers/me", "/publishers/"+claims.PublisherID, 1)
		path = strings.Replace(path, "/advertisers/me", "/advertisers/"+claims.AdvertiserID, 1)
	}
	return path
}

// sessionUser resolves the session cookie into the account to render and its
// claims. This is the actor-vs-account split in one place:
//
//   - Org members: the ORG owns the entities. The user's in-memory Role
//     becomes the session's active side and its entity ids become the org's,
//     so every downstream RoleGuard / rewriteMePath / template works
//     unchanged. Org and role are re-read from the DB each render — removal
//     or demotion applies immediately, not when the JWT expires.
//   - View-as: uid is the VIEWED user (pages render as they see them); the
//     actor must still be an active platform admin or the session dies.
func (h *Handler) sessionUser(r *http.Request) (*model.User, *model.Claims) {
	cookie, err := r.Cookie("token")
	if err != nil {
		return nil, nil
	}
	claims, err := h.jwtSvc.Validate(cookie.Value)
	if err != nil {
		return nil, nil
	}
	// Re-check the account against the DB so a rejected/deleted user's
	// still-valid JWT (up to 24 h) stops working immediately. One indexed PK
	// lookup per page render.
	u, err := h.userSvc.GetByID(r.Context(), claims.UserID)
	if err != nil || u.Status != model.StatusActive {
		return nil, nil
	}
	if claims.ActorID != "" {
		actor, err := h.userSvc.GetByID(r.Context(), claims.ActorID)
		if err != nil || actor.Status != model.StatusActive || actor.Role != model.RoleAdmin {
			return nil, nil
		}
		u.ViewAsBy = actor.Email
	}
	if claims.OrgID != "" {
		o, m, err := h.orgRepo.ForUser(r.Context(), u.ID)
		if err != nil || o.ID != claims.OrgID {
			return nil, nil
		}
		// No special-casing for view-as: the page must render EXACTLY as the
		// viewed user sees it (view-as targets are org admins, so the money
		// pages are part of that view anyway — forcing the role here is how
		// a member's view once grew an Earnings menu it shouldn't have).
		u.Org = o
		u.OrgRole = m.OrgRole
		side := o.DefaultSide(claims.Role)
		u.ActiveSide = side
		u.Role = side
		u.AdvertiserID = o.AdvertiserID
		u.PublisherID = o.PublisherID
		// Refresh the claims from the live org so a side approved after
		// login is usable without re-authenticating.
		claims.Role = side
		claims.OrgRole = u.OrgRole
		claims.AdvertiserID = ""
		claims.PublisherID = ""
		if o.AdvertiserID != nil {
			claims.AdvertiserID = *o.AdvertiserID
		}
		if o.PublisherID != nil {
			claims.PublisherID = *o.PublisherID
		}
	}
	return u, claims
}

// --- Page Handlers ---

func (h *Handler) LoginPage(w http.ResponseWriter, r *http.Request) {
	if r.Method == "POST" {
		h.handleLoginPost(w, r)
		return
	}
	mode := r.URL.Query().Get("mode")
	role := r.URL.Query().Get("role")
	// The password login/register modes only exist under DEV_AUTH.
	if mode == "" || !h.devAuth {
		mode = "choose"
	}
	roleLabel := "Publisher"
	if role == "advertiser" {
		roleLabel = "Advertiser"
	}
	h.render(w, r, "login.html", pageData{
		Title:     "Login",
		Mode:      mode,
		Role:      role,
		RoleLabel: roleLabel,
		DevAuth:   h.devAuth,
	})
}

func (h *Handler) handleLoginPost(w http.ResponseWriter, r *http.Request) {
	// Password login/register exist only as the DEV_AUTH escape hatch;
	// production sign-in goes through the WebAuthn endpoints.
	if !h.devAuth {
		http.NotFound(w, r)
		return
	}
	r.ParseForm()
	mode := r.FormValue("mode")
	role := r.FormValue("role")
	email := r.FormValue("email")
	password := r.FormValue("password")
	displayName := r.FormValue("displayName")

	var user *model.User
	var err error

	if mode == "register" {
		user, err = h.userSvc.Register(r.Context(), email, password, displayName, model.Role(role))
	} else {
		user, err = h.userSvc.Authenticate(r.Context(), email, password)
	}

	if err != nil {
		roleLabel := "Publisher"
		if role == "advertiser" {
			roleLabel = "Advertiser"
		}
		// DEV_AUTH-only path. Map the two sentinel errors through the
		// catalog (string match: the local user variable shadows the
		// user package here); anything else surfaces raw, dev-facing.
		msg := err.Error()
		switch msg {
		case "email already registered", "invalid credentials":
			msg = i18n.T(h.lang(r, nil), msg)
		}
		h.render(w, r, "login.html", pageData{
			Title:     "Login",
			Mode:      mode,
			Role:      role,
			RoleLabel: roleLabel,
			DevAuth:   h.devAuth,
			Error:     msg,
		})
		return
	}

	token, redirect, err := h.issueSessionFor(r.Context(), user)
	if err != nil {
		http.Error(w, "failed to issue token", http.StatusInternalServerError)
		return
	}

	h.setSessionCookie(w, token)
	http.Redirect(w, r, redirect, http.StatusSeeOther)
}

func (h *Handler) Logout(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{Name: "token", Value: "", Path: "/", MaxAge: -1})
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

func (h *Handler) loadPending(siteID string, claims *model.Claims) []pendingCreative {
	pendBody, _ := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/approval/pending?limit=50", siteID), claims)
	var pendResp struct {
		Data []struct {
			CreativeID            string  `json:"creativeId"`
			CPM                   string  `json:"cpm"`
			Category              string  `json:"category"`
			CategoryName          *string `json:"categoryName"`
			AssetURL              *string `json:"assetUrl"`
			Width                 *int    `json:"width"`
			Height                *int    `json:"height"`
			AdProductCategory     *string `json:"adProductCategory"`
			AdProductCategoryName *string `json:"adProductCategoryName"`
			CampaignID            *string `json:"campaignId"`
			AdvertiserID          *string `json:"advertiserId"`
			LandingDomain         *string `json:"landingDomain"`
			LandingURL            *string `json:"landingUrl"`
			PagesJSON             *string `json:"pagesJson"`
			Filler                bool    `json:"filler"`
			CreatedAt             string  `json:"createdAt"`
			FirstSeenAt           *int64  `json:"firstSeenAt"`
			RequeueCount          *int    `json:"requeueCount"`
			BannerConfigJSON      *string `json:"bannerConfigJson"`
			CreativeName          *string `json:"creativeName"`
			Placements            []struct {
				URL          string  `json:"url"`
				SlotID       string  `json:"slotId"`
				CPM          string  `json:"cpm"`
				Category     *string `json:"category"`
				CategoryName *string `json:"categoryName"`
				Filler       bool    `json:"filler"`
			} `json:"placements"`
		} `json:"data"`
	}
	json.Unmarshal(pendBody, &pendResp)
	var pending []pendingCreative
	for _, p := range pendResp.Data {
		pc := pendingCreative{
			CreativeID: p.CreativeID,
			CPM:        money(p.CPM),
			Category:   p.Category,
			SiteID:     siteID,
			Filler:     p.Filler,
			CreatedAt:  p.CreatedAt,
		}
		if p.CreativeName != nil {
			pc.CreativeName = *p.CreativeName
		}
		if p.FirstSeenAt != nil {
			pc.WaitingFor = humanizeSince(time.UnixMilli(*p.FirstSeenAt))
		}
		if p.RequeueCount != nil {
			pc.RequeueCount = *p.RequeueCount
		}
		if p.AssetURL != nil {
			pc.AssetURL = *p.AssetURL
		}
		if p.Width != nil {
			pc.Width = *p.Width
		}
		if p.Height != nil {
			pc.Height = *p.Height
		}
		if p.AdProductCategory != nil {
			pc.AdProductCategory = *p.AdProductCategory
		}
		if p.CampaignID != nil {
			pc.CampaignID = *p.CampaignID
		}
		if p.AdvertiserID != nil {
			pc.AdvertiserID = *p.AdvertiserID
		}
		if p.LandingDomain != nil {
			pc.LandingDomain = *p.LandingDomain
		}
		if p.LandingURL != nil {
			pc.LandingURL = *p.LandingURL
		}
		if p.PagesJSON != nil {
			pc.PagesJSON = *p.PagesJSON
		}
		if p.BannerConfigJSON != nil {
			pc.BannerConfigJSON = *p.BannerConfigJSON
		}
		// Prefer the server-resolved IAB taxonomy name; fall back to
		// the raw ID when the taxonomy doesn't recognize it. Filler
		// representatives have no meaningful category — leave the
		// name empty so the template uses the badge path instead.
		if p.CategoryName != nil && *p.CategoryName != "" {
			pc.CategoryName = *p.CategoryName
		} else if !p.Filler {
			pc.CategoryName = p.Category
		}
		pc.AdProductName = ""
		if p.AdProductCategoryName != nil && *p.AdProductCategoryName != "" {
			pc.AdProductName = *p.AdProductCategoryName
		} else if p.AdProductCategory != nil {
			pc.AdProductName = *p.AdProductCategory
		}
		// Insertion-ordered set so the explainer lists categories in
		// the same order they appear in the placement list.
		seenCat := make(map[string]bool)
		for _, pl := range p.Placements {
			plc := pendingPlacement{
				URL:    pl.URL,
				SlotID: pl.SlotID,
				CPM:    money(pl.CPM),
				Filler: pl.Filler,
			}
			if pl.Category != nil {
				plc.Category = *pl.Category
			}
			// Prefer the server-resolved IAB name; fall back to the
			// raw ID so the UI always has something to show. Filler
			// placements carry no meaningful category, so leave the
			// name empty — the template renders the filler badge
			// instead.
			if !pl.Filler {
				if pl.CategoryName != nil && *pl.CategoryName != "" {
					plc.CategoryName = *pl.CategoryName
				} else if pl.Category != nil {
					plc.CategoryName = *pl.Category
				}
			}
			pc.Placements = append(pc.Placements, plc)
			if plc.CategoryName != "" && !seenCat[plc.CategoryName] {
				seenCat[plc.CategoryName] = true
				pc.DistinctCategoryNames = append(pc.DistinctCategoryNames, plc.CategoryName)
			}
		}
		pending = append(pending, pc)
	}
	return pending
}

func (h *Handler) loadServing(siteID string, claims *model.Claims, floorCpm float64) []servingCreative {
	// Hits the grouped endpoint (mirror of /approval/pending) so each
	// creative carries its delivered (url, slot, impressions) list.
	// Falls back gracefully when the endpoint is unavailable — older
	// core builds without listServingCreatives just return an error
	// body and `serving` ends up empty.
	servBody, _ := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/approval/serving?hours=24", siteID), claims)
	var servResp struct {
		Data []struct {
			CreativeID       string  `json:"creativeId"`
			CampaignID       *string `json:"campaignId"`
			AdvertiserID     *string `json:"advertiserId"`
			CPM              string  `json:"cpm"`
			AssetURL         *string `json:"assetUrl"`
			Width            *int    `json:"width"`
			Height           *int    `json:"height"`
			BelowFloor       bool    `json:"belowFloor"`
			FloorCPM         string  `json:"floorCpm"`
			PagesJSON        *string `json:"pagesJson"`
			BannerConfigJSON *string `json:"bannerConfigJson"`
			LandingDomain    *string `json:"landingDomain"`
			LandingURL       *string `json:"landingUrl"`
			AutoApproved     *bool   `json:"autoApproved"`
			CreativeName     *string `json:"creativeName"`
			Placements       []struct {
				URL          string  `json:"url"`
				SlotID       string  `json:"slotId"`
				Impressions  int64   `json:"impressions"`
				Category     *string `json:"category"`
				CategoryName *string `json:"categoryName"`
			} `json:"placements"`
		} `json:"data"`
	}
	json.Unmarshal(servBody, &servResp)
	var serving []servingCreative
	for _, s := range servResp.Data {
		// CPM arrives as a 4-decimal string from the API; display
		// rounds to 2 decimals like the prior view did.
		cpmF, _ := strconv.ParseFloat(s.CPM, 64)
		sc := servingCreative{
			CreativeID: s.CreativeID,
			CPM:        fmt.Sprintf("%.2f", cpmF),
			BelowFloor: s.BelowFloor,
			FloorCPM:   money(s.FloorCPM),
		}
		if s.CampaignID != nil {
			sc.CampaignID = *s.CampaignID
		}
		if s.AssetURL != nil {
			sc.AssetURL = *s.AssetURL
		}
		if s.Width != nil {
			sc.Width = *s.Width
		}
		if s.Height != nil {
			sc.Height = *s.Height
		}
		if s.PagesJSON != nil {
			sc.PagesJSON = *s.PagesJSON
		}
		if s.BannerConfigJSON != nil {
			sc.BannerConfigJSON = *s.BannerConfigJSON
		}
		if s.AdvertiserID != nil {
			sc.AdvertiserID = *s.AdvertiserID
		}
		if s.LandingDomain != nil {
			sc.LandingDomain = *s.LandingDomain
		}
		if s.LandingURL != nil {
			sc.LandingURL = *s.LandingURL
		}
		if s.AutoApproved != nil {
			sc.AutoApproved = *s.AutoApproved
		}
		if s.CreativeName != nil {
			sc.CreativeName = *s.CreativeName
		}
		for _, pl := range s.Placements {
			plc := servingPlacement{
				URL:         pl.URL,
				SlotID:      pl.SlotID,
				Impressions: pl.Impressions,
			}
			if pl.Category != nil {
				plc.Category = *pl.Category
			}
			if pl.CategoryName != nil {
				plc.CategoryName = *pl.CategoryName
			} else if pl.Category != nil {
				plc.CategoryName = *pl.Category
			}
			sc.Placements = append(sc.Placements, plc)
		}
		serving = append(serving, sc)
	}
	// Benched annotation — one status read per distinct advertiser, plus a
	// campaign-budget read when the advertiser looks fine. Serving lists
	// are small (a publisher's active creatives), so this stays cheap.
	advBenched := map[string]string{}
	campBenched := map[string]string{}
	for i := range serving {
		advID := serving[i].AdvertiserID
		if advID == "" {
			continue
		}
		benched, seen := advBenched[advID]
		if !seen {
			if body, err := h.coreGet("/v1/advertisers/"+advID, claims); err == nil {
				var a struct {
					Status string `json:"status"`
					Budget struct {
						WithinBudget bool `json:"withinBudget"`
					} `json:"budget"`
				}
				if json.Unmarshal(body, &a) == nil {
					switch strings.ToLower(a.Status) {
					case "suspended", "closed":
						benched = "Benched — advertiser suspended"
					default:
						if !a.Budget.WithinBudget {
							benched = "Benched — out of budget today, resumes at rollover"
						}
					}
				}
			}
			advBenched[advID] = benched
		}
		if benched == "" && serving[i].CampaignID != "" {
			cid := serving[i].CampaignID
			cb, seenC := campBenched[cid]
			if !seenC {
				if body, err := h.coreGet("/v1/advertisers/"+advID+"/campaigns/"+cid, claims); err == nil {
					var c struct {
						Remaining *string `json:"remaining"`
					}
					if json.Unmarshal(body, &c) == nil && c.Remaining != nil {
						if rem, err := strconv.ParseFloat(*c.Remaining, 64); err == nil && rem <= 0 {
							cb = "Benched — campaign budget spent for today"
						}
					}
				}
				campBenched[cid] = cb
			}
			benched = cb
		}
		serving[i].Benched = benched
	}
	return serving
}

func (h *Handler) loadFlagged(siteID string, claims *model.Claims) []flaggedCreative {
	flagBody, _ := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/approval/flagged?limit=50", siteID), claims)
	var flagResp struct {
		Data []struct {
			CreativeID       string  `json:"creativeId"`
			CampaignID       string  `json:"campaignId"`
			CPM              string  `json:"cpm"`
			Reason           string  `json:"reason"`
			AssetURL         *string `json:"assetUrl"`
			Width            *int    `json:"width"`
			Height           *int    `json:"height"`
			PagesJSON        *string `json:"pagesJson"`
			BannerConfigJSON *string `json:"bannerConfigJson"`
			AdvertiserID     *string `json:"advertiserId"`
			LandingDomain    *string `json:"landingDomain"`
			LandingURL       *string `json:"landingUrl"`
		} `json:"data"`
	}
	json.Unmarshal(flagBody, &flagResp)
	var flagged []flaggedCreative
	for _, f := range flagResp.Data {
		fc := flaggedCreative{
			CreativeID: f.CreativeID,
			CampaignID: f.CampaignID,
			CPM:        money(f.CPM),
			Reason:     f.Reason,
		}
		if f.AssetURL != nil {
			fc.AssetURL = *f.AssetURL
		}
		if f.Width != nil {
			fc.Width = *f.Width
		}
		if f.Height != nil {
			fc.Height = *f.Height
		}
		if f.PagesJSON != nil {
			fc.PagesJSON = *f.PagesJSON
		}
		if f.BannerConfigJSON != nil {
			fc.BannerConfigJSON = *f.BannerConfigJSON
		}
		if f.AdvertiserID != nil {
			fc.AdvertiserID = *f.AdvertiserID
		}
		if f.LandingDomain != nil {
			fc.LandingDomain = *f.LandingDomain
		}
		if f.LandingURL != nil {
			fc.LandingURL = *f.LandingURL
		}
		flagged = append(flagged, fc)
	}
	return flagged
}

// advertiserDirectory memoizes per-request core lookups of advertiser
// display names and campaign names for the approval inbox. One fetch pair
// per distinct advertiser regardless of creative count; failures degrade
// to the raw IDs.
type advertiserDirectory struct {
	h         *Handler
	names     map[string]string
	campaigns map[string]map[string]string
}

func (h *Handler) newAdvertiserDirectory() *advertiserDirectory {
	return &advertiserDirectory{
		h:         h,
		names:     map[string]string{},
		campaigns: map[string]map[string]string{},
	}
}

func (d *advertiserDirectory) resolve(advertiserID string) (string, map[string]string) {
	if advertiserID == "" {
		return "", nil
	}
	if name, ok := d.names[advertiserID]; ok {
		return name, d.campaigns[advertiserID]
	}
	name := advertiserID
	if body, err := d.h.coreGet("/v1/advertisers/"+advertiserID, nil); err == nil {
		var resp struct {
			Name string `json:"name"`
		}
		if json.Unmarshal(body, &resp) == nil && resp.Name != "" {
			name = resp.Name
		}
	}
	d.names[advertiserID] = name
	d.campaigns[advertiserID] = d.h.campaignNames("/v1/advertisers/"+advertiserID+"/campaigns?limit=100", nil)
	return name, d.campaigns[advertiserID]
}

func (h *Handler) PublisherApproval(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	tab := r.URL.Query().Get("tab")
	if tab == "" {
		tab = "pending"
	}

	// Load sites
	sitesBody, _ := h.coreGet("/v1/publishers/me/sites?limit=50", claims)
	var sitesResp struct {
		Data []struct {
			ID       string `json:"id"`
			Domain   string `json:"domain"`
			FloorCpm string `json:"floorCpm"`
		} `json:"data"`
	}
	json.Unmarshal(sitesBody, &sitesResp)

	var sites []site
	for _, s := range sitesResp.Data {
		floor, _ := strconv.ParseFloat(s.FloorCpm, 64)
		sites = append(sites, site{ID: s.ID, Domain: s.Domain, FloorCpm: floor})
	}

	// Load data for all sites, grouped
	var groups []siteGroup
	var allPending []pendingCreative
	var allServing []servingCreative
	var allFlagged []flaggedCreative

	dir := h.newAdvertiserDirectory()
	for _, s := range sites {
		pending := h.loadPending(s.ID, claims)
		serving := h.loadServing(s.ID, claims, s.FloorCpm)
		flagged := h.loadFlagged(s.ID, claims)

		// ONE row per creative per site. The lists come from different
		// stores with different propagation clocks (pending from Postgres,
		// serving from the replicated ServeIndex), and transitions like
		// revoke have no atomic end state — a render sampled mid-cascade
		// legitimately finds the same creative in two lists. Pending wins
		// (revoke's serving-side cleanup can lag a full auction wave),
		// then flagged, then serving.
		pendingSet := map[string]bool{}
		for i := range pending {
			pendingSet[pending[i].CreativeID] = true
		}
		flaggedSet := map[string]bool{}
		filteredFlagged := flagged[:0:0]
		for i := range flagged {
			if pendingSet[flagged[i].CreativeID] {
				continue
			}
			flaggedSet[flagged[i].CreativeID] = true
			filteredFlagged = append(filteredFlagged, flagged[i])
		}
		flagged = filteredFlagged
		filteredServing := serving[:0:0]
		for i := range serving {
			if pendingSet[serving[i].CreativeID] || flaggedSet[serving[i].CreativeID] {
				continue
			}
			filteredServing = append(filteredServing, serving[i])
		}
		serving = filteredServing

		for i := range pending {
			name, camps := dir.resolve(pending[i].AdvertiserID)
			pending[i].AdvertiserName = name
			pending[i].CampaignName = labelOr(camps, pending[i].CampaignID)
		}
		for i := range serving {
			name, camps := dir.resolve(serving[i].AdvertiserID)
			serving[i].AdvertiserName = name
			serving[i].CampaignName = labelOr(camps, serving[i].CampaignID)
		}
		for i := range flagged {
			name, camps := dir.resolve(flagged[i].AdvertiserID)
			flagged[i].AdvertiserName = name
			flagged[i].CampaignName = labelOr(camps, flagged[i].CampaignID)
		}
		// Disambiguate visually-identical rows: auto-generated creative
		// names ("Creative - domain") plus a shared landing domain render
		// two different creatives as the same row. Where the (name, domain)
		// pair collides within the site, suffix a short creative-id.
		labelCount := map[string]int{}
		for i := range pending {
			labelCount[pending[i].CreativeName+"|"+pending[i].LandingDomain]++
		}
		for i := range serving {
			labelCount[serving[i].CreativeName+"|"+serving[i].LandingDomain]++
		}
		shortID := func(cid string) string {
			if len(cid) > 6 {
				return cid[len(cid)-6:]
			}
			return cid
		}
		for i := range pending {
			if labelCount[pending[i].CreativeName+"|"+pending[i].LandingDomain] > 1 {
				pending[i].CreativeName = strings.TrimSpace(pending[i].CreativeName + " #" + shortID(pending[i].CreativeID))
			}
		}
		for i := range serving {
			if labelCount[serving[i].CreativeName+"|"+serving[i].LandingDomain] > 1 {
				serving[i].CreativeName = strings.TrimSpace(serving[i].CreativeName + " #" + shortID(serving[i].CreativeID))
			}
		}

		autoApprove, trustedCampaigns, trustedDomains, _ := h.loadAutoApprove(s.ID, claims)
		// Trusted-campaign names resolve from the campaigns visible in this
		// page load (pending/serving carry names); raw ID otherwise.
		campNames := map[string]string{}
		for i := range pending {
			campNames[pending[i].CampaignID] = pending[i].CampaignName
		}
		for i := range serving {
			campNames[serving[i].CampaignID] = serving[i].CampaignName
		}
		var trustedCampaignRows []trustAnchorRow
		for _, cid := range trustedCampaigns {
			name := campNames[cid]
			if name == "" {
				name = cid
			}
			trustedCampaignRows = append(trustedCampaignRows, trustAnchorRow{ID: cid, Name: name})
		}
		// A site earns a section when it has inbox content OR auto-approve
		// state to manage (toggle on / anchors present) — otherwise a
		// publisher could never see or undo the feature on a quiet site.
		if len(pending) > 0 || len(serving) > 0 || len(flagged) > 0 || autoApprove || len(trustedCampaignRows) > 0 ||
			len(trustedDomains) > 0 {
			groups = append(groups, siteGroup{
				Site:             s,
				Pending:          pending,
				Serving:          serving,
				Flagged:          flagged,
				AutoApprove:      autoApprove,
				TrustedCampaigns: trustedCampaignRows,
				TrustedDomains:   trustedDomains,
			})
		}
		allPending = append(allPending, pending...)
		allServing = append(allServing, serving...)
		allFlagged = append(allFlagged, flagged...)
	}

	// SiteID no longer drives the SSE connect — the template subscribes to
	// the publisher-level /sse/publishers/events stream (a single-site
	// subscription missed every other site's events). Kept populated for
	// remaining single-site uses in the template.
	siteID := ""
	if len(sites) > 0 {
		siteID = sites[0].ID
	}

	data := pageData{
		Title:           "Approval",
		Nav:             "approval",
		User:            user,
		Tab:             tab,
		SiteID:          siteID,
		Sites:           sites,
		Pending:         allPending,
		Serving:         allServing,
		Flagged:         allFlagged,
		SiteGroups:      groups,
		BannerScriptURL: h.bannerScriptURL,
	}

	// htmx partial request — return tab content + trigger count update
	if r.Header.Get("HX-Request") == "true" {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("HX-Trigger", fmt.Sprintf(
			`{"updateCounts": {"pending": %d, "serving": %d, "flagged": %d}}`,
			len(allPending), len(allServing), len(allFlagged),
		))
		getPage(h.lang(r, user), "publisher/approval.html").ExecuteTemplate(w, "approval-tab", data)
		return
	}

	h.render(w, r, "publisher/approval.html", data)
}

// One trust-anchor row from the core, with the source creative's advertiser
// for display-name resolution. AdvertiserID is nil when the source creative
// no longer resolves (deleted since the approval).
type anchorDetail struct {
	AnchorType       string  `json:"anchorType"`
	AnchorValue      string  `json:"anchorValue"`
	SourceCreativeID string  `json:"sourceCreativeId"`
	AdvertiserID     *string `json:"advertiserId"`
	CreatedAt        string  `json:"createdAt"`
	LandingDomain    *string `json:"landingDomain"`
}

// loadAutoApprove fetches the site's auto-approve toggle + trust anchors.
// Errors degrade to "disabled, no anchors" — the panel just renders off.
func (h *Handler) loadAutoApprove(siteID string, claims *model.Claims) (bool, []string, []string, []anchorDetail) {
	body, err := h.coreGet(fmt.Sprintf("/v1/publishers/me/sites/%s/auto-approve", siteID), claims)
	if err != nil {
		return false, nil, nil, nil
	}
	var resp struct {
		Enabled          bool           `json:"enabled"`
		TrustedCampaigns []string       `json:"trustedCampaigns"`
		TrustedDomains   []string       `json:"trustedDomains"`
		AnchorDetails    []anchorDetail `json:"anchorDetails"`
	}
	json.Unmarshal(body, &resp)
	return resp.Enabled, resp.TrustedCampaigns, resp.TrustedDomains, resp.AnchorDetails
}

// PublisherTrusted renders the Trusted Advertisers page: per-site
// auto-approve toggles plus one table of every trust anchor across the
// publisher's sites — the scalable home for a list the inbox panel's
// chips would drown in.
func (h *Handler) PublisherTrusted(w http.ResponseWriter, r *http.Request) {
	user, claims := h.sessionUser(r)
	if user == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	sitesBody, _ := h.coreGet("/v1/publishers/me/sites?limit=50", claims)
	var sitesResp struct {
		Data []struct {
			ID     string `json:"id"`
			Domain string `json:"domain"`
		} `json:"data"`
	}
	json.Unmarshal(sitesBody, &sitesResp)

	dir := h.newAdvertiserDirectory()
	var toggles []trustedSiteToggle
	var rows []trustedAnchorRow
	for _, s := range sitesResp.Data {
		enabled, _, _, details := h.loadAutoApprove(s.ID, claims)
		siteLabel := s.Domain
		if siteLabel == "" {
			siteLabel = s.ID
		}
		toggles = append(toggles, trustedSiteToggle{SiteID: s.ID, Domain: siteLabel, Enabled: enabled})

		var domainRows, campaignRows []trustedAnchorRow
		trustedDomains := map[string]bool{}
		for _, d := range details {
			row := trustedAnchorRow{
				SiteID:      s.ID,
				SiteDomain:  siteLabel,
				AnchorType:  d.AnchorType,
				AnchorValue: d.AnchorValue,
				Display:     d.AnchorValue,
				Paused:      !enabled,
			}
			if d.AdvertiserID != nil {
				name, camps := dir.resolve(*d.AdvertiserID)
				row.Advertiser = name
				if d.AnchorType == "campaign" {
					row.Display = labelOr(camps, d.AnchorValue)
				}
			}
			if t, err := time.Parse(time.RFC3339Nano, d.CreatedAt); err == nil {
				row.Since = t.Format("2006-01-02")
			}
			switch d.AnchorType {
			case "campaign":
				row.Type = "Campaign"
				if d.LandingDomain != nil {
					row.CoveredBy = *d.LandingDomain // provisional; kept only if the domain row exists
				}
				campaignRows = append(campaignRows, row)
			case "domain":
				row.Type = "Landing domain"
				trustedDomains[d.AnchorValue] = true
				domainRows = append(domainRows, row)
			default:
				row.Type = d.AnchorType
				campaignRows = append(campaignRows, row)
			}
		}
		// Grouped order: each domain row followed by the campaign rows it
		// covers (nested in the UI), then campaigns standing alone — e.g.
		// their domain anchor was removed, or the landing host had no
		// registrable domain.
		for i := range campaignRows {
			if !trustedDomains[campaignRows[i].CoveredBy] {
				campaignRows[i].CoveredBy = ""
			}
		}
		for _, dr := range domainRows {
			rows = append(rows, dr)
			for _, cr := range campaignRows {
				if cr.CoveredBy == dr.AnchorValue {
					rows = append(rows, cr)
				}
			}
		}
		for _, cr := range campaignRows {
			if cr.CoveredBy == "" {
				rows = append(rows, cr)
			}
		}
	}

	// Search across everything a publisher would remember an anchor by:
	// domain, campaign name, advertiser, site, type. Filter before paging.
	if q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q"))); q != "" {
		filtered := rows[:0:0]
		for _, row := range rows {
			hay := strings.ToLower(row.SiteDomain + " " + row.Display + " " + row.AnchorValue + " " +
				row.Advertiser + " " + row.Type)
			if strings.Contains(hay, q) {
				filtered = append(filtered, row)
			}
		}
		rows = filtered
	}

	start, end, nav := buildListNav(r, len(rows), 25)
	h.render(w, r, "publisher/trusted.html", pageData{
		Title:          "Trusted Advertisers",
		Nav:            "trusted",
		User:           user,
		TrustedToggles: toggles,
		TrustedAnchors: rows[start:end],
		ListNav:        nav,
		Query:          r.URL.Query().Get("q"),
	})
}

// PublisherAutoApprove flips a site's auto-approve toggle (htmx checkbox —
// an unchecked box submits no "enabled" field, which reads as false).
func (h *Handler) PublisherAutoApprove(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	enabled := r.FormValue("enabled") != ""
	bodyJSON, _ := json.Marshal(map[string]bool{"enabled": enabled})
	resp, err := h.corePut(fmt.Sprintf("/v1/publishers/me/sites/%s/auto-approve", siteID), claims, string(bodyJSON))
	if err != nil {
		slog.Error("auto-approve toggle failed", "siteId", siteID, "error", err)
	} else {
		slog.Info("auto-approve toggle", "siteId", siteID, "enabled", enabled, "response", string(resp))
	}
	if r.Header.Get("HX-Request") == "true" {
		w.Header().Set("HX-Trigger", `{"inboxActed": true}`)
		w.WriteHeader(http.StatusNoContent)
		return
	}
	http.Redirect(w, r, autoApproveReturnPath(r), http.StatusSeeOther)
}

// The toggle/remove forms live on two pages; plain (no-JS) posts should
// land back where they came from. Referer is matched against a fixed set
// of internal paths — never echoed — so it can't become an open redirect.
func autoApproveReturnPath(r *http.Request) string {
	if strings.Contains(r.Header.Get("Referer"), "/publisher/trusted") {
		return "/publisher/trusted"
	}
	return "/publisher/approval"
}

// PublisherTrustAnchorRemove deletes one trust anchor ("stop trusting this
// campaign/domain") from the site's auto-approve panel.
func (h *Handler) PublisherTrustAnchorRemove(w http.ResponseWriter, r *http.Request) {
	_, claims := h.sessionUser(r)
	if claims == nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	r.ParseForm()
	siteID := r.FormValue("siteId")
	bodyJSON, _ := json.Marshal(map[string]string{
		"anchorType":  r.FormValue("anchorType"),
		"anchorValue": r.FormValue("anchorValue"),
	})
	resp, err := h.coreDelete(fmt.Sprintf("/v1/publishers/me/sites/%s/auto-approve/anchors", siteID), claims,
		string(bodyJSON))
	if err != nil {
		slog.Error("trust anchor remove failed", "siteId", siteID, "error", err)
	} else {
		slog.Info("trust anchor removed", "siteId", siteID, "response", string(resp))
	}
	if r.Header.Get("HX-Request") == "true" {
		w.Header().Set("HX-Trigger", `{"inboxActed": true}`)
		w.WriteHeader(http.StatusNoContent)
		return
	}
	http.Redirect(w, r, autoApproveReturnPath(r), http.StatusSeeOther)
}

func (h *Handler) ApprovalAction(action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		_, claims := h.sessionUser(r)
		if claims == nil {
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}
		r.ParseForm()
		siteID := r.FormValue("siteId")
		body := map[string]string{
			"url":        r.FormValue("url"),
			"slot":       r.FormValue("slot"),
			"creativeId": r.FormValue("creativeId"),
		}
		if action == "flag" {
			body["reason"] = r.FormValue("reason")
		}
		bodyJSON, _ := json.Marshal(body)
		resp, err := h.corePost(fmt.Sprintf("/v1/publishers/me/sites/%s/approval/%s", siteID, action), claims, string(bodyJSON))
		if err != nil {
			slog.Error("approval action failed", "action", action, "error", err)
		} else {
			slog.Info("approval action", "action", action, "response", string(resp))
		}
		// htmx action (no full-page navigation): fire a client event so the
		// inbox refetches in place. Keeping the page (and its SSE connection)
		// alive means the eventual revoked/pending-updated events aren't lost
		// in a navigation gap. No-JS fallback still redirects.
		if r.Header.Get("HX-Request") == "true" {
			w.Header().Set("HX-Trigger", `{"inboxActed": true}`)
			w.WriteHeader(http.StatusNoContent)
			return
		}
		http.Redirect(w, r, "/publisher/approval", http.StatusSeeOther)
	}
}
