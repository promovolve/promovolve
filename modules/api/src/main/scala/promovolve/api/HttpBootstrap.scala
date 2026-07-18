package promovolve.api

import com.typesafe.config.Config
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem }
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.HttpMethods.*
import org.apache.pekko.http.scaladsl.model.headers.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import promovolve.*
import promovolve.advertiser.CampaignDirectory
import promovolve.api.guard.TrackingReplayGuard
import promovolve.api.projection.{ DashboardRoutes, TrackingEventJournal }
import promovolve.common.EntityBackedPublisherSecretsRepo
import promovolve.publisher.delivery.ServeIndexDData
import promovolve.publisher.{
  AdvertiserAssetRepo,
  CreativeRepo,
  EntityBackedPublisherSettings,
  ImageAssetRepo,
  PublisherEmailRepo,
  SlickAdvertiserAssetRepo,
  SlickImageAssetRepo,
  SlickPublisherEmailRepo
}
import promovolve.publisher.assets.{ ImageStorage, R2ImageStorage }
import promovolve.advertiser.{ AdvertiserEmailRepo, SlickAdvertiserEmailRepo }
import scala.util.Try
import promovolve.taxonomy.CategoryRegistry
import org.apache.pekko.actor.typed.pubsub.Topic
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{ Failure, Success }

/** HTTP-related initialization for API nodes. */
object HttpBootstrap {

