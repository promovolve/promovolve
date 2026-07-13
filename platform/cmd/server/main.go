package main

import (
	"context"
	"flag"
	"fmt"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/hanishi/promovolve/platform"
	"github.com/hanishi/promovolve/platform/internal/audit"
	"github.com/hanishi/promovolve/platform/internal/auth"
	"github.com/hanishi/promovolve/platform/internal/billing"
	"github.com/hanishi/promovolve/platform/internal/config"
	"github.com/hanishi/promovolve/platform/internal/db"
	"github.com/hanishi/promovolve/platform/internal/handler"
	"github.com/hanishi/promovolve/platform/internal/middleware"
	"github.com/hanishi/promovolve/platform/internal/model"
	"github.com/hanishi/promovolve/platform/internal/org"
	"github.com/hanishi/promovolve/platform/internal/passkey"
	"github.com/hanishi/promovolve/platform/internal/settings"
	"github.com/hanishi/promovolve/platform/internal/setup"
	"github.com/hanishi/promovolve/platform/internal/siterequest"
	"github.com/hanishi/promovolve/platform/internal/user"
)

func main() {
	cfg := config.Load()

	// Break-glass subcommand: mint a passkey recovery link from the server
	// itself, for when the (only) admin loses their passkey and no session
	// exists to mint one through the UI. Run inside the pod:
	//   kubectl exec deploy/promovolve-platform -- /server mint-recovery --email <email>
	if len(os.Args) > 1 && os.Args[1] == "mint-recovery" {
		os.Exit(runMintRecovery(cfg, os.Args[2:]))
	}
	// Sibling escape hatch: mint a session token directly, for driving the
	// passkey-only dashboard from scripts/automation (passkey ceremonies
	// need a real browser authenticator). Run inside the pod:
	//   kubectl exec deploy/promovolve-platform -- /server mint-session --email <email>
	if len(os.Args) > 1 && os.Args[1] == "mint-session" {
		os.Exit(runMintSession(cfg, os.Args[2:]))
	}
	// Force-settle a single UTC day on demand (the scheduled tick only
	// settles up to yesterday). For operator re-settles and testing the
	// billing loop before a day naturally closes. Run inside the pod:
	//   kubectl exec deploy/promovolve-platform -- /server settle-day [--date YYYY-MM-DD]
	if len(os.Args) > 1 && os.Args[1] == "settle-day" {
		os.Exit(runSettleDay(cfg, os.Args[2:]))
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: cfg.LogLevel,
	}))
	slog.SetDefault(logger)

	pool, err := db.Connect(cfg.DatabaseURL)
	if err != nil {
		slog.Error("failed to connect to database", "error", err)
		os.Exit(1)
	}
	defer pool.Close()

	if err := db.Migrate(pool); err != nil {
		slog.Error("failed to run migrations", "error", err)
		os.Exit(1)
	}

	// Initialize embedded FS for templates and static files
	handler.SetFS(platform.Templates, platform.Static)
	handler.SetHelpFS(platform.Help)

	jwtSvc := auth.NewJWTService(cfg.JWTSecret, cfg.JWTExpiry)
	userRepo := user.NewRepository(pool)
	orgRepo := org.NewRepository(pool)
	auditRepo := audit.NewRepository(pool)
	userSvc := user.NewService(userRepo, orgRepo, cfg.CoreAPIURL)
	authHandler := auth.NewHandler(userSvc, jwtSvc)
	passkeyRepo := passkey.NewRepository(pool)
	passkeySvc, err := passkey.NewService(cfg.RPID, cfg.RPOrigins, passkeyRepo, userSvc)
	if err != nil {
		slog.Error("failed to configure webauthn", "error", err)
		os.Exit(1)
	}
	setupSvc := setup.NewService(pool, userRepo, passkeyRepo)
	settingsSvc := settings.NewService(pool)
	siteReqRepo := siterequest.NewRepository(pool)
	siteReqSvc := siterequest.NewService(siteReqRepo, cfg.CoreAPIURL)

	// Billing settlement job: books yesterday's metering into the ledger and
	// enforces the prepaid-wallet policy (docs/design/BILLING.md). Idempotent
	// per day, catches up after downtime, single-pod by design.
	billingSvc := billing.NewService(pool)
	settler := billing.NewSettler(
		billingSvc, pool,
		billing.NewHTTPCoreClient(cfg.CoreAPIURL, cfg.InternalAPIKey),
		settingsSvc.MarginBpsAt,
	)
	settleCtx, stopSettler := context.WithCancel(context.Background())
	defer stopSettler()
	go settler.Run(settleCtx)

	h := handler.New(handler.Deps{
		CoreAPIURL:      cfg.CoreAPIURL,
		BannerScriptURL: cfg.BannerScriptURL,
		JWTSvc:          jwtSvc,
		UserSvc:         userSvc,
		PasskeySvc:      passkeySvc,
		SetupSvc:        setupSvc,
		SettingsSvc:     settingsSvc,
		BillingSvc:      billingSvc,
		SiteReqSvc:      siteReqSvc,
		OrgRepo:         orgRepo,
		AuditRepo:       auditRepo,
		Settler:         settler,
		DevAuth:         cfg.DevAuth,
		SecureCookies:   cfg.RPID != "localhost",
	})

	mux := http.NewServeMux()

	// Static files. Templates reference them through the `asset` template
	// func as /static/x.js?v=<content-hash-of-the-embed>, so versioned
	// requests are served immutable-cacheable: a new build mints a new
	// hash (fresh URLs), unchanged builds keep the browser cache warm.
	// Unversioned requests (hand-typed, external) fall back to no-cache
	// revalidation rather than a year of staleness.
	//
	// DEV_STATIC_DIR overrides the compile-time embed with a live
	// directory (pairs with `npm run dev` in creative-designer, which
	// writes straight into platform/static). Files then change without a
	// rebuild, so the immutable promise would be a lie — that mode stays
	// fully uncached. Never set in production images.
	devStatic := os.Getenv("DEV_STATIC_DIR")
	var staticHTTPFS http.FileSystem
	if devStatic != "" {
		slog.Info("serving /static/ from live directory (DEV_STATIC_DIR)", "dir", devStatic)
		staticHTTPFS = http.Dir(devStatic)
	} else {
		staticSub, _ := fs.Sub(platform.Static, "static")
		staticHTTPFS = http.FS(staticSub)
	}
	staticFS := http.FileServer(staticHTTPFS)
	mux.Handle("GET /static/", http.StripPrefix("/static/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case devStatic != "":
			w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
			w.Header().Set("Pragma", "no-cache")
			w.Header().Set("Expires", "0")
		case r.URL.Query().Get("v") == handler.StaticVersion():
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		default:
			w.Header().Set("Cache-Control", "no-cache")
		}
		staticFS.ServeHTTP(w, r)
	})))

	// Auth API (JSON). Password register/login are development-only escape
	// hatches (DEV_AUTH=true) — production auth is passkey ceremonies below.
	if cfg.DevAuth {
		mux.HandleFunc("POST /api/v1/auth/register", authHandler.Register)
		mux.HandleFunc("POST /api/v1/auth/login", authHandler.Login)
	}
	mux.HandleFunc("POST /api/v1/auth/refresh", authHandler.Refresh)

	protected := middleware.Chain(
		middleware.Logger(logger),
		middleware.Auth(jwtSvc),
	)

	// Core API proxy (JSON — for SPA/API clients)
	proxy := middleware.NewCoreProxy(cfg.CoreAPIURL, jwtSvc)
	mux.Handle("/api/v1/core/", protected(proxy))

	// HTML pages (htmx — server-rendered)
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
	})
	mux.HandleFunc("/login", h.LoginPage)
	mux.HandleFunc("GET /logout", h.Logout)

	// WebAuthn ceremonies (JSON, driven by static/passkey.js)
	mux.HandleFunc("POST /webauthn/login/begin", h.PasskeyLoginBegin)
	mux.HandleFunc("POST /webauthn/login/finish", h.PasskeyLoginFinish)

	// One-time installation wizard (see setup.Service.Gate below)
	mux.HandleFunc("GET /setup", h.SetupPage)
	mux.HandleFunc("POST /setup/dev", h.SetupDev)
	mux.HandleFunc("POST /webauthn/setup/begin", h.SetupBegin)
	mux.HandleFunc("POST /webauthn/setup/finish", h.SetupFinish)

	// Account requests (admin approves before sign-in works)
	mux.HandleFunc("GET /request-account", h.RequestAccountPage)
	mux.HandleFunc("POST /webauthn/request-account/begin", h.RequestAccountBegin)
	mux.HandleFunc("POST /webauthn/request-account/finish", h.RequestAccountFinish)

	// Lost-passkey recovery (one-time links minted by an admin)
	mux.HandleFunc("GET /recover/{token}", h.RecoverPage)
	mux.HandleFunc("POST /webauthn/recover/begin", h.RecoverBegin)
	mux.HandleFunc("POST /webauthn/recover/finish", h.RecoverFinish)

	// Self-service passkey management (any signed-in role)
	mux.HandleFunc("GET /account/passkeys", h.AccountPasskeysPage)
	mux.HandleFunc("POST /account/passkeys/delete", h.DeletePasskey)
	mux.HandleFunc("POST /webauthn/passkeys/begin", h.AddPasskeyBegin)
	mux.HandleFunc("POST /webauthn/passkeys/finish", h.AddPasskeyFinish)

	// Org membership (Team page) + the advertiser/publisher side switcher.
	// Role checks live inside the handlers (any org member may view; admin
	// actions are gated per handler + atomically in the repository).
	mux.HandleFunc("GET /org/members", h.OrgMembersPage)
	mux.HandleFunc("POST /org/invite", h.OrgInvite)
	mux.HandleFunc("POST /org/invite-link", h.OrgInviteLink)
	mux.HandleFunc("POST /org/members/promote", h.OrgMemberAction("promote"))
	mux.HandleFunc("POST /org/members/demote", h.OrgMemberAction("demote"))
	mux.HandleFunc("POST /org/members/remove", h.OrgMemberAction("remove"))
	mux.HandleFunc("POST /org/request-side", h.OrgRequestSide)
	mux.HandleFunc("POST /account/switch-side", h.SwitchSide)

	// Read-only admin view-as (exit lives outside /admin so the viewed-user
	// session can reach it; SessionGuard exempts it from read-only).
	mux.HandleFunc("POST /view-as/exit", h.ViewAsExit)

	// Admin pages
	adm := func(fn http.HandlerFunc) http.HandlerFunc { return h.RoleGuard("admin", fn) }
	mux.HandleFunc("GET /admin", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/admin/requests", http.StatusSeeOther)
	})
	mux.HandleFunc("GET /admin/requests", adm(h.AdminRequests))
	mux.HandleFunc("POST /admin/requests/approve", adm(h.ApprovalDecision("approve")))
	mux.HandleFunc("POST /admin/requests/reject", adm(h.ApprovalDecision("reject")))
	mux.HandleFunc("GET /admin/sites", adm(h.AdminSiteRequests))
	mux.HandleFunc("POST /admin/sites/approve", adm(h.SiteRequestDecision("approve")))
	mux.HandleFunc("POST /admin/sites/reject", adm(h.SiteRequestDecision("reject")))
	mux.HandleFunc("GET /admin/users", adm(h.AdminUsers))
	mux.HandleFunc("POST /admin/users/recovery-link", adm(h.MintRecoveryLink))
	mux.HandleFunc("POST /admin/users/invite-admin", adm(h.InviteAdmin))
	mux.HandleFunc("POST /admin/users/delete", adm(h.AdminDeleteUser))
	mux.HandleFunc("POST /admin/view-as", adm(h.AdminViewAs))
	mux.HandleFunc("POST /admin/org-side/approve", adm(h.OrgSideDecision("approve")))
	mux.HandleFunc("POST /admin/org-side/reject", adm(h.OrgSideDecision("reject")))
	mux.HandleFunc("GET /admin/settings", adm(h.AdminSettings))
	mux.HandleFunc("POST /admin/settings/margin", adm(h.UpdateMargin))
	mux.HandleFunc("POST /admin/settings/payout-floor", adm(h.UpdatePayoutFloor))
	mux.HandleFunc("POST /admin/settings/org-max-members", adm(h.UpdateOrgMaxMembers))
	mux.HandleFunc("GET /admin/billing", adm(h.AdminBilling))
	mux.HandleFunc("GET /admin/billing/topups", adm(h.AdminBillingTopups))
	mux.HandleFunc("GET /admin/billing/accounts", adm(h.AdminBillingAccounts))
	mux.HandleFunc("GET /admin/billing/accounts/{ownerType}/{ownerID}", adm(h.AdminBillingAccount))
	mux.HandleFunc("GET /admin/billing/payouts", adm(h.AdminBillingPayouts))
	mux.HandleFunc("GET /admin/billing/journal", adm(h.AdminBillingJournal))
	mux.HandleFunc("POST /admin/billing/topup", adm(h.AdminRecordTopup))
	mux.HandleFunc("POST /admin/billing/payouts/create", adm(h.AdminCreatePayout))
	mux.HandleFunc("POST /admin/billing/payouts/paid", adm(h.AdminMarkPayoutPaid))
	mux.HandleFunc("POST /admin/billing/payouts/cancel", adm(h.AdminCancelPayout))
	mux.HandleFunc("POST /admin/billing/adjust", adm(h.AdminAdjust))
	// JSON search behind the billing comboboxes (admin-checked in-handler,
	// returns JSON errors rather than the RoleGuard's login redirect).
	mux.HandleFunc("GET /api/billing/accounts", h.SearchBillingAccounts)

	// Publisher pages (role-guarded)
	pub := func(fn http.HandlerFunc) http.HandlerFunc { return h.RoleGuard("publisher", fn) }
	mux.HandleFunc("GET /publisher/approval", pub(h.PublisherApproval))
	mux.HandleFunc("GET /publisher/approval/partial", pub(h.PublisherApproval))
	mux.HandleFunc("POST /publisher/approval/approve", pub(h.ApprovalAction("approve")))
	mux.HandleFunc("POST /publisher/approval/reject", pub(h.ApprovalAction("reject")))
	mux.HandleFunc("POST /publisher/approval/flag", pub(h.ApprovalAction("flag")))
	mux.HandleFunc("POST /publisher/approval/revoke", pub(h.ApprovalAction("revoke")))
	mux.HandleFunc("POST /publisher/approval/unflag", pub(h.ApprovalAction("unflag")))
	mux.HandleFunc("POST /publisher/approval/auto-approve", pub(h.PublisherAutoApprove))
	mux.HandleFunc("POST /publisher/approval/trust-anchor/remove", pub(h.PublisherTrustAnchorRemove))
	mux.HandleFunc("GET /publisher/trusted", pub(h.PublisherTrusted))
	mux.HandleFunc("GET /publisher/sites", pub(h.PublisherSites))
	mux.HandleFunc("POST /publisher/block-advertiser-domain", pub(h.BlockAdvertiserDomain))
	mux.HandleFunc("POST /publisher/unblock-advertiser-domain", pub(h.UnblockAdvertiserDomain))
	mux.HandleFunc("POST /publisher/sites/create", pub(h.CreateSite))
	mux.HandleFunc("POST /publisher/sites/requests/{id}/delete", pub(h.DeleteSiteRequest))
	mux.HandleFunc("POST /publisher/sites/{siteId}/delete", pub(h.DeleteSite))
	mux.HandleFunc("POST /publisher/sites/{siteId}/verify", pub(h.VerifySite))
	mux.HandleFunc("GET /publisher/sites/{siteId}/verification-token", pub(h.GetVerificationToken))
	mux.HandleFunc("POST /publisher/sites/floor", pub(h.UpdateFloorCPM))
	mux.HandleFunc("POST /publisher/sites/min-floor", pub(h.UpdateMinFloorCPM))
	mux.HandleFunc("POST /publisher/sites/slot-floor", pub(h.UpdateSlotFloorOverride))
	mux.HandleFunc("GET /publisher/sites/{siteId}/observations", pub(h.FloorObservations))
	mux.HandleFunc("POST /publisher/sites/reset-floor-agent", pub(h.ResetFloorAgent))
	mux.HandleFunc("POST /publisher/sites/bid-weight", pub(h.UpdateBidWeight))
	mux.HandleFunc("POST /publisher/sites/filler-traffic", pub(h.UpdateAcceptsFillerTraffic))
	mux.HandleFunc("POST /publisher/sites/block-ad-product", pub(h.BlockAdProduct))
	mux.HandleFunc("POST /publisher/sites/unblock-ad-product", pub(h.UnblockAdProduct))
	mux.HandleFunc("GET /publisher/stats", pub(h.PublisherStats))
	mux.HandleFunc("GET /publisher/earnings", pub(h.PublisherEarnings))
	mux.HandleFunc("GET /publisher/report", pub(h.PublisherReport))
	mux.HandleFunc("GET /publisher/help", pub(h.HelpPage(model.RolePublisher)))
	mux.HandleFunc("POST /publisher/payout-method", pub(h.UpdatePayoutMethod))

	// Advertiser pages (role-guarded)
	adv := func(fn http.HandlerFunc) http.HandlerFunc { return h.RoleGuard("advertiser", fn) }
	mux.HandleFunc("GET /advertiser/account", adv(h.AdvertiserAccount))
	mux.HandleFunc("GET /advertiser/campaigns", adv(h.AdvertiserCampaigns))
	// Stats existed as a handler+template but was never routed (nor
	// linked in the nav) — the page was unreachable dead code.
	mux.HandleFunc("GET /advertiser/stats", adv(h.AdvertiserStats))
	mux.HandleFunc("GET /advertiser/report", adv(h.AdvertiserReport))
	mux.HandleFunc("GET /advertiser/wallet", adv(h.AdvertiserWallet))
	mux.HandleFunc("GET /advertiser/help", adv(h.HelpPage(model.RoleAdvertiser)))
	mux.HandleFunc("POST /advertiser/budget", adv(h.SetAdvertiserBudget))
	mux.HandleFunc("POST /advertiser/campaigns/create", adv(h.CreateCampaign))
	mux.HandleFunc("POST /advertiser/campaigns/update", adv(h.UpdateCampaign))
	mux.HandleFunc("POST /advertiser/campaigns/schedule", adv(h.UpdateCampaignSchedule))
	mux.HandleFunc("POST /advertiser/campaigns/toggle", adv(h.ToggleCampaignStatus))
	mux.HandleFunc("POST /advertiser/campaigns/delete", adv(h.DeleteCampaign))
	mux.HandleFunc("POST /advertiser/block-site", adv(h.BlockSite))
	mux.HandleFunc("POST /advertiser/unblock-site", adv(h.UnblockSite))
	mux.HandleFunc("POST /advertiser/campaigns/cpm", adv(h.UpdateCampaignCPM))
	mux.HandleFunc("POST /advertiser/campaigns/filler", adv(h.UpdateCampaignFiller))
	mux.HandleFunc("GET /advertiser/creatives/editor", adv(h.CreativeEditor))
	mux.HandleFunc("GET /advertiser/creatives/design", adv(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/advertiser/creatives/editor", http.StatusSeeOther)
	}))
	mux.HandleFunc("POST /advertiser/creatives/design", adv(h.CreativeDesign))
	mux.HandleFunc("POST /advertiser/creatives/save", adv(h.SaveCreative))
	mux.HandleFunc("POST /advertiser/creatives/resume-draft", adv(h.ResumeDraft))
	mux.HandleFunc("POST /advertiser/creatives/upload-page-image", adv(h.UploadPageImage))
	mux.HandleFunc("POST /advertiser/creatives/analyze-lp", adv(h.AnalyzeLP))
	mux.HandleFunc("POST /advertiser/creatives/analyze-lp/start", adv(h.AnalyzeLPStart))
	mux.HandleFunc("GET /advertiser/creatives/analyze-lp/status", adv(h.AnalyzeLPStatus))
	mux.HandleFunc("GET /advertiser/creatives/analyze-lp/screenshot", adv(h.AnalyzeLPScreenshot))
	mux.HandleFunc("POST /advertiser/creatives/rewrite-sections", adv(h.RewriteSections))
	mux.HandleFunc("POST /advertiser/creatives/generate-layout", adv(h.GenerateLayout))
	mux.HandleFunc("POST /advertiser/creatives/generate-layout-pair", adv(h.GenerateLayoutPair))
	mux.HandleFunc("POST /advertiser/creatives/rewrite-copy", adv(h.RewriteCopy))
	mux.HandleFunc("GET /advertiser/creatives/layout-templates", adv(h.LayoutTemplates))
	mux.HandleFunc("GET /advertiser/assets", adv(h.ListAdvertiserAssets))
	mux.HandleFunc("POST /advertiser/assets", adv(h.UploadAdvertiserAsset))
	mux.HandleFunc("POST /advertiser/assets/import-urls", adv(h.ImportAdvertiserAssetUrls))
	mux.HandleFunc("POST /advertiser/assets/presigned-upload", adv(h.PresignAdvertiserAsset))
	mux.HandleFunc("POST /advertiser/assets/register", adv(h.RegisterAdvertiserAsset))
	// Same-origin proxy for canvas-reading code paths (auto-crop saliency).
	// Public — assets are already public on the R2 CDN; this just adds the
	// Access-Control-Allow-Origin header so the browser can read pixels.
	mux.HandleFunc("GET /proxy/asset/{hash...}", h.ProxyAsset)
	mux.HandleFunc("DELETE /advertiser/assets/{id}", adv(h.DeleteAdvertiserAsset))
	mux.HandleFunc("GET /advertiser/creatives", adv(h.AdvertiserCreatives))
	mux.HandleFunc("GET /advertiser/creatives/{creativeId}/pages", adv(h.CreativePages))
	mux.HandleFunc("POST /advertiser/creatives/toggle", adv(h.ToggleCreativeStatus))
	mux.HandleFunc("POST /advertiser/creatives/reprocess", adv(h.ReprocessCreative))
	mux.HandleFunc("POST /advertiser/creatives/delete", adv(h.DeleteCreative))

	// SSE proxy — Go BFF re-emits core API events to browser.
	// The publisher-level stream carries every site's events on one
	// connection (what the multi-site approval inbox needs); the per-site
	// route is kept for compatibility. The literal "events" segment is more
	// specific than {siteId}, so both patterns coexist.
	mux.HandleFunc("GET /sse/publishers/events", pub(h.SSEProxyPublisher))
	mux.HandleFunc("GET /sse/publishers/{siteId}/events", pub(h.SSEProxy))

	// JSON endpoints for Alpine.js autocomplete/polling
	mux.HandleFunc("GET /api/taxonomy/ad-products", h.SearchAdProducts)
	mux.HandleFunc("GET /api/taxonomy/categories", h.SearchCategories)
	mux.HandleFunc("GET /api/sites", h.SearchSites)
	mux.HandleFunc("GET /api/advertiser-domains", h.SearchAdvertiserDomains)
	mux.HandleFunc("GET /api/image-proxy", h.ImageProxy)

	// Health check
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `{"status":"ok"}`)
	})

	srv := &http.Server{
		Addr:         cfg.ListenAddr,
		Handler:      middleware.CORS(cfg.AllowedOrigin)(setupSvc.Gate(h.SessionGuard(mux))),
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 120 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		slog.Info("platform server starting", "addr", cfg.ListenAddr, "core", cfg.CoreAPIURL)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("server failed", "error", err)
			os.Exit(1)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	slog.Info("shutting down...")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	srv.Shutdown(ctx)
}

