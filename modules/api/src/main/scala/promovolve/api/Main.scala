package promovolve.api

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import promovolve.api.projection.DashboardProjection
import promovolve.cluster.ClusterBootstrap
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

/** Unified Main for all node types.
  *
  * Behavior is determined by cluster roles in config:
  *   - "singleton" role: Hosts cluster singletons (CampaignDirectory, CategoryRegistry)
  *   - "entity" role: Hosts sharded entities
  *   - "api" role: Starts HTTP server
  *
  * Examples:
  *   - API node: roles = ["api"]
  *   - Entity node: roles = ["entity"]
  *   - Singleton node: roles = ["singleton", "entity"]
  *   - All-in-one (dev): roles = ["singleton", "entity", "api"]
  *
  * Usage:
  *   sbt "api/run" (uses application.conf in api module)
  */
object Main {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()
    val system = ActorSystem(Guardian(config), "promovolve", config)

    scala.concurrent.Await.result(system.whenTerminated, scala.concurrent.duration.Duration.Inf)
  }

  object Guardian {
    def apply(config: Config): Behavior[Nothing] = Behaviors.setup[Nothing] { context =>
      given system: ActorSystem[Nothing] = context.system
      given ec: ExecutionContext         = context.executionContext

      val roles = config.getStringList("pekko.cluster.roles").asScala.toSet
      context.log.info("Starting PromoVolve node with roles: {}", roles.mkString(", "))

      // Multi-pod clustering: form the cluster via Pekko Management +
      // Cluster Bootstrap using Kubernetes API discovery. Enabled only when
      // PEKKO_CLUSTER_BOOTSTRAP=on (k8s); otherwise the node uses the static
      // seed-nodes from config (local / single-node dev). Fully-qualified to
      // avoid clashing with promovolve.cluster.ClusterBootstrap.
      if (sys.env.get("PEKKO_CLUSTER_BOOTSTRAP").contains("on")) {
        context.log.info("Pekko Management + Cluster Bootstrap (Kubernetes API discovery)")
        org.apache.pekko.management.scaladsl.PekkoManagement(system).start()
        org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap(system).start()
      }

      val sharding = ClusterSharding(system)

      // Initialize topics (all nodes need these for pub/sub)
      val topics = ClusterBootstrap.Topics.init(context)

      // Initialize repositories (PostgreSQL-backed for persistence)
      val repositories = ClusterBootstrap.Repositories.init(config)

      // Singletons: actual instances on singleton nodes, proxies on others
      val (campaignDirectory, categoryRegistry, serveIndex, affinityRegistry, geminiRateLimiter, browserPool) =
        if (roles.contains("singleton")) {
          context.log.info("Initializing cluster singletons (this node hosts them)")
          val singletons = ClusterBootstrap.Singletons.init(
            system,
            sharding,
            topics
          )
          (singletons.campaignDirectory, singletons.categoryRegistry, singletons.serveIndex, singletons.affinityRegistry, singletons.geminiRateLimiter, singletons.browserPool)
        } else {
          context.log.info("Initializing singleton proxies")
          val proxies = ClusterBootstrap.SingletonProxies.init(system, sharding, topics)
          (proxies.campaignDirectory, proxies.categoryRegistry, proxies.serveIndex, proxies.affinityRegistry, proxies.geminiRateLimiter, proxies.browserPool)
        }

      // Content crawling has been removed — pages are classified on-demand from
      // real traffic (serve -> /v1/classify-page). The crawler-tier browser pool
      // remains, used only by the LP analysis worker below.

      // LP analysis worker — crawler-tier offload of landing-page Playwright
      // analysis. The shard region is role-pinned to crawler, so initSharding
      // runs on every node (proxy elsewhere) but the real RunAnalysis —
      // Playwright + crawler-side R2 upload of captured bytes — is built ONLY
      // on crawler nodes (where the browser pool + R2 creds live). image_asset
      // dedup is skipped here (no DB on the crawler tier; R2 is
      // content-addressed, so the upload stays idempotent).
      val lpWorkerSettings = promovolve.browser.LPWorker.settingsFromConfig(config)
      val lpRunAnalysis: promovolve.browser.LPWorker.RunAnalysis =
        if (roles.contains("crawler")) {
          val appCfg          = config.getConfig("promovolve")
          val cdnBase         = appCfg.getString("cdn-base-url")
          val bannerScriptUrl = appCfg.getString("banner-script-url")
          val analyzer        = new promovolve.browser.LPAnalyzer(bannerScriptUrl, browserPool)
          promovolve.publisher.assets.R2ImageStorage.fromEnv()(using system) match {
            case Some(storage) =>
              context.log.info("LPWorker: analysis runner enabled on crawler node")
              LPAnalysisRunner(analyzer, storage, None, cdnBase)(using system)
            case None =>
              context.log.warn("LPWorker: R2 not configured on crawler node — LP analysis will fail until R2_* set")
              (_: promovolve.browser.LPWorker.AnalyzeLP) =>
                scala.concurrent.Future.failed(new IllegalStateException("R2 not configured on crawler node"))
          }
        } else
          // Non-crawler node: the role-pinned entity factory never runs here, so
          // this is never invoked — it only satisfies initSharding's signature.
          ((_: promovolve.browser.LPWorker.AnalyzeLP) =>
            scala.concurrent.Future.failed(new IllegalStateException("LPWorker analysis runs only on crawler nodes")))
      promovolve.browser.LPWorker.initSharding(system, sharding, lpRunAnalysis, lpWorkerSettings)
      promovolve.browser.LPWorker.initKeepAlive(system, sharding, lpWorkerSettings)

      // Entity hosting: only on entity-role nodes
      if (roles.contains("entity")) {
        context.log.info("Initializing sharded entities (this node hosts them)")
        ClusterBootstrap.Entities.initWithRefs(
          system,
          sharding,
          topics,
          campaignDirectory,
          categoryRegistry,
          serveIndex,
          affinityRegistry,
          repositories,
          geminiRateLimiter,
          browserPool,
        )

        // Initialize dashboard projection on entity nodes
        try {
          val dbConfig = DatabaseConfig.forConfig[PostgresProfile]("dashboard-projection-db", config)
          DashboardProjection.init(system, dbConfig)
          context.log.info("Dashboard projection initialized")
        } catch {
          case ex: Exception =>
            context.log.warn("Dashboard projection disabled: {}", ex.getMessage)
        }
      }

      // HTTP server: only on api-role nodes
      if (roles.contains("api")) {
        context.log.info("Starting HTTP server")
        val httpDeps = HttpBootstrap.HttpDependencies.init(
          sharding,
          campaignDirectory,
          categoryRegistry,
          serveIndex,
          repositories.creative,
          topics.budgetEvent,
          config,
          affinityRegistry = Some(affinityRegistry),
          geminiRateLimiter = geminiRateLimiter,
          browserPool = browserPool,
        )
        HttpBootstrap.HttpServer.start(config, httpDeps)
      }

      context.log.info("Node startup complete with roles: {}", roles.mkString(", "))

      Behaviors.empty
    }
  }
}
