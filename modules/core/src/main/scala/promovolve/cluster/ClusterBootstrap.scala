package promovolve.cluster

import com.typesafe.config.Config
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, SupervisorStrategy }
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity }
import promovolve.*
import promovolve.advertiser.{ AdvertiserEntity, CampaignDirectory, CampaignEntity }
import promovolve.auction.{ AuctioneerEntity, CampaignDistributor, CategoryBidderEntity }
import promovolve.publisher.*
import promovolve.publisher.delivery.{ AdServer, AdaptivePacing, ServeIndexDData }
import promovolve.publisher.store.SlickPendingSelectionStore
import promovolve.taxonomy.{
  AffinityRegistryDData,
  CategoryRegistry,
  ContentProductAffinityEntity,
  IABTaxonomy,
  TaxonomyRankerEntity
}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

/**
 * Shared initialization code for all node types.
 *
 * Different node roles use different subsets:
 *   - API nodes: Topics, SingletonProxies, Repositories (no entity hosting)
 *   - Entity nodes: Topics, SingletonProxies, Repositories, Entities
 *   - Singleton nodes: Topics, Singletons, Repositories, Entities
 */
object ClusterBootstrap {

  // ========================================
  // Topics - Pub/Sub Event Channels
  // ========================================
  object Topics {
    def init(context: org.apache.pekko.actor.typed.scaladsl.ActorContext[?]): Refs = {
      context.log.info("Initializing pub/sub topics...")

      val campaignChanged = context.spawn(
        Topic[CampaignEntity.CampaignChanged]("campaign-changed"),
        "campaign-changed-topic"
      )

      val budgetEvent = context.spawn(
        Topic[BudgetEvent]("budget-events"),
        "budget-events-topic"
      )

      Refs(campaignChanged, budgetEvent)
    }

    case class Refs(
        campaignChanged: ActorRef[Topic.Command[CampaignEntity.CampaignChanged]],
        budgetEvent: ActorRef[Topic.Command[BudgetEvent]]
    )
  }

  // ========================================
  // Singletons - For nodes that HOST singletons
  // ========================================
  object Singletons {

    /** Initialize actual singletons (for singleton-role nodes). */
    def init(
        system: ActorSystem[?],
        sharding: ClusterSharding,
        topics: Topics.Refs
    )(using ExecutionContext): Refs = {
      system.log.info("Initializing cluster singletons...")

      val campaignDirectory = CampaignDirectory.singletonInit(
        system,
        sharding,
        topics.campaignChanged
      )

      val categoryRegistry = CategoryRegistry.init(system)

      // ServeIndex is a regular actor (not singleton) but spawned once per node
      // Each node has its own local DData replica
      val serveIndex = system.systemActorOf(
        Behaviors
          .supervise(ServeIndexDData())
          .onFailure(SupervisorStrategy.restart),
        "serve-index"
      )

      // AffinityRegistry is node-local (DData), each node needs its own
      val affinityRegistry = system.systemActorOf(
        Behaviors
          .supervise(AffinityRegistryDData())
          .onFailure(SupervisorStrategy.restart),
        "affinity-registry"
      )

      // Gemini rate limiter singleton (only if Gemini is configured)
      val geminiRateLimiter = initGeminiRateLimiter(system)

      // Browser session pool (node-local, NOT a cluster singleton —
      // browsers are local processes). Used for designer/LP work
      // (HttpBootstrap → LPWorker). Pages are classified on-demand from real
      // traffic, so there is no content crawler / crawl-host registration.
      val browserPool = promovolve.browser.BrowserSessionPool.init(system)

      Refs(campaignDirectory, categoryRegistry, serveIndex, affinityRegistry, geminiRateLimiter, browserPool)
    }

    case class Refs(
        campaignDirectory: ActorRef[CampaignDirectory.Command],
        categoryRegistry: ActorRef[CategoryRegistry.Command],
        serveIndex: ActorRef[ServeIndexDData.Cmd],
        affinityRegistry: ActorRef[AffinityRegistryDData.Cmd],
        geminiRateLimiter: Option[ActorRef[GeminiRateLimiter.Command]],
        browserPool: ActorRef[promovolve.browser.BrowserSessionPool.Command]
    )
  }

  // ========================================
  // SingletonProxies - For nodes that PROXY to singletons
  // ========================================
  object SingletonProxies {