// runMintRecovery mints a one-time passkey recovery link for a user without
// requiring an admin session — the operator's escape hatch. Anyone who can
// run this already owns the database, so it grants nothing new.
func runMintRecovery(cfg config.Config, args []string) int {
	fs := flag.NewFlagSet("mint-recovery", flag.ContinueOnError)
	email := fs.String("email", "", "email of the account to recover")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *email == "" {
		fmt.Fprintln(os.Stderr, "usage: server mint-recovery --email <email>")
		return 2
	}

	pool, err := db.Connect(cfg.DatabaseURL)
	if err != nil {
		fmt.Fprintln(os.Stderr, "database:", err)
		return 1
	}
	defer pool.Close()

	ctx := context.Background()
	u, err := user.NewRepository(pool).GetByEmail(ctx, *email)
	if err != nil {
		fmt.Fprintf(os.Stderr, "no account for %s\n", *email)
		return 1
	}

	raw, err := passkey.NewRepository(pool).CreateRecoveryToken(ctx, u.ID, "")
	if err != nil {
		fmt.Fprintln(os.Stderr, "mint failed:", err)
		return 1
	}

	origin := "http://localhost:9091"
	if len(cfg.RPOrigins) > 0 {
		origin = cfg.RPOrigins[0]
	}
	fmt.Printf("One-time recovery link for %s (valid 72h, single use):\n\n  %s/recover/%s\n\nOpen it on the device that should hold the new passkey.\n", u.Email, origin, raw)
	return 0
}

