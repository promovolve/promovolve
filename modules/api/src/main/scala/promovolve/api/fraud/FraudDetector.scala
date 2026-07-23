package promovolve.api.fraud

import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors }
import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }
import org.apache.pekko.cluster.typed.{ ClusterSingleton, ClusterSingletonSettings, SingletonActor }
import promovolve.fraud.FraudDetection

import scala.concurrent.duration.*
import scala.util.{ Failure, Success }

/**
 * Cluster-singleton runner for the Layer-2 economics detector
 * (docs/design/FRAUD_PREVENTION.md). On a timer it pulls the two
 * per-site aggregations, runs the pure [[FraudDetection]] core, and
 * upserts flags. Singleton so flags are written once, not once-per-pod.
 *
 * Read-only over live traffic + append-only to fraud_flags — it never
 * touches serving, billing, or a publisher's payout. It only surfaces
 * candidates for the (Phase 3) human review queue.
 */
object FraudDetector {

  sealed trait Command
  private case object Tick extends Command
  private final case class RunComplete(flags: Int, sites: Int) extends Command
  private final case class RunFailed(err: Throwable) extends Command

  final case class Config(
      windowDays: Int = 14,
      interval: FiniteDuration = 1.hour,
      detection: FraudDetection.Config = FraudDetection.Config()
  )

  /**
   * Init as a cluster singleton on the singleton role. No-op-safe: if
   * the dashboard DB isn't configured the caller passes None and skips.
   */
  def init(system: ActorSystem[?], repo: FraudFlagRepo, config: Config): ActorRef[Command] =
    ClusterSingleton(system).init(
      SingletonActor(apply(repo, config), "fraud-detector")
        .withSettings(ClusterSingletonSettings(system).withRole("singleton"))
    )

  /**
   * Self-contained registration for nodes that don't run HttpBootstrap
   * (the dedicated singleton tier). The singleton MANAGER must be
   * registered on EVERY node carrying the "singleton" role: the actor
   * runs only on the oldest such node, and if that node never
   * registered the manager the singleton is stranded cluster-wide —
   * younger managers wait on the oldest forever. That's exactly what
   * happened when the singleton tier came up 2026-07-23: registration
   * lived only in HttpBootstrap (api nodes), so the moment the api-less
   * singleton pod became oldest, the detector silently stopped.
   * Builds its own dashboard-DB pool, so api nodes (which already hold
   * one for routes) should keep using the HttpBootstrap path instead.
   */
  def initFromConfig(system: ActorSystem[?], config: com.typesafe.config.Config): Unit = {
    val appConfig = config.getConfig("promovolve")
    if (scala.util.Try(appConfig.getBoolean("fraud.detector.enabled")).getOrElse(false)) {
      scala.util.Try(
        slick.basic.DatabaseConfig.forConfig[slick.jdbc.PostgresProfile]("dashboard-projection-db", config)
      ).toOption match {
        case Some(dbCfg) =>
          val fdb = dbCfg.db.asInstanceOf[slick.jdbc.PostgresProfile.backend.Database]
          val repo = new FraudFlagRepo(fdb)(using system.executionContext)
          val intervalSec =
            scala.util.Try(appConfig.getInt("fraud.detector.interval-seconds")).getOrElse(3600)
          init(system, repo, Config(interval = intervalSec.seconds))
          system.log.info("FraudDetector enabled (Layer 2, every {}s)", intervalSec: Integer)
        case None =>
          system.log.warn("fraud.detector.enabled=true but no dashboard DB — detector NOT started on this node")
      }
    }
  }

  def apply(repo: FraudFlagRepo, config: Config): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        // A short initial delay lets the node finish joining before the
        // first (DB-heavy) run; then steady cadence.
        timers.startTimerAtFixedRate(Tick, 2.minutes, config.interval)
        ctx.log.info("FraudDetector singleton started (window={}d, every={})",
          config.windowDays, config.interval)
        running(ctx, repo, config, inFlight = false)
      }
    }

  private def running(
      ctx: ActorContext[Command],
      repo: FraudFlagRepo,
      config: Config,
      inFlight: Boolean
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case Tick =>
        if (inFlight) {
          // A run overran the interval — skip rather than pile up.
          ctx.log.warn("FraudDetector: previous run still in flight, skipping tick")
          Behaviors.same
        } else {
          runOnce(ctx, repo, config)
          running(ctx, repo, config, inFlight = true)
        }

      case RunComplete(flags, sites) =>
        if (flags > 0) ctx.log.info("FraudDetector: {} flag(s) across {} sites", flags, sites)
        else ctx.log.debug("FraudDetector: clean sweep ({} sites)", sites)
        running(ctx, repo, config, inFlight = false)

      case RunFailed(err) =>
        ctx.log.warn("FraudDetector run failed (will retry next tick): {}", err.toString)
        running(ctx, repo, config, inFlight = false)
    }

  private def runOnce(ctx: ActorContext[Command], repo: FraudFlagRepo, config: Config): Unit = {
    given ec: scala.concurrent.ExecutionContext = ctx.system.executionContext
    val f = for {
      events <- repo.loadEventDays(config.windowDays)
      pvs <- repo.loadPageviews(config.windowDays)
      metrics = FraudDetection.assembleMetrics(events, pvs)
      flagged = metrics.flatMap(m => FraudDetection.evaluate(m, config.detection).map(_ -> m.latestDay))
      written <- repo.upsertFlags(flagged)
    } yield (written, metrics.size)
    ctx.pipeToSelf(f) {
      case Success((flags, sites)) => RunComplete(flags, sites)
      case Failure(err)            => RunFailed(err)
    }
  }
}