    /**
     * Initialize proxies to singletons (for non-singleton nodes).
     * Note: ClusterSingleton.init() returns a proxy when the role doesn't match,
     * so we use the same init methods but on nodes without the singleton role.
     */
    def init(system: ActorSystem[?], sharding: ClusterSharding, topics: Topics.Refs)(using ExecutionContext
    ): Refs = {
      system.log.info("Initializing singleton proxies...")

      // These will be proxies since this node doesn't have singleton role
      val campaignDirectory = CampaignDirectory.singletonInit(
        system,
        sharding,
        topics.campaignChanged
      )

      val categoryRegistry = CategoryRegistry.init(system)

      // ServeIndex is node-local (DData), each node needs its own
      val serveIndex = system.systemActorOf(
        Behaviors
          .supervise(ServeIndexDData())
          .onFailure(SupervisorStrategy.restart),
        "serve-index"
      )

      // AffinityRegistry is node-local (DData), each node needs its own
      val affinityRegistry = system.systemActorOf(
        Behaviors
          .supervise(AffinityRegistryDData())
          .onFailure(SupervisorStrategy.restart),
        "affinity-registry"
      )

      // Gemini rate limiter proxy (routes to singleton node)
      val geminiRateLimiter = initGeminiRateLimiter(system)

      // Browser session pool (node-local). Used for designer/LP work. On a
      // crawler-role node, this is the pool the LPWorker drives directly.
      val browserPool = promovolve.browser.BrowserSessionPool.init(system)

      Refs(campaignDirectory, categoryRegistry, serveIndex, affinityRegistry, geminiRateLimiter, browserPool)
    }

    case class Refs(
        campaignDirectory: ActorRef[CampaignDirectory.Command],
        categoryRegistry: ActorRef[CategoryRegistry.Command],
        serveIndex: ActorRef[ServeIndexDData.Cmd],
        affinityRegistry: ActorRef[AffinityRegistryDData.Cmd],
        geminiRateLimiter: Option[ActorRef[GeminiRateLimiter.Command]],
        browserPool: ActorRef[promovolve.browser.BrowserSessionPool.Command]
    )
  }

  /** Shared init for Gemini rate limiter — creates singleton or proxy depending on node role. */
  private def initGeminiRateLimiter(system: ActorSystem[?]): Option[ActorRef[GeminiRateLimiter.Command]] = {
    val config = system.settings.config
    val hasKey = config.hasPath("promovolve.gemini.api-key") &&
      config.getString("promovolve.gemini.api-key").nonEmpty
    if (hasKey) {
      val s = GeminiRateLimiter.settings
      val ref = GeminiRateLimiter.singletonInit(system)
      system.log.info("Gemini rate limiter singleton initialized: max={} rate={}/s", s.maxTokens, s.tokensPerSecond)
      Some(ref)
    } else None
  }

  // ========================================
  // Repositories - Data Access
  // ========================================
  object Repositories {

    /**
     * Initialize with PostgreSQL-backed creative repo.
     * Uses the same Slick DB config as pekko-persistence-jdbc.
     */
    def init(config: com.typesafe.config.Config)(using ExecutionContext): Refs = {
      import slick.jdbc.PostgresProfile.api.*

      val dbConfig = config.getConfig("jdbc-durable-state-store.slick.db")
      val db = Database.forConfig("", dbConfig)

      val creativeRepo = new SlickCreativeRepo(db)
      creativeRepo.ensureSchema()

      val pendingSelectionStore = new SlickPendingSelectionStore(db)
      pendingSelectionStore.ensureSchema()
      pendingSelectionStore.ensureFlaggedSchema()
      pendingSelectionStore.ensureApprovedSchema()
      pendingSelectionStore.ensureTrustSchema()

      val statsSnapshot = new TimescaleCreativeStatsRepo(db)
      statsSnapshot.ensureSchema()

      val trafficShapeSnapshot = new SlickTrafficShapeSnapshotRepo(db)
      trafficShapeSnapshot.ensureSchema()

      val categoryDemand = new SlickCategoryDemandRepo(db)
      categoryDemand.ensureSchema()

      Refs(
        pendingSelectionStore = pendingSelectionStore,
        creative = creativeRepo,
        statsSnapshot = statsSnapshot,
        trafficShapeSnapshot = trafficShapeSnapshot,
        categoryDemand = categoryDemand
      )
    }

    case class Refs(
        pendingSelectionStore: PendingSelectionStore,
        creative: CreativeRepo,
        statsSnapshot: CreativeStatsSnapshotRepo,
        trafficShapeSnapshot: TrafficShapeSnapshotRepo,
        categoryDemand: CategoryDemandRepo
    )
  }

  // ========================================
  // Entities - Cluster Sharding Setup
  // ========================================
  object Entities {

