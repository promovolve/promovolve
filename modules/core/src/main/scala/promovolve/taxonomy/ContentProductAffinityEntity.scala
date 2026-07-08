package promovolve.taxonomy

import com.typesafe.config.Config
import org.apache.commons.math3.distribution.BetaDistribution
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.state.scaladsl.{DurableStateBehavior, Effect}
import promovolve.CborSerializable

import java.time.{Clock, Instant}
import scala.concurrent.duration.*

/** ContentProductAffinityEntity - Learned affinity between content and ad product categories.
  *
  * == Purpose ==
  * Tracks how well ads for a given ad product category perform on pages classified
  * under a given content category. This goes beyond the static IAB mapping by learning
  * from actual click data which (content, adProduct) pairs produce engagement.
  *
  * == Sharding ==
  * Entity ID format: `contentCategoryId|adProductCategoryId` (global, not per-site).
  * Global sharding allows faster learning from sparse data across all sites.
  *
  * == Model ==
  * Identical to TaxonomyRankerEntity: Thompson Sampling with Beta posterior.
  * {{{
  *   Beta(clicks + α, (wins - clicks) + β)
  * }}}
  *
  * == Buffering ==
  * Updates are buffered in memory and flushed periodically (default 5s).
  * On crash, up to `flushEvery` seconds of feedback data may be lost.
  */
object ContentProductAffinityEntity {

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("content-product-affinity")

  def apply(
      entityId: String,
      cfg: Settings = Settings(),
      clock: Clock  = Clock.systemUTC()
  ): Behavior[Command] =
    Behaviors
      .setup[Command] { ctx =>
        val (contentCategoryId, adProductCategoryId) = entityId.split('|') match {
          case Array(content, adProduct) => (content, adProduct)
          case Array(content) => (content, "unknown")
          case _ => (entityId, "unknown")
        }

        Behaviors.withTimers { timers =>
          val flushTimerKey = "flush-key"
          var bufferedState: Option[State] = None

          timers.startTimerAtFixedRate(flushTimerKey, FlushTick, cfg.flushEvery)

          def effectiveState(persistedState: State): State =
            bufferedState.getOrElse(persistedState)

          def nowMs: Long = clock.millis()

          def decayStats(st: Stats, tNow: Long, updateTimestamp: Boolean = true): Stats = {
            val dt = (tNow - st.updatedMs).toDouble
            if (dt <= 0.0) st
            else {
              val hl = cfg.halfLife.toMillis.toDouble
              val k  = math.pow(0.5, dt / hl)
              st.copy(
                wins      = st.wins * k,
                clicks    = st.clicks * k,
                revenue   = st.revenue * k,
                updatedMs = if (updateTimestamp) tNow else st.updatedMs
              )
            }
          }

          def sampleCtr(clicks: Double, wins: Double): Double = {
            val alpha = math.max(0.01, clicks + cfg.priorAlpha)
            val beta  = math.max(0.01, math.max(0.0, wins - clicks) + cfg.priorBeta)
            new BetaDistribution(alpha, beta).sample()
          }

          def meanCtr(clicks: Double, wins: Double): Double = {
            val alpha = clicks + cfg.priorAlpha
            val beta  = math.max(0.0, wins - clicks) + cfg.priorBeta
            alpha / (alpha + beta)
          }

          def sampleScore(st: Stats): Double = sampleCtr(st.clicks, st.wins)

          def commandHandler(state: State, command: Command): Effect[State] = command match {

            case Quote(replyTo) =>
              val current       = effectiveState(state)
              val tNow          = nowMs
              val decayed       = decayStats(current.stats, tNow, updateTimestamp = false)
              val sampledWeight = sampleScore(decayed)
              replyTo ! Quoted(contentCategoryId, adProductCategoryId, sampledWeight, Instant.ofEpochMilli(tNow))
              Effect.none

            case FlushTick =>
              bufferedState match {
                case Some(buffered) =>
                  val tNow    = nowMs
                  val decayed = decayStats(buffered.stats, tNow)
                  bufferedState = None
                  Effect.persist(buffered.copy(stats = decayed))
                case None =>
                  Effect.none
              }

            case RecordImpression(revenue) =>
              val current = effectiveState(state)
              val tNow    = nowMs
              val decayed = decayStats(current.stats, tNow)
              val updatedStats = decayed.copy(
                wins      = decayed.wins + 1.0,
                revenue   = decayed.revenue + revenue,
                updatedMs = tNow
              )
              ctx.log.debug(
                "Affinity impression: content={} adProduct={} wins={}",
                contentCategoryId, adProductCategoryId, f"${updatedStats.wins}%.1f"
              )
              bufferedState = Some(current.copy(stats = updatedStats))
              Effect.none

            case RecordClick() =>
              val current = effectiveState(state)
              val tNow    = nowMs
              val decayed = decayStats(current.stats, tNow)
              val updatedStats = decayed.copy(
                clicks    = decayed.clicks + 1.0,
                updatedMs = tNow
              )
              val ctr = if (updatedStats.wins > 0) updatedStats.clicks / updatedStats.wins * 100 else 0.0
              ctx.log.debug(
                "Affinity click: content={} adProduct={} clicks={} CTR={}%",
                contentCategoryId, adProductCategoryId, f"${updatedStats.clicks}%.1f", f"$ctr%.2f"
              )
              bufferedState = Some(current.copy(stats = updatedStats))
              Effect.none

            case GetStats(replyTo) =>
              val current = effectiveState(state)
              val tNow    = nowMs
              val decayed = decayStats(current.stats, tNow, updateTimestamp = false)
              replyTo ! StatsSnapshot(
                contentCategoryId  = contentCategoryId,
                adProductCategoryId = adProductCategoryId,
                wins       = decayed.wins,
                clicks     = decayed.clicks,
                revenue    = decayed.revenue,
                ctr        = if (decayed.wins > 0) decayed.clicks / decayed.wins else 0.0,
                meanCtr    = meanCtr(decayed.clicks, decayed.wins),
                timestamp  = Instant.ofEpochMilli(tNow)
              )
              Effect.none
          }

          ctx.log.debug(
            "ContentProductAffinity[{}|{}] starting (priors: α={}, β={})",
            contentCategoryId,
            adProductCategoryId,
            cfg.priorAlpha,
            cfg.priorBeta
          )

          DurableStateBehavior[Command, State](
            persistenceId  = PersistenceId.ofUniqueId(s"content-product-affinity-$entityId"),
            emptyState     = State.empty(contentCategoryId, adProductCategoryId),
            commandHandler = commandHandler
          ).receiveSignal {
            case (state, org.apache.pekko.persistence.typed.state.RecoveryCompleted) =>
              val st = state.stats
              val ctr = if (st.wins > 0) 100.0 * st.clicks / st.wins else 0.0
              ctx.log.info(
                "ContentProductAffinity[{}|{}] recovered: wins={}, clicks={}, CTR={}%",
                contentCategoryId,
                adProductCategoryId,
                f"${st.wins}%.1f",
                f"${st.clicks}%.1f",
                f"$ctr%.2f"
              )
          }
        }
      }
      .narrow