  // ========================================
  // HTTP Dependencies
  // ========================================
  object HttpDependencies {
    def init(
        sharding: ClusterSharding,
        campaignDirectory: ActorRef[CampaignDirectory.Command],
        categoryRegistry: ActorRef[CategoryRegistry.Command],
        serveIndex: ActorRef[ServeIndexDData.Cmd],
        creativeRepo: CreativeRepo,
        // Shared store from ClusterBootstrap.Repositories — the trusted-
        // anchors listing reads it directly (same instance AdServer writes).
        pendingSelectionStore: promovolve.publisher.PendingSelectionStore,
        budgetEventTopic: ActorRef[Topic.Command[BudgetEvent]],
        config: Config,
        affinityRegistry: Option[ActorRef[promovolve.taxonomy.AffinityRegistryDData.Cmd]] = None,
        geminiRateLimiter: Option[ActorRef[promovolve.GeminiRateLimiter.Command]] = None,
        browserPool: ActorRef[promovolve.browser.BrowserSessionPool.Command]
    )(using system: ActorSystem[?]): Refs = {
      system.log.info("Initializing HTTP routes...")

      val appConfig = config.getConfig("promovolve")

      // Security - backed by PublisherEntity
      val secretsRepo = new EntityBackedPublisherSecretsRepo(sharding)

      // Replay guard settings (single source of truth for timing)
      val replayGuardConfig = appConfig.getConfig("replay-guard")
      val replayGuardEnabled = replayGuardConfig.getBoolean("enabled")

      // URL validity window: both TrackRoutes and ReplayGuard derive timing from this
      val urlValidityWindow = replayGuardConfig.getDuration("url-validity-window").toMillis.millis
      val partitions = replayGuardConfig.getInt("partitions")
      val expectedPerPartition = replayGuardConfig.getInt("expected-per-partition")

      // Replay guard for tracking URL deduplication
      // Bloom bucket = urlValidityWindow + 1 min, coverage = 2× bucket
      val replayGuard = if (replayGuardEnabled) {
        system.log.info(
          "Initializing TrackingReplayGuard: validity={}ms, partitions={}, expectedPerPartition={}",
          urlValidityWindow.toMillis,
          partitions,
          expectedPerPartition
        )
        Some(system.systemActorOf(
          TrackingReplayGuard(
            sharding,
            urlValidityWindow = urlValidityWindow,
            partitions = partitions,
            expectedPerPartition = expectedPerPartition
          ),
          "tracking-replay-guard"
        ))
      } else None

      // Publisher settings (classification freshness windows) - backed by PublisherEntity
      val publisherSettings = new EntityBackedPublisherSettings(sharding)

      // CDN base URL for asset URLs
      val cdnBase = appConfig.getString("cdn-base-url")

      // URL publisher browsers, the dashboard, and the crawler's Playwright
      // all fetch the <expandable-magazine-banner> web component from.
      val bannerScriptUrl = appConfig.getString("banner-script-url")

      // Image storage: R2 is required. No in-memory fallback — it loses
      // creatives on restart and silently masks a misconfigured deploy.
      val imageStorage: ImageStorage = R2ImageStorage.fromEnv()(using system).getOrElse {
        throw new IllegalStateException(
          "R2 not configured. Set R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, and R2_BUCKET."
        )
      }
      val storageType = "R2 (Cloudflare)"

      // Database-backed repos for creative storage (required)
      // NOTE: creativeRepo is passed in from ClusterBootstrap.Repositories to ensure
      // both entities (AdServer) and HTTP routes use the SAME repo instance
      val (imageAssetRepo, advertiserEmailRepo, publisherEmailRepo, advertiserAssetRepo, mountBeaconRepo) = try {
        val dbConfig = DatabaseConfig.forConfig[PostgresProfile]("dashboard-projection-db", config)
        val db = dbConfig.db

        val imgRepo = new SlickImageAssetRepo(db)(using system.executionContext)
        imgRepo.ensureSchema()

        val advEmailRepo = new SlickAdvertiserEmailRepo(db)(using system.executionContext)
        advEmailRepo.ensureSchema()

        val pubEmailRepo = new SlickPublisherEmailRepo(db)(using system.executionContext)
        pubEmailRepo.ensureSchema()

        val advAssetRepo = new SlickAdvertiserAssetRepo(db)(using system.executionContext)
        advAssetRepo.ensureSchema()

        val beaconRepo = new promovolve.publisher.SlickMountBeaconRepo(db)(using system.executionContext)
        beaconRepo.ensureSchema()

        system.log.info(
          "ImageAssetRepo, AdvertiserEmailRepo, PublisherEmailRepo, AdvertiserAssetRepo, MountBeaconRepo initialized (PostgreSQL), ImageStorage: {}",
          storageType)
        system.log.info("Using shared CreativeRepo from ClusterBootstrap.Repositories")
        (imgRepo: ImageAssetRepo, advEmailRepo: AdvertiserEmailRepo, pubEmailRepo: PublisherEmailRepo,
          advAssetRepo: AdvertiserAssetRepo, beaconRepo: promovolve.publisher.MountBeaconRepo)
      } catch {
        case ex: Exception =>
          system.log.error("Failed to initialize database repos: {}", ex.getMessage)
          throw ex // Can't continue without database
      }

      val serveRoutes = new ServeRoutes(
        appConfig.getString("tracking-base-url"),
        sharding,
        secretsRepo,
        publisherSettings,
        cdnBase,
        bannerScriptUrl,
        Some(creativeRepo)
      )(using system)

      // Initialize TrackingEventJournal for dashboard projection
      val trackingJournal = try {
        val dbConfig = DatabaseConfig.forConfig[PostgresProfile]("dashboard-projection-db", config)
        Some(new TrackingEventJournal(dbConfig.db)(using system.executionContext, system))
      } catch {
        case ex: Exception =>
          system.log.warn("Dashboard projection disabled: {}", ex.getMessage)
          None
      }

      // Initialize FloorDecisionJournal (parallel to tracking journal,
      // writes one row per completed sweep cycle). Registers itself as
      // the global writer via FloorDecisionRecorder so SiteEntity can
      // call it at argmax-pick time without constructor-plumbing.
      val floorDecisionJournal = try {
        val dbConfig = DatabaseConfig.forConfig[PostgresProfile]("dashboard-projection-db", config)
        val j = new projection.FloorDecisionJournal(dbConfig.db)(using system.executionContext, system)
        promovolve.publisher.FloorDecisionRecorder.set(j)
        Some(j)
      } catch {
        case ex: Exception =>
          system.log.warn("Floor-decision persistence disabled: {}", ex.getMessage)
          None
      }
      floorDecisionJournal.foreach { journal =>
        CoordinatedShutdown(system).addTask(
          CoordinatedShutdown.PhaseBeforeServiceUnbind,
          "flush-floor-decision-journal"
        ) { () =>
          system.log.info("Flushing floor decision journal...")
          journal.shutdown()
        }
      }

      // Register shutdown hook to drain buffered tracking events
      trackingJournal.foreach { journal =>
        CoordinatedShutdown(system).addTask(
          CoordinatedShutdown.PhaseBeforeServiceUnbind,
          "flush-tracking-journal"
        ) { () =>
          system.log.info("Flushing tracking event journal...")
          journal.shutdown()
        }
      }

      val eventLog = new LearningEventLog(sharding, trackingJournal, affinityRegistry, Some(creativeRepo))(using system)
      val trackRoutes = new TrackRoutes(
        secretsRepo,
        eventLog,
        maxSkew = urlValidityWindow, // Same source of truth
        replayGuard = replayGuard,
        mountBeacons = Some(mountBeaconRepo)
      )(using system)

      val enableTestRoutes = Try(config.getBoolean("promovolve.enable-test-routes")).getOrElse(false)
      if (enableTestRoutes) system.log.warn("Test routes enabled — do not use in production")
      val auctionRoutes =
        new AuctionRoutes(sharding, serveIndex, creativeRepo, eventLog, enableTestRoutes)(using system)

      // Category verification client (optional, requires GEMINI_API_KEY and promovolve.gemini.enabled=true)
      val geminiEnabled = Try(config.getBoolean("promovolve.gemini.enabled")).getOrElse(false)
      val categoryVerificationClient = if (geminiEnabled) {
        sys.env.get("GEMINI_API_KEY").filter(_.nonEmpty).map { apiKey =>
          system.log.info("CategoryVerificationClient enabled (Gemini)")
          new promovolve.publisher.assessment.CategoryVerificationClient(apiKey)(using system, system.executionContext)
        }
      } else {
        system.log.info("CategoryVerificationClient disabled (promovolve.gemini.enabled=false)")
        None
      }

      // LP-to-Creative copy rewriter (optional, requires GEMINI_API_KEY).
      // Passes the shared GeminiRateLimiter through so LP extraction
      // counts against the same RPM budget as taxonomy classification —
      // otherwise campaign creation / creative regen can burst past
      // the quota and 429 the whole system.
      val lpExtractor = if (geminiEnabled) {
        sys.env.get("GEMINI_API_KEY").filter(_.nonEmpty).map { apiKey =>
          system.log.info("LPExtractor enabled (Gemini) — copy rewriting only, rate-limited={}",
            geminiRateLimiter.isDefined)
          new promovolve.creative.LPExtractor(apiKey, rateLimiter = geminiRateLimiter)(using system,
            system.executionContext)
        }
      } else None

      // LP Analyzer (Playwright-based section extraction + banner screenshots)
      val lpAnalyzer = try {
        val analyzer = new promovolve.browser.LPAnalyzer(bannerScriptUrl, browserPool)
        system.log.info("LPAnalyzer enabled (Playwright via BrowserSessionPool), banner-script-url={}", bannerScriptUrl)
        Some(analyzer)
      } catch {
        case ex: Exception =>
          system.log.warn("LPAnalyzer not available: {}", ex.getMessage)
          None
      }

      // Creative assessor: disabled (using CategoryVerificationClient instead)
      val creativeAssessor: Option[ActorRef[promovolve.publisher.assessment.CreativeAssessor.Command]] = None

      // Creative processor: orchestrates image download, banner render, verification
      val creativeProcessor: Option[ActorRef[CreativeProcessor.Command]] = {
        import org.apache.pekko.actor.typed.DispatcherSelector
        // Self-hosted web fonts: fetch allow-listed Google fonts at publish and
        // store in R2 so the expanded view loads them from our CDN, never Google
        // at the visitor's runtime.
        val fontProvisioner = Some(new GoogleFontProvisioner(imageStorage))
        val behavior = CreativeProcessor(
          creativeRepo, imageAssetRepo, imageStorage, cdnBase,
          lpAnalyzer, categoryVerificationClient, sharding,
          fontProvisioner,
          budgetEventTopic = Some(budgetEventTopic)
        )
        Some(system.systemActorOf(
          org.apache.pekko.actor.typed.scaladsl.Behaviors.supervise(behavior)
            .onFailure(org.apache.pekko.actor.typed.SupervisorStrategy.resume),
          "creative-processor",
          DispatcherSelector.fromConfig("blocking-io-dispatcher")
        ))
      }

      // PendingEventHub for SSE notifications (created before EndpointRoutes so it can be passed in)
      val pendingEventHub = system.systemActorOf(PendingEventHub(), "pending-event-hub")
      system.log.info("PendingEventHub initialized for SSE notifications")

      // Dashboard DB for both DashboardRoutes and EndpointRoutes (win rate query)
      val dashboardDbConfig = try {
        Some(DatabaseConfig.forConfig[PostgresProfile]("dashboard-projection-db", config))
      } catch {
        case ex: Exception =>
          system.log.warn("Dashboard DB not available: {}", ex.getMessage)
          None
      }

      val lpWorkerEnabled =
        appConfig.hasPath("crawler.lp-workers.enabled") && appConfig.getBoolean("crawler.lp-workers.enabled")
      val lpWorkerNumWorkers =
        if (appConfig.hasPath("crawler.lp-workers.num-workers")) appConfig.getInt("crawler.lp-workers.num-workers")
        else 4
      if (lpWorkerEnabled)
        system.log.info("LP analysis dispatch: crawler-tier LPWorker (num-workers={})", lpWorkerNumWorkers)

      val endpointRoutes = new EndpointRoutes(
        sharding,
        serveIndex,
        categoryVerificationClient,
        creativeAssessor,
        Some(imageAssetRepo),
        Some(creativeRepo),
        Some(imageStorage),
        cdnBase, // Reuse existing cdn-base-url config
        Some(advertiserEmailRepo),
        Some(publisherEmailRepo),
        budgetEventTopic,
        pendingEventHub = Some(pendingEventHub),
        dashboardDb = dashboardDbConfig.map(_.db.asInstanceOf[slick.jdbc.PostgresProfile.backend.Database]),
        lpExtractor = lpExtractor,
        lpAnalyzer = lpAnalyzer,
        creativeProcessor = creativeProcessor,
        advertiserAssetRepo = Some(advertiserAssetRepo),
        floorDecisionJournal = floorDecisionJournal,
        trackingEventJournal = trackingJournal,
        lpWorkerEnabled = lpWorkerEnabled,
        lpWorkerNumWorkers = lpWorkerNumWorkers,
        pendingSelectionStore = Some(pendingSelectionStore)
      )(using system)

      // Dashboard routes for advertiser performance data
      val dashboardRoutes = dashboardDbConfig.map(dbConfig =>
        new DashboardRoutes(dbConfig)(using system)
      )

      // Subscribe PendingEventHub to BudgetEvent topic to receive PendingCreativesQueued events
      budgetEventTopic ! Topic.Subscribe(
        system.systemActorOf(
          PendingEventHubAdapter(pendingEventHub),
          "pending-event-hub-adapter"
        )
      )

      val sseRoutes = new SseRoutes(pendingEventHub, sharding)

      Refs(serveRoutes, trackRoutes, auctionRoutes, endpointRoutes, dashboardRoutes, sseRoutes)
    }