    /** Initialize and HOST sharded entities (for entity and singleton nodes). */
    def init(
        system: ActorSystem[?],
        sharding: ClusterSharding,
        topics: Topics.Refs,
        singletons: Singletons.Refs | SingletonProxies.Refs,
        repos: Repositories.Refs
    )(using ExecutionContext): Unit = {
      // Extract refs from either Singletons.Refs or SingletonProxies.Refs
      val (campaignDirectory, categoryRegistry, serveIndex, affinityRegistry, geminiRateLimiter, browserPool) =
        singletons match {
          case s: Singletons.Refs => (s.campaignDirectory, s.categoryRegistry, s.serveIndex, s.affinityRegistry,
              s.geminiRateLimiter, s.browserPool)
          case p: SingletonProxies.Refs => (p.campaignDirectory, p.categoryRegistry, p.serveIndex, p.affinityRegistry,
              p.geminiRateLimiter, p.browserPool)
        }

      initWithRefs(system, sharding, topics, campaignDirectory, categoryRegistry, serveIndex, affinityRegistry, repos,
        geminiRateLimiter, browserPool)
    }

    /** Initialize with explicit refs (used by unified Main). */
    def initWithRefs(
        system: ActorSystem[?],
        sharding: ClusterSharding,
        topics: Topics.Refs,
        campaignDirectory: ActorRef[CampaignDirectory.Command],
        categoryRegistry: ActorRef[CategoryRegistry.Command],
        serveIndex: ActorRef[ServeIndexDData.Cmd],
        affinityRegistry: ActorRef[AffinityRegistryDData.Cmd],
        repos: Repositories.Refs,
        geminiRateLimiter: Option[ActorRef[GeminiRateLimiter.Command]] = None,
        browserPool: ActorRef[promovolve.browser.BrowserSessionPool.Command]
    )(using ec: ExecutionContext): Unit = {
      system.log.info("Initializing cluster-sharded entities...")

      // Advertiser domain
      initAdvertiserEntity(system, sharding, topics.budgetEvent)
      initCampaignEntity(system, sharding, campaignDirectory, repos.categoryDemand, topics.budgetEvent)
      initCampaignDistributor(sharding)

      // Auction domain
      initCategoryBidderEntity(sharding, repos.categoryDemand)
      initTaxonomyRankerEntity(sharding, system.settings.config)
      initContentProductAffinityEntity(sharding, system.settings.config)
      initAuctioneerEntity(sharding, topics, system.settings.config, affinityRegistry, campaignDirectory)

      // Publisher domain
      initPublisherEntity(system, sharding)
      initAdServerEntity(system, sharding, repos.pendingSelectionStore, repos.creative, serveIndex, repos.statsSnapshot,
        repos.trafficShapeSnapshot, topics.budgetEvent)
      initSiteEntity(system, sharding, categoryRegistry, campaignDirectory, geminiRateLimiter)
    }

    private def initAdvertiserEntity(
        system: ActorSystem[?],
        sharding: ClusterSharding,
        budgetTopic: ActorRef[Topic.Command[BudgetEvent]]
    ): Unit =
      sharding.init(Entity(AdvertiserEntity.TypeKey) { ctx =>
        AdvertiserEntity(AdvertiserId(ctx.entityId), sharding, budgetTopic)(using system)
      }.withEntityProps(DispatcherSelector.fromConfig("entity-dispatcher")))

    private def initCampaignEntity(
        system: ActorSystem[?],
        sharding: ClusterSharding,
        directory: ActorRef[CampaignDirectory.Command],
        categoryDemand: CategoryDemandRepo,
        budgetTopic: ActorRef[Topic.Command[BudgetEvent]]
    )(using ExecutionContext): Unit = {
      val appConfig = system.settings.config
      val simDayDuration =
        if (appConfig.hasPath("promovolve.sim.day-duration-seconds"))
          appConfig.getDouble("promovolve.sim.day-duration-seconds")
        else 86400.0

      sharding.init(Entity(CampaignEntity.TypeKey) { ctx =>
        // Entity ID format: "advertiserId|campaignId"
        val (advertiserId, campaignId) = ctx.entityId.split('|') match {
          case Array(advId, campId) => (AdvertiserId(advId), CampaignId(campId))
          case _                    => throw new IllegalArgumentException(s"Invalid CampaignEntity entityId: ${ctx.entityId}")
        }
        CampaignEntity(
          campaignId,
          advertiserId,
          directory,
          sharding,
          categoryDemand,
          budgetTopic,
          simDayDurationSeconds = simDayDuration
        )(using system, summon[ExecutionContext])
      }.withEntityProps(DispatcherSelector.fromConfig("entity-dispatcher")))
    }

