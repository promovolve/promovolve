package promovolve.browser

import com.microsoft.playwright.Browser
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior, DispatcherSelector, SupervisorStrategy }
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.{ Cluster, SelfUp, Subscribe }
import org.slf4j.LoggerFactory

import scala.concurrent.{ Future, Promise }
import scala.jdk.CollectionConverters.*

/**
 * Node-local pool of [[BrowserSession]] actors. One per JVM.
 *
 * Replaces the previous per-worker `BrowserContextFactory`: instead
 * of each `PlaywrightWorker` owning its own Playwright + Chromium
 * (5 per crawl × N concurrent crawls = explosive memory),
 * `BrowserSessionPool` keeps a fixed `size` of sessions and routes
 * page-fetch requests across them round-robin. Total Chromium
 * processes per node = `size`.
 *
 * Sized via `promovolve.browser.browser-pool.size`. The companion
 * `CrawlScheduler.maxConcurrent` (queue depth across crawls) and
 * this pool size (total browser count) are independent knobs.
 *
 * Proxy assignment: at boot, each session is assigned one entry
 * from the configured proxy list (modulo size). Sessions never
 * change proxy at runtime — proxy rotation per page is a
 * follow-up.
 */
object BrowserSessionPool {

  // -- Protocol --

  sealed trait Command

  /**
   * Same-shape forward to a chosen session. Wrapping rather than
   * passing `BrowserSession.Render` directly lets the pool's
   * protocol evolve (priorities, queueing, metrics) without
   * touching session internals.
   */
  final case class Render(
      url: String,
      targetElements: Array[String],
      depth: Int,
      opts: BrowserSession.CrawlOpts,
      replyTo: ActorRef[BrowserSession.PageScrapedResult]
  ) extends Command with promovolve.CborSerializable

  /**
   * Run `work(browser)` on one of the pool's pinned session threads
   * and deliver the result via `promise`. Used for non-crawl
   * Playwright work (LP analysis, banner screenshots). Prefer the
   * typed helper [[submit]] which hides the Promise/cast plumbing.
   */
  final case class Submit(
      work: Browser => Any,
      promise: Promise[Any]
  ) extends Command

  case object Stop extends Command

  /**
   * Internal: this node has reached cluster member status Up. Delivered via a
   * message adapter from the cluster SelfUp subscription; triggers the
   * deferred warmup (see warm-after-join in routing).
   */
  private case object ClusterUp extends Command

  // -- Settings --

  final case class Settings(
      size: Int = 4,
      // Sessions reserved exclusively for designer `Submit` work (LP
      // analysis / banner screenshots) so the interactive designer never
      // queues behind a background crawl flooding the pool with `Render`s.
      // Clamped so the crawler always keeps >= 1 session; with size == 1
      // reservation is impossible and both share the single session.
      // Override via promovolve.browser.browser-pool.designer-reserved-sessions.
      designerReserved: Int = 1,
      // Eagerly launch Chromium on every session at pool startup instead of
      // lazily on first use. Trades idle memory (size × Chromium held from
      // boot) for a warm first crawl/LP job. Default off — keep lazy on the
      // entity tier (lean nodes); enable per-tier (e.g. the crawler
      // Deployment) via CRAWLER_BROWSER_WARM_ON_START=true.
      warmOnStart: Boolean = false,
      sessionSettings: BrowserSession.Settings = BrowserSession.Settings()
  )

  /**
   * Number of sessions actually reserved for the designer, clamped to
   * [0, size-1] so the crawler always keeps at least one. Pure for testing.
   */
  private[browser] def reservedCount(size: Int, designerReserved: Int): Int =
    math.max(0, math.min(designerReserved, size - 1))