    case class Refs(
        serveRoutes: ServeRoutes,
        trackRoutes: TrackRoutes,
        auctionRoutes: AuctionRoutes,
        endpointRoutes: EndpointRoutes,
        dashboardRoutes: Option[DashboardRoutes],
        sseRoutes: SseRoutes
    )
  }

  // ========================================
  // HTTP Server
  // ========================================
  object HttpServer {
    def start(config: Config, deps: HttpDependencies.Refs)(using system: ActorSystem[?]): Unit = {
      given ExecutionContext = system.executionContext

      val httpConfig = config.getConfig("http")
      val host = httpConfig.getString("host")
      val port = httpConfig.getInt("port")

      val baseRoutes = deps.endpointRoutes.routes ~
        deps.serveRoutes.routes ~
        deps.trackRoutes.routes ~
        deps.auctionRoutes.routes ~
        deps.sseRoutes.routes

      val routesWithDashboard = deps.dashboardRoutes match {
        case Some(dashboard) => baseRoutes ~ dashboard.routes
        case None            => baseRoutes
      }

      // CORS support for development (allows requests from localhost:5173, etc.)
      val corsHeaders = List(
        `Access-Control-Allow-Origin`.*,
        `Access-Control-Allow-Methods`(GET, POST, PUT, PATCH, DELETE, OPTIONS),
        `Access-Control-Allow-Headers`("Content-Type", "Authorization", "X-Requested-With"),
        `Access-Control-Max-Age`(86400)
      )

      val routes = respondWithHeaders(corsHeaders) {
        options { complete("") } ~ routesWithDashboard
      }

      Http()
        .newServerAt(host, port)
        .bind(routes)
        .onComplete {
          case Success(binding) =>
            system.log.info(
              "PromoVolve HTTP server started at http://{}:{}",
              binding.localAddress.getHostString,
              binding.localAddress.getPort
            )
          case Failure(ex) =>
            system.log.error("Failed to start HTTP server", ex)
            system.terminate()
        }
    }
  }
}