    private def initCampaignDistributor(sharding: ClusterSharding): Unit =
      sharding.init(Entity(CampaignDistributor.TypeKey) { ctx =>
        // Entity ID is worker index: "0", "1", ..., "N-1"
        CampaignDistributor(ctx.entityId, sharding)
      }.withEntityProps(DispatcherSelector.fromConfig("auction-dispatcher")))

    private def initCategoryBidderEntity(sharding: ClusterSharding, categoryDemand: CategoryDemandRepo): Unit =
      sharding.init(Entity(CategoryBidderEntity.TypeKey) { ctx =>
        // Entity ID is now compound format: "categoryId|shardIndex" (e.g., "IAB1|2")
        CategoryBidderEntity(ctx.entityId, sharding, categoryDemand)
      }.withEntityProps(DispatcherSelector.fromConfig("auction-dispatcher")))

    private def initTaxonomyRankerEntity(
        sharding: ClusterSharding,
        config: Config
    ): Unit = {
      // Load settings from config if promovolve.taxonomy-ranker section exists,
      // otherwise use defaults (for tests and backwards compatibility)
      val settings =
        if (config.hasPath("promovolve.taxonomy-ranker"))
          TaxonomyRankerEntity.Settings.fromConfig(config)
        else
          TaxonomyRankerEntity.Settings()

      sharding.init(Entity(TaxonomyRankerEntity.TypeKey) { ctx =>
        TaxonomyRankerEntity(ctx.entityId, settings)
      }.withEntityProps(DispatcherSelector.fromConfig("learning-dispatcher")))
    }

    private def initContentProductAffinityEntity(
        sharding: ClusterSharding,
        config: Config
    ): Unit = {
      val settings =
        if (config.hasPath("promovolve.content-product-affinity"))
          ContentProductAffinityEntity.Settings.fromConfig(config)
        else
          ContentProductAffinityEntity.Settings()

      sharding.init(Entity(ContentProductAffinityEntity.TypeKey) { ctx =>
        ContentProductAffinityEntity(ctx.entityId, settings)
      }.withEntityProps(DispatcherSelector.fromConfig("learning-dispatcher")))
    }

    private def initAuctioneerEntity(
        sharding: ClusterSharding,
        topics: Topics.Refs,
        config: com.typesafe.config.Config,
        affinityRegistry: ActorRef[AffinityRegistryDData.Cmd],
        campaignDirectory: ActorRef[CampaignDirectory.Command]
    ): Unit = {
      val enableAffinity = config.hasPath("promovolve.auction.enable-affinity-expansion") &&
        config.getBoolean("promovolve.auction.enable-affinity-expansion")

      val settings =
        if (config.hasPath("promovolve.auction.reauction-interval"))
          AuctioneerEntity.Settings(
            reauctionInterval = config.getDuration("promovolve.auction.reauction-interval").toMillis.millis,
            enableAffinityExpansion = enableAffinity
          )
        else AuctioneerEntity.Settings(enableAffinityExpansion = enableAffinity)

      sharding.init(Entity(AuctioneerEntity.TypeKey) { ctx =>
        AuctioneerEntity(
          SiteId(ctx.entityId),
          sharding,
          topics.budgetEvent,
          topics.campaignChanged,
          settings = settings,
          affinityRegistry = Some(affinityRegistry),
          campaignDirectory = Some(campaignDirectory)
        )
      }.withEntityProps(DispatcherSelector.fromConfig("auction-dispatcher")))
    }

    private def initPublisherEntity(
        system: ActorSystem[?],
        sharding: ClusterSharding
    ): Unit =
      sharding.init(Entity(PublisherEntity.TypeKey) { ctx =>
        PublisherEntity(PublisherId(ctx.entityId), sharding)(using system)
      }.withEntityProps(DispatcherSelector.fromConfig("entity-dispatcher")))