// runMintSession mints a signed session JWT for an active user without a
// passkey ceremony — the scriptable-login escape hatch for automation that
// drives the dashboard (passkey ceremonies need a real browser
// authenticator). Anyone who can run this already reads JWT_SECRET from the
// pod env, so it grants nothing new. The token dies with the account: every
// page render re-checks the user's status in the DB.
func runMintSession(cfg config.Config, args []string) int {
	fs := flag.NewFlagSet("mint-session", flag.ContinueOnError)
	email := fs.String("email", "", "email of the account to sign in as")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *email == "" {
		fmt.Fprintln(os.Stderr, "usage: server mint-session --email <email>")
		return 2
	}

	pool, err := db.Connect(cfg.DatabaseURL)
	if err != nil {
		fmt.Fprintln(os.Stderr, "database:", err)
		return 1
	}
	defer pool.Close()

	ctx := context.Background()
	u, err := user.NewRepository(pool).GetByEmail(ctx, *email)
	if err != nil {
		fmt.Fprintf(os.Stderr, "no account for %s\n", *email)
		return 1
	}
	if u.Status != model.StatusActive {
		fmt.Fprintf(os.Stderr, "account %s is not active (status: %s)\n", u.Email, u.Status)
		return 1
	}

	// Org members get an org session (side defaults like a real login);
	// the platform admin and pre-org accounts get a plain token.
	jwtSvc := auth.NewJWTService(cfg.JWTSecret, cfg.JWTExpiry)
	var token string
	if o, m, oerr := org.NewRepository(pool).ForUser(ctx, u.ID); oerr == nil && u.Role != model.RoleAdmin {
		token, err = jwtSvc.IssueSession(u, auth.SessionOpts{Org: o, OrgRole: m.OrgRole, Side: m.DefaultSide(o)})
	} else {
		token, err = jwtSvc.Issue(u)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "mint failed:", err)
		return 1
	}

	fmt.Printf("Session token for %s (%s, valid %s):\n\n%s\n\nUse it as the session cookie, e.g.:\n  curl -H 'Cookie: token=%s' <origin>/...\n", u.Email, u.Role, cfg.JWTExpiry, token, token)
	return 0
}