  /**
   * Read pool size + per-session settings from
   * `promovolve.browser.browser-pool.*`. Defaults apply when keys
   * are missing (tests, embedded use).
   */
  def settingsFromConfig(config: Config): Settings = {
    val base = Settings()
    if (!config.hasPath("promovolve.browser.browser-pool")) base
    else {
      val bp = config.getConfig("promovolve.browser.browser-pool")
      base.copy(
        size = if (bp.hasPath("size")) bp.getInt("size") else base.size,
        designerReserved =
          if (bp.hasPath("designer-reserved-sessions")) bp.getInt("designer-reserved-sessions")
          else base.designerReserved,
        warmOnStart =
          if (bp.hasPath("warm-on-start")) bp.getBoolean("warm-on-start")
          else base.warmOnStart,
        sessionSettings = base.sessionSettings.copy(
          contextRotationEvery =
            if (bp.hasPath("context-rotation-every")) bp.getInt("context-rotation-every")
            else base.sessionSettings.contextRotationEvery
        )
      )
    }
  }

  /**
   * Read the legacy top-level `crawler.{useProxy,proxyProviders}`
   * block. Returns empty when disabled or absent.
   */
  def proxiesFromConfig(config: Config): Seq[ProxyProviderConf] = {
    if (!config.hasPath("crawler")) return Seq.empty
    val c = config.getConfig("crawler")
    val enabled = c.hasPath("useProxy") && c.getBoolean("useProxy")
    if (!enabled || !c.hasPath("proxyProviders")) Seq.empty
    else
      c.getConfigList("proxyProviders").asScala
        .map(p =>
          ProxyProviderConf(
            p.getString("provider"),
            p.getString("server"),
            p.getString("username"),
            p.getString("password")
          ))
        .toSeq
  }

  // -- Behavior --

  def apply(
      proxies: Seq[ProxyProviderConf],
      settings: Settings = Settings()
  ): Behavior[Command] = Behaviors.setup { ctx =>
    val log = LoggerFactory.getLogger("promovolve.BrowserSessionPool")
    log.info(
      "BrowserSessionPool starting: size={}, proxies={}",
      settings.size,
      if (proxies.isEmpty) "none" else proxies.map(_.provider).mkString(",")
    )

    val sessions: Vector[ActorRef[BrowserSession.Command]] = (0 until settings.size).map { i =>
      val proxy = if (proxies.isEmpty) None else Some(proxies(i % proxies.size))
      val behavior = Behaviors
        .supervise(BrowserSession(i, proxy, settings.sessionSettings))
        .onFailure[Exception](SupervisorStrategy.restart)
      ctx.spawn(
        behavior,
        s"browser-session-$i",
        DispatcherSelector.fromConfig("browser-session-dispatcher")
      )
    }.toVector

    // Warmup (eager Chromium launch) is deferred to AFTER this node joins the
    // cluster — see routing's SelfUp handling. Launching 4 Chromium here at
    // pool init (which runs during ClusterBootstrap, concurrent with the
    // cluster join) spiked CPU/mem and caused HttpContactPointBootstrap
    // "Overdue of probing-failure-timeout" → delayed crawler registration.
    // Session actors are spawned now (cheap — no Chromium until first use).

    // Reserve the last `r` sessions for designer Submit work; the crawler
    // (Render) round-robins over the rest. crawlerSessions is always
    // non-empty (r <= size-1); designerSessions is empty only when size == 1,
    // in which case designer work shares the single crawler session.
    val r = reservedCount(settings.size, settings.designerReserved)
    val (crawlerSessions, designerSessions) = sessions.splitAt(settings.size - r)
    log.info(
      "BrowserSessionPool routing: {} crawler session(s), {} designer-reserved",
      crawlerSessions.size,
      designerSessions.size
    )

    routing(crawlerSessions, designerSessions, settings.warmOnStart)
  }