  // ---------------- Public API ----------------
  sealed trait Command extends promovolve.CborSerializable

  final case class Quote(replyTo: ActorRef[Quoted]) extends Command
  final case class RecordImpression(revenue: Double) extends Command
  final case class RecordClick() extends Command
  final case class GetStats(replyTo: ActorRef[StatsSnapshot]) extends Command

  final case class Quoted(
      contentCategory: String,
      adProductCategory: String,
      weight: Double,
      timestamp: Instant
  ) extends promovolve.CborSerializable

  final case class StatsSnapshot(
      contentCategoryId: String,
      adProductCategoryId: String,
      wins: Double,
      clicks: Double,
      revenue: Double,
      ctr: Double,
      meanCtr: Double,
      timestamp: Instant
  ) extends promovolve.CborSerializable

  final case class Settings(
      halfLife: FiniteDuration   = 7.days,
      priorAlpha: Double         = 1.0,
      priorBeta: Double          = 1.0,
      flushEvery: FiniteDuration = 5.seconds
  )

  final case class Stats(
      wins: Double      = 0.0,
      clicks: Double    = 0.0,
      revenue: Double   = 0.0,
      updatedMs: Long   = System.currentTimeMillis
  ) extends CborSerializable

  final case class State(
      contentCategoryId: String,
      adProductCategoryId: String,
      stats: Stats
  ) extends CborSerializable

  object Settings {
    def fromConfig(config: Config): Settings = {
      val c = config.getConfig("promovolve.content-product-affinity")
      Settings(
        halfLife   = c.getDuration("half-life").toMillis.millis,
        priorAlpha = c.getDouble("prior-alpha"),
        priorBeta  = c.getDouble("prior-beta"),
        flushEvery = c.getDuration("flush-every").toMillis.millis
      )
    }
  }

  object State {
    def empty(contentCategoryId: String, adProductCategoryId: String): State = State(
      contentCategoryId   = contentCategoryId,
      adProductCategoryId = adProductCategoryId,
      stats               = Stats()
    )
  }

  private case object FlushTick extends Command
}