// runSettleDay force-settles one UTC day through the same code path the
// scheduled job uses (metering → ledger → daily_settlements + wallet
// debit/publisher credit), bypassing the up-to-yesterday finality window so
// the billing loop can be exercised before a day naturally closes.
// Idempotent — re-running a day is a no-op via the settlement idempotency
// keys. Defaults to today (UTC).
func runSettleDay(cfg config.Config, args []string) int {
	fs := flag.NewFlagSet("settle-day", flag.ContinueOnError)
	dateStr := fs.String("date", "", "UTC day to settle, YYYY-MM-DD (default: today)")
	if err := fs.Parse(args); err != nil {
		return 2
	}

	day := time.Now().UTC()
	if *dateStr != "" {
		d, err := time.Parse("2006-01-02", *dateStr)
		if err != nil {
			fmt.Fprintf(os.Stderr, "invalid --date %q (want YYYY-MM-DD): %v\n", *dateStr, err)
			return 2
		}
		day = d.UTC()
	}

	pool, err := db.Connect(cfg.DatabaseURL)
	if err != nil {
		fmt.Fprintln(os.Stderr, "database:", err)
		return 1
	}
	defer pool.Close()

	billingSvc := billing.NewService(pool)
	settingsSvc := settings.NewService(pool)
	settler := billing.NewSettler(
		billingSvc, pool,
		billing.NewHTTPCoreClient(cfg.CoreAPIURL, cfg.InternalAPIKey),
		settingsSvc.MarginBpsAt,
	)

	ctx := context.Background()
	if err := settler.SettleDay(ctx, day); err != nil {
		fmt.Fprintf(os.Stderr, "settle %s failed: %v\n", day.Format("2006-01-02"), err)
		return 1
	}

	var rows, skipped int
	var grossMicros int64
	pool.QueryRow(ctx, `SELECT rows_settled, rows_skipped, gross_micros FROM billing_settlement_days WHERE day = $1::date`,
		day.Format("2006-01-02")).Scan(&rows, &skipped, &grossMicros)
	fmt.Printf("Settled %s: %d rows booked, %d skipped (no publisher mapping), gross $%.2f\n",
		day.Format("2006-01-02"), rows, skipped, float64(grossMicros)/1e6)
	return 0
}