  /**
   * Crawler `Render`s round-robin over `crawlerSessions` only (the
   * non-reserved sessions). Designer `Submit`s round-robin over ALL
   * sessions — they get full parallelism (LP analysis and banner render
   * run on different sessions instead of serializing), and because the
   * crawler never touches the reserved `designerSessions`, the designer
   * always has at least one lane the crawler can't occupy. "Reserved for
   * the designer" = "kept free of crawler", not "the only lane the
   * designer may use". Split out from `apply` so it's testable with probe
   * sessions without launching Playwright.
   */
  private[browser] def routing(
      crawlerSessions: Vector[ActorRef[BrowserSession.Command]],
      designerSessions: Vector[ActorRef[BrowserSession.Command]],
      warmOnStart: Boolean = false
  ): Behavior[Command] = Behaviors.setup { ctx =>
    val log = LoggerFactory.getLogger("promovolve.BrowserSessionPool")
    val submitSessions = crawlerSessions ++ designerSessions
    var crawlerNext = 0
    var designerNext = 0
    var warmed = false

    // Warm-after-join: when enabled, subscribe to the cluster SelfUp event and
    // warm only once THIS node is Up — so the Chromium launches don't compete
    // with cluster-bootstrap probing. SelfUp fires once (immediately if the
    // node is already Up). No-op in tests (warmOnStart defaults false → no
    // Cluster access needed, so routing stays testable with probe sessions).
    if (warmOnStart) {
      val selfUpAdapter = ctx.messageAdapter[SelfUp](_ => ClusterUp)
      Cluster(ctx.system).subscriptions ! Subscribe(selfUpAdapter, classOf[SelfUp])
    }

    Behaviors.receiveMessage[Command] {
      case ClusterUp =>
        if (!warmed) {
          warmed = true
          log.info("BrowserSessionPool node joined cluster — warming {} session(s)", submitSessions.size)
          submitSessions.foreach(_ ! BrowserSession.Warmup)
        }
        Behaviors.same

      case r: Render =>
        val target = crawlerSessions(crawlerNext)
        crawlerNext = (crawlerNext + 1) % crawlerSessions.size
        target ! BrowserSession.Render(
          r.url, r.targetElements, r.depth, r.opts, r.replyTo
        )
        Behaviors.same

      case s: Submit =>
        val target = submitSessions(designerNext)
        designerNext = (designerNext + 1) % submitSessions.size
        target ! BrowserSession.Submit(s.work, s.promise)
        Behaviors.same

      case Stop =>
        val all = (crawlerSessions ++ designerSessions).distinct
        log.info("BrowserSessionPool stopping, sending Stop to {} sessions", all.size)
        all.foreach(_ ! BrowserSession.Stop)
        Behaviors.stopped
    }
  }

  /**
   * Run `work` on a pool session's pinned thread and return the
   * result as `Future[T]`. The work executes synchronously inside
   * the actor's receive (already on the pinned thread, so all
   * Playwright API calls are safe). The future fails with whatever
   * `work` throws.
   */
  def submit[T](pool: ActorRef[Command])(work: Browser => T): Future[T] = {
    val promise = Promise[Any]()
    pool ! Submit(br => work(br): Any, promise)
    promise.future.asInstanceOf[Future[T]]
  }

  // Cluster crawl routing (Receptionist CrawlerServiceKey + group router +
  // registerForCrawl) was removed: SiteEntity now dispatches crawls to the
  // CrawlWorker ShardedDaemonProcess, which drives its node-local pool
  // directly. This pool is still spawned per node for designer/LP work and,
  // on crawler nodes, is the pool the CrawlWorker drives.

  // -- Init helper --

  /**
   * Spawn a node-local pool using settings + proxies derived from
   * the system config. Call once per node at bootstrap.
   */
  def init(system: ActorSystem[?]): ActorRef[Command] = {
    val cfg = system.settings.config
    val settings = settingsFromConfig(cfg)
    val proxies = proxiesFromConfig(cfg)
    system.systemActorOf(
      Behaviors
        .supervise(apply(proxies, settings))
        .onFailure[Exception](SupervisorStrategy.restart),
      "browser-session-pool"
    )
  }
}
