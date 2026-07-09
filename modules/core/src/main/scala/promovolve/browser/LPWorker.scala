package promovolve.browser

import com.typesafe.config.Config
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior, DispatcherSelector }
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ClusterShardingSettings
import org.apache.pekko.cluster.sharding.typed.ShardedDaemonProcessSettings
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity, EntityTypeKey, ShardedDaemonProcess }
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*
import scala.util.{ Failure, Success }

/**
 * Always-on, per-partition landing-page analysis worker for the heavy
 * Playwright LP analysis that used to run in-process on the api/entity JVMs
 * (HttpBootstrap → node-local BrowserSessionPool), competing with the
 * bid/serve hot path.
 *
 * One `LPWorker` exists per index `0 .. numWorkers-1`, keyed by that index
 * and **pinned to the `crawler` role**, so Chromium runs on a crawler pod
 * next to a warm browser pool. An api node addresses its owning worker via
 * `entityRefFor(TypeKey, workerIndexFor(url).toString) ! AnalyzeLP(...)`; the
 * ShardRegion buffers the message if no crawler node has joined yet.
 *
 * The actual analysis + CDN upload is the injected [[RunAnalysis]] seam (so
 * captured bytes are uploaded crawler-side and only URLs cross the wire, and
 * so the worker stays hermetic for tests). The worker itself only bounds
 * concurrency (`running`/`queue`/SweepStale) — which also caps what was an
 * unbounded session-mailbox queue on the api tier.
 */
object LPWorker {

  // -- Protocol (cross-node: api-node route → crawler-node worker) --

  sealed trait Command extends promovolve.CborSerializable

  /**
   * An api route asks its owning worker to analyze a landing page. `replyTo`
   * gets the finished result (section srcs already rewritten to CDN URLs by
   * the worker); `progressTo` gets the early hero screenshot URL. A duplicate
   * `AnalyzeLP` for a URL already in flight on this worker is a no-op.
   */
  final case class AnalyzeLP(
      url: String,
      strategy: String,
      replyTo: ActorRef[AnalyzeLPDone],
      progressTo: ActorRef[AnalyzeLPProgress]
  ) extends Command

  /**
   * Final result: the analysis with captured-image `src`s already rewritten
   * to CDN URLs (the worker uploaded the protected-origin bytes itself, so
   * they never cross the wire) + the hero screenshot URL. `error` is set and
   * `result` is empty on failure.
   */
  final case class AnalyzeLPDone(
      url: String,
      result: LPAnalysisResult,
      screenshotUrl: Option[String],
      error: Option[String]
  ) extends promovolve.CborSerializable

  /**
   * Early preview: the hero screenshot URL, sent before extraction finishes
   * (mirrors the old in-process `onScreenshot` streaming).
   */
  final case class AnalyzeLPProgress(url: String, screenshotUrl: String)
      extends promovolve.CborSerializable

  /** Keep-alive poke from [[initKeepAlive]]; resets the idle timer. */
  case object KeepAlive extends Command

  /**
   * Internal: the analysis Future for `url` completed (success/failure
   * folded into `done`). Delivered on the actor thread via pipeToSelf.
   */
  private final case class AnalyzeFinished(url: String, done: AnalyzeLPDone) extends Command

  /**
   * Internal: periodic stale-slot sweep (an analysis whose Future never
   * completed — belt-and-suspenders against a hung browser).
   */
  private case object SweepStale extends Command

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("lp-worker")

  // -- Settings --

  final case class Settings(
      numWorkers: Int = 4,
      // LP analysis is heavier (full stepped scroll) than a crawl and each
      // holds a browser session for its whole duration, so default 1 in-flight
      // per worker — raise with the pool size if needed.
      maxConcurrentPerWorker: Int = 1,
      staleAfter: FiniteDuration = 5.minutes,
      sweepInterval: FiniteDuration = 1.minute,
      keepAliveInterval: FiniteDuration = 30.seconds
  )