    private def initAdServerEntity(
        system: ActorSystem[?],
        sharding: ClusterSharding,
        pendingSelectionStore: PendingSelectionStore,
        creativeRepo: CreativeRepo,
        serveIndex: ActorRef[ServeIndexDData.Cmd],
        statsSnapshotRepo: CreativeStatsSnapshotRepo,
        trafficShapeSnapshotRepo: TrafficShapeSnapshotRepo,
        budgetEventTopic: ActorRef[Topic.Command[BudgetEvent]]
    ): Unit = {
      val cfg = system.settings.config
      // Pacing is always on: budget protection must never be a toggle (a
      // disabled pacing path is a foot-gun — it lets a campaign blow its daily
      // budget). Low traffic under-paces (throttle ~0) so serving isn't blocked;
      // only artificial bursts get throttled, which is correct.
      val pacingStrategy = AdaptivePacing()

      // Per-page-winners TTL: how long after a campaign wins on a URL
      // it stays excluded from re-winning that URL. Default 15s ≈
      // page-load window (multi-slot dedup within one render). Drop
      // it down (e.g. "1s") in dev when smoke-testing with one
      // advertiser and rapid reloads.
      val pageWinnersTtl: FiniteDuration =
        if (cfg.hasPath("promovolve.adserver.page-winners-ttl"))
          cfg.getDuration("promovolve.adserver.page-winners-ttl").toMillis.millis
        else AdServer.DefaultPageWinnersTtl

      // Advertiser-timezone pacing kill switch (default OFF). When on, real-day
      // expected spend integrates the UTC traffic shape over each campaign's
      // advertiser-zone budget window instead of assuming a shared UTC day.
      // Off — or on with every zone unset — is behavior-identical to before;
      // flip via PACING_ZONE_AWARE without a code change. Deliberately a flag
      // (unlike pacing itself): this math sits on the serve-death codepath.
      val zoneAwarePacing: Boolean =
        cfg.hasPath("promovolve.pacing.zone-aware") &&
        cfg.getBoolean("promovolve.pacing.zone-aware")

      sharding.init(Entity(AdServer.TypeKey) { ctx =>
        AdServer(
          SiteId(ctx.entityId),
          pendingSelectionStore,
          creativeRepo,
          serveIndex,
          sharding,
          statsSnapshotRepo,
          trafficShapeSnapshotRepo,
          budgetEventTopic,
          pacingStrategy = pacingStrategy,
          pageWinnersTtl = pageWinnersTtl,
          zoneAwarePacing = zoneAwarePacing
        )(using system)
      }.withEntityProps(DispatcherSelector.fromConfig("serve-dispatcher")))
    }

    private def initSiteEntity(
        system: ActorSystem[?],
        sharding: ClusterSharding,
        categoryRegistry: ActorRef[CategoryRegistry.Command],
        campaignDirectory: ActorRef[CampaignDirectory.Command],
        geminiRateLimiter: Option[ActorRef[GeminiRateLimiter.Command]]
    )(using ExecutionContext): Unit = {
      val config = system.settings.config

      // Determine LLM provider from config (prefer Gemini for cost savings)
      def configuredKey(path: String): Option[String] =
        if (config.hasPath(path)) Some(config.getString(path)).filter(_.nonEmpty) else None

      val llmProvider: IABTaxonomy.Provider =
        if (configuredKey("promovolve.gemini.api-key").isDefined) {
          val apiKey = config.getString("promovolve.gemini.api-key")
          val model = if (config.hasPath("promovolve.gemini.model"))
            config.getString("promovolve.gemini.model")
          else "gemini-2.5-flash"
          system.log.info("Using Gemini for taxonomy classification (model: {})", model)
          IABTaxonomy.Provider.Gemini(apiKey, model)
        } else if (configuredKey("promovolve.openai.api-key").isDefined) {
          val apiKey = config.getString("promovolve.openai.api-key")
          val model = if (config.hasPath("promovolve.openai.model"))
            config.getString("promovolve.openai.model")
          else "gpt-4o-mini"
          system.log.info("Using OpenAI for taxonomy classification (model: {})", model)
          IABTaxonomy.Provider.OpenAI(apiKey, model)
        } else if (configuredKey("promovolve.anthropic.api-key").isDefined) {
          val apiKey = config.getString("promovolve.anthropic.api-key")
          val model = if (config.hasPath("promovolve.anthropic.model"))
            config.getString("promovolve.anthropic.model")
          else "claude-3-haiku-20240307"
          system.log.info("Using Anthropic for taxonomy classification (model: {})", model)
          IABTaxonomy.Provider.Anthropic(apiKey, model)
        } else {
          throw new IllegalStateException(
            "No LLM provider configured. Set GEMINI_API_KEY, OPENAI_API_KEY, or ANTHROPIC_API_KEY environment variable"
          )
        }

      sharding.init(Entity(SiteEntity.TypeKey) { ctx =>
        SiteEntity(
          SiteId(ctx.entityId),
          sharding,
          categoryRegistry,
          campaignDirectory,
          llmProvider,
          geminiRateLimiter
        )(using system)
      }.withEntityProps(DispatcherSelector.fromConfig("entity-dispatcher")))
    }
  }

}
