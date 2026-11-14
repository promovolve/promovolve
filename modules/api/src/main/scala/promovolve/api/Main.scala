package promovolve.api

import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorSystem, Behavior }
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import promovolve.api.projection.DashboardProjection
import promovolve.cluster.ClusterBootstrap
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

/**
 * Unified Main for all node types.
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
    def apply(
        config: Config,
        startBrowser: (ActorSystem[?], ClusterSharding) => Unit = BrowserRuntime.start
    ): Behavior[Nothing] = Behaviors.setup[Nothing] { context =>
      given system: ActorSystem[Nothing] = context.system
      given ec: ExecutionContext = context.executionContext

      val roles = config.getStringList("pekko.cluster.roles").asScala.toSet
      context.log.info("Starting Promovolve node with roles: {}", roles.mkString(", "))

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

      if (roles.contains("browser")) {
        require(roles == Set("browser"), "Browser nodes must not host serving or entity roles")
        startBrowser(system, sharding)
        Behaviors.empty
      } else {
        // Initialize topics (all nodes need these for pub/sub)
        val topics = ClusterBootstrap.Topics.init(context)

        // Initialize repositories (PostgreSQL-backed for persistence)
        val repositories = ClusterBootstrap.Repositories.init(config)

        // Singletons: actual instances on singleton nodes, proxies on others
        val (campaignDirectory, categoryRegistry, serveIndex, affinityRegistry, geminiRateLimiter) =
          if (roles.contains("singleton")) {
            context.log.info("Initializing cluster singletons (this node hosts them)")
            val singletons = ClusterBootstrap.Singletons.init(
              system,
              sharding,
              topics
            )
            (singletons.campaignDirectory, singletons.categoryRegistry, singletons.serveIndex,
              singletons.affinityRegistry, singletons.geminiRateLimiter)
          } else {
            context.log.info("Initializing singleton proxies")
            val proxies = ClusterBootstrap.SingletonProxies.init(system, sharding, topics)
            (proxies.campaignDirectory, proxies.categoryRegistry, proxies.serveIndex, proxies.affinityRegistry,
              proxies.geminiRateLimiter)
          }

        // Fraud Layer-2 detector: singleton-role nodes WITHOUT the api role
        // never run HttpBootstrap (where api nodes register this manager),
        // so they must register it here — the manager has to exist on every
        // singleton-role node or the singleton strands when such a node is
        // oldest (bit the dedicated singleton tier 2026-07-23).
        if (roles.contains("singleton") && !roles.contains("api"))
          promovolve.api.fraud.FraudDetector.initFromConfig(system, config)

        val workersEnabled = config.getBoolean("promovolve.browser.lp-workers.enabled")
        // Legacy/local manifests remain usable while CI updates images before manual cutover.
        val browserPool = if (roles.contains("api") && !workersEnabled)
          promovolve.browser.BrowserSessionPool.init(system)
        else system.ignoreRef[promovolve.browser.BrowserSessionPool.Command]
        promovolve.browser.LPWorker.initSharding(system, sharding,
          _ => scala.concurrent.Future.failed(new IllegalStateException("No browser worker on API node")),
          promovolve.browser.LPWorker.settingsFromConfig(config))
        promovolve.browser.BannerWorker.init(system, sharding,
          _ => scala.concurrent.Future.failed(new IllegalStateException("No browser worker on API node")))

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
            geminiRateLimiter
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
            repositories.pendingSelectionStore,
            topics.budgetEvent,
            config,
            affinityRegistry = Some(affinityRegistry),
            geminiRateLimiter = geminiRateLimiter,
            browserPool = browserPool
          )
          HttpBootstrap.HttpServer.start(config, httpDeps)
        }

        context.log.info("Node startup complete with roles: {}", roles.mkString(", "))

        Behaviors.empty
      }
    }
  }
}