  /**
   * Build [[Settings]] from `promovolve.browser.lp-workers.*`, defaulting any
   * missing key.
   */
  def settingsFromConfig(config: Config): Settings = {
    val base = Settings()
    if (!config.hasPath("promovolve.browser.lp-workers")) base
    else {
      val w = config.getConfig("promovolve.browser.lp-workers")
      base.copy(
        numWorkers =
          if (w.hasPath("num-workers")) math.max(1, w.getInt("num-workers")) else base.numWorkers,
        maxConcurrentPerWorker =
          if (w.hasPath("max-concurrent-per-worker")) math.max(1, w.getInt("max-concurrent-per-worker"))
          else base.maxConcurrentPerWorker,
        staleAfter =
          if (w.hasPath("stale-after")) w.getDuration("stale-after").toScala else base.staleAfter,
        sweepInterval =
          if (w.hasPath("sweep-interval")) w.getDuration("sweep-interval").toScala else base.sweepInterval,
        keepAliveInterval =
          if (w.hasPath("keep-alive-interval")) w.getDuration("keep-alive-interval").toScala else base.keepAliveInterval
      )
    }
  }

  /**
   * Which worker owns a URL. Pure + deterministic; `floorMod` keeps it in
   * `[0, numWorkers)`. Computed identically on api nodes (to address) and
   * used as the entity index.
   */
  def workerIndexFor(url: String, numWorkers: Int): Int =
    math.floorMod(url.hashCode, math.max(1, numWorkers))

  // -- Behavior --

  /**
   * Seam: run the analysis (Playwright + CDN upload of captured bytes) for a
   * request, emitting the early screenshot URL via the request's `progressTo`
   * and completing with the finished [[AnalyzeLPDone]]. The default is built
   * in the wiring (Main) from `LPAnalyzer` + `ImageStorage`; tests inject a
   * stub so the worker's concurrency logic stays hermetic.
   */
  type RunAnalysis = AnalyzeLP => Future[AnalyzeLPDone]

  private final case class Slot(startedAt: Instant, replyTo: ActorRef[AnalyzeLPDone])

  def apply(
      workerIndex: Int,
      runAnalysis: RunAnalysis,
      settings: Settings
  ): Behavior[Command] = Behaviors.setup { ctx =>
    val log = LoggerFactory.getLogger(s"promovolve.LPWorker.$workerIndex")

    // running: url → (when started, who to reply to).
    val running: mutable.Map[String, Slot] = mutable.Map.empty
    val queue: mutable.Queue[AnalyzeLP] = mutable.Queue.empty
    val queuedUrls: mutable.Set[String] = mutable.Set.empty

    def canGrant: Boolean = running.size < settings.maxConcurrentPerWorker

    def grant(req: AnalyzeLP): Unit = {
      running.update(req.url, Slot(Instant.now(), req.replyTo))
      // Future body is pure (no ctx access); the Try lands on the actor thread.
      ctx.pipeToSelf(runAnalysis(req)) {
        case Success(done) => AnalyzeFinished(req.url, done)
        case Failure(e)    =>
          AnalyzeFinished(
            req.url,
            AnalyzeLPDone(req.url, LPAnalysisResult(req.url, Vector.empty), None,
              Some(e.getMessage))
          )
      }
      log.info(
        "LPWorker {} started analysis for {} ({}/{} running, {} queued)",
        workerIndex, req.url, running.size, settings.maxConcurrentPerWorker, queue.size
      )
    }

    def drainQueue(): Unit =
      while (canGrant && queue.nonEmpty) {
        val req = queue.dequeue()
        queuedUrls -= req.url
        grant(req)
      }

    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(SweepStale, settings.sweepInterval)
      log.info(
        "LPWorker {} starting: maxConcurrentPerWorker={}, staleAfter={}",
        workerIndex, settings.maxConcurrentPerWorker, settings.staleAfter
      )

      Behaviors.receiveMessage {
        case req: AnalyzeLP =>
          if (running.contains(req.url)) {
            running.updateWith(req.url)(_.map(_.copy(startedAt = Instant.now())))
            log.debug("LPWorker {} ignoring duplicate AnalyzeLP for in-flight {}", workerIndex, req.url)
          } else if (queuedUrls.contains(req.url)) {
            val pos = queue.indexWhere(_.url == req.url)
            if (pos >= 0) queue.update(pos, req) // freshest replyTo wins
            log.debug("LPWorker {} updated queued AnalyzeLP for {}", workerIndex, req.url)
          } else if (canGrant) {
            grant(req)
          } else {
            queue.enqueue(req)
            queuedUrls += req.url
            log.debug("LPWorker {} queued {} ({} waiting)", workerIndex, req.url, queue.size)
          }
          Behaviors.same

        case AnalyzeFinished(url, done) =>
          running.remove(url) match {
            case Some(slot) =>
              slot.replyTo ! done
              drainQueue()
            case None =>
              // Slot was stale-evicted before the Future completed; the reply
              // has nowhere to go (the caller already timed out). Drop it.
              log.warn("LPWorker {} analysis for {} completed after stale eviction — reply dropped", workerIndex, url)
          }
          Behaviors.same

        case SweepStale =>
          val cutoff = Instant.now().minusSeconds(settings.staleAfter.toSeconds)
          val stale = running.collect { case (u, s) if s.startedAt.isBefore(cutoff) => u }.toVector
          if (stale.nonEmpty) {
            log.warn("LPWorker {} auto-releasing {} stale slot(s): {}", workerIndex, stale.size, stale.mkString(", "))
            stale.foreach(running.remove)
            drainQueue()
          }
          Behaviors.same

        case KeepAlive =>
          Behaviors.same
      }
    }
  }

  // -- Sharding + keep-alive init --

  /**
   * Initialise the LPWorker shard region. MUST be called on `crawler`-role
   * nodes (where the local browser pool + R2 creds live) — pinned to that
   * role, overriding the global `sharding.role = "entity"`. `remember-entities`
   * off (pure compute; keep-alive is the liveness mechanism).
   */
  def initSharding(
      system: ActorSystem[?],
      sharding: ClusterSharding,
      runAnalysis: RunAnalysis,
      settings: Settings
  ): Unit = {
    sharding.init(
      Entity(TypeKey) { ctx =>
        LPWorker(ctx.entityId.toInt, runAnalysis, settings)
      }.withSettings(
        ClusterShardingSettings(system)
          .withRole("crawler")
          .withRememberEntities(false)
      ).withEntityProps(DispatcherSelector.fromConfig("crawler-dispatcher"))
    )
    LoggerFactory.getLogger("promovolve.LPWorker")
      .info("LPWorker shard region initialised (role=crawler, numWorkers={})", settings.numWorkers)
  }

  /**
   * Keep every worker index warm so it never passivates between analyses.
   * A ShardedDaemonProcess of N tickers pinned to the `crawler` role.
   */
  def initKeepAlive(
      system: ActorSystem[?],
      sharding: ClusterSharding,
      settings: Settings
  ): Unit = {
    ShardedDaemonProcess(system).init[KeepAliveProtocol](
      name = "LPWorkerKeepAlive",
      numberOfInstances = settings.numWorkers,
      behaviorFactory = idx => keepAliveBehavior(sharding, idx, settings.keepAliveInterval),
      settings = ShardedDaemonProcessSettings(system).withRole("crawler"),
      stopMessage = None
    )
    LoggerFactory.getLogger("promovolve.LPWorker")
      .info("LPWorker keep-alive daemon initialised (numWorkers={}, every {})", settings.numWorkers,
        settings.keepAliveInterval)
  }

  private sealed trait KeepAliveProtocol
  private case object KeepAliveTick extends KeepAliveProtocol

  private def keepAliveBehavior(
      sharding: ClusterSharding,
      idx: Int,
      interval: FiniteDuration
  ): Behavior[KeepAliveProtocol] = Behaviors.setup { _ =>
    val worker = sharding.entityRefFor(TypeKey, idx.toString)
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(KeepAliveTick, interval)
      worker ! KeepAlive // immediate poke so the shard allocates promptly
      Behaviors.receiveMessage { case KeepAliveTick =>
        worker ! KeepAlive
        Behaviors.same
      }
    }
  }
}
