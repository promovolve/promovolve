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

/** TaxonomyRankerEntity - Thompson Sampling for CTR (click-through rate) estimation.
  *
  * == Sharding ==
  * Entity ID format is `category|siteId`. Each site gets their own category ranker,
  * allowing per-site category CTR tracking. This prevents cross-site interference.
  *
  * == CTR Model ==
  * This entity models P(click | impression) using Thompson Sampling with a Beta posterior:
  * {{{
  *   Beta(clicks + α, (wins - clicks) + β)
  * }}}
  * where:
  *  - clicks = number of clicks received
  *  - wins   = number of impressions served (i.e., auction wins)
  *  - α, β   = prior parameters (default: α=1, β=1 for uniform prior)
  *
  * The `Quote` command returns a SAMPLE from this distribution, representing an
  * estimated CTR. This sample inherently provides exploration (high variance when
  * data is sparse) and exploitation (converges to true CTR with more data).
  *
  * == Usage ==
  * {{{
  * // When ad is served (impression):
  * ranker ! RecordImpression(revenue = 2.50)
  *
  * // When user clicks the served ad:
  * ranker ! RecordClick()
  * }}}
  *
  * == Semantic Model ==
  * {{{
  * Stats fields:
  *   wins:    Impressions served (used in Thompson Sampling)
  *   clicks:  Clicks on served impressions (used in Thompson Sampling)
  *   revenue: Total revenue generated (for monitoring, NOT used in sampling)
  *
  * Thompson Sampling uses ONLY wins and clicks. Revenue/CPM weighting happens in AdServer.
  * }}}
  *
  * == Buffering Tradeoff ==
  * Updates are buffered in memory and flushed periodically (default 5s).
  * '''On crash, up to `flushEvery` seconds of feedback data may be lost.'''
  * This is acceptable for Thompson Sampling which is robust to noise.
  *
  * == Decay Semantics ==
  * Stats decay exponentially with configurable half-life (default 7 days). Decay is
  * '''view-time only''' - computed on read (Quote/GetStats) but only persisted on flush.
  *
  * Uses DurableStateBehavior for persistence. Stats survive restarts and are
  * recovered automatically from PostgreSQL.
  *
  * @see [[TAXONOMY_RANKING.md]] for detailed architecture documentation
  */
object TaxonomyRankerEntity {

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("taxonomy-category")

  /** Create a TaxonomyRankerEntity with persistence enabled.
    * State is automatically persisted to PostgreSQL via DurableStateBehavior.
    *
    * @param entityId Format: "category|siteId" (e.g., "tech|fashion-women.com")
    *
    * Performance optimizations:
    * - Batched persistence: Updates are buffered in memory and flushed to the
    *   database periodically (every flushEvery interval)
    */
  def apply(
      entityId: String,
      cfg: Settings = Settings(),
      clock: Clock  = Clock.systemUTC()
  ): Behavior[Command] =
    Behaviors
      .setup[Command] { ctx =>
        // Parse entityId: "category|siteId"
        val (categoryId, siteId) = entityId.split('|') match {
          case Array(cat, site) => (cat, site)
          case Array(cat) => (cat, "default") // Fallback for tests
          case _ => (entityId, "default")
        }

        Behaviors.withTimers { timers =>
          val flushTimerKey = "flush-key"

          // Buffered state for batched persistence - holds changes since last flush
          // The persisted state is the "base" and bufferedState holds recent changes
          var bufferedState: Option[State] = None

          // Start flush timer immediately
          timers.startTimerAtFixedRate(flushTimerKey, FlushTick, cfg.flushEvery)

          /** Get the current effective state (buffered if available, otherwise persisted) */
          def effectiveState(persistedState: State): State =
            bufferedState.getOrElse(persistedState)

          def nowMs: Long = clock.millis()

          /** Decay stats using exponential half-life.
            * @param updateTimestamp If true, update `updatedMs` to `tNow`. Set to false
            *                        for read-only paths (Quote/GetStats) where we don't persist.
            */
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

          /** Thompson Sampling for CTR: sample from Beta(clicks + α, (wins - clicks) + β).
            *
            * Models P(click | impression) where:
            *  - clicks = number of successes (user clicked)
            *  - wins   = total trials (impressions served)
            *
            * Returns a sample representing estimated CTR. The sample's variance
            * naturally provides exploration when data is sparse.
            */
          def sampleCtr(clicks: Double, wins: Double): Double = {
            val alpha = math.max(0.01, clicks + cfg.priorAlpha)
            val beta  = math.max(0.01, math.max(0.0, wins - clicks) + cfg.priorBeta)
            new BetaDistribution(alpha, beta).sample()
          }

          /** Deterministic CTR using posterior mean: (clicks + α) / (wins + α + β).
            * Use for monitoring/debugging. NOT used in selection.
            */
          def meanCtr(clicks: Double, wins: Double): Double = {
            val alpha = clicks + cfg.priorAlpha
            val beta  = math.max(0.0, wins - clicks) + cfg.priorBeta
            alpha / (alpha + beta)
          }

          /** Sample a CTR score for this category using Thompson Sampling.
            *
            * Returns ONLY the CTR sample. No additional noise or weighting.
            * CPM/revenue weighting happens in AdServer, not here.
            */
          def sampleScore(st: Stats): Double =
            sampleCtr(st.clicks, st.wins)

          // Command handler for DurableStateBehavior
          // Note: `state` is the persisted state, use `effectiveState(state)` for current view
          def commandHandler(state: State, command: Command): Effect[State] = command match {

            case Quote(replyTo) =>
              val current       = effectiveState(state)
              val tNow          = nowMs
              val decayed       = decayStats(current.stats, tNow, updateTimestamp = false)
              val sampledWeight = sampleScore(decayed)
              replyTo ! Quoted(categoryId, sampledWeight, Instant.ofEpochMilli(tNow))
              Effect.none

            case FlushTick =>
              bufferedState match {
                case Some(buffered) =>
                  val tNow    = nowMs
                  val decayed = decayStats(buffered.stats, tNow)
                  bufferedState = None // Clear buffer after flush
                  Effect.persist(buffered.copy(stats = decayed))
                case None =>
                  Effect.none
              }

            case RecordImpression(revenue) =>
              // New preferred API: record a served impression
              val current = effectiveState(state)
              val tNow    = nowMs
              val decayed = decayStats(current.stats, tNow)

              val updatedStats = decayed.copy(
                wins      = decayed.wins + 1.0,
                revenue   = decayed.revenue + revenue,
                updatedMs = tNow
              )

              ctx.log.info(
                "📈 RecordImpression: category={} site={} wins={} revenue={}",
                categoryId, siteId, f"${updatedStats.wins}%.1f", f"${updatedStats.revenue}%.4f"
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
              ctx.log.info(
                "🖱️ RecordClick: category={} site={} clicks={} CTR={}%",
                categoryId, siteId, f"${updatedStats.clicks}%.1f", f"$ctr%.2f"
              )

              bufferedState = Some(current.copy(stats = updatedStats))
              Effect.none

            case GetStats(replyTo) =>
              val current = effectiveState(state)
              val tNow    = nowMs
              val decayed = decayStats(current.stats, tNow, updateTimestamp = false)
              replyTo ! StatsSnapshot(
                categoryId = categoryId,
                siteId     = siteId,
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
            "TaxonomyRanker[{}|{}] starting with CTR Thompson Sampling (priors: α={}, β={})",
            categoryId,
            siteId,
            cfg.priorAlpha,
            cfg.priorBeta
          )

          DurableStateBehavior[Command, State](
            persistenceId  = PersistenceId.ofUniqueId(s"taxonomy-ranker-$entityId"),
            emptyState     = State.empty(categoryId, siteId),
            commandHandler = commandHandler
          ).receiveSignal {
            case (state, org.apache.pekko.persistence.typed.state.RecoveryCompleted) =>
              val st = state.stats
              val ctr = if (st.wins > 0) 100.0 * st.clicks / st.wins else 0.0
              ctx.log.info(
                "TaxonomyRanker[{}|{}] recovered: wins={}, clicks={}, CTR={}%",
                categoryId,
                siteId,
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

  /** Request a Thompson-sampled score for this category.
    * Note: Each call returns a DIFFERENT sample - this is intentional for exploration.
    */
  final case class Quote(replyTo: ActorRef[Quoted]) extends Command

  /** Record a served impression for this category.
    *
    * Call this when an ad from this category is served to a user.
    *
    * @param revenue Revenue generated from this impression (CPM-based or actual)
    */
  final case class RecordImpression(revenue: Double) extends Command

  /** Record a click on a served impression.
    *
    * Call this when a user clicks on an ad from this category.
    * Should only be called after a corresponding RecordImpression.
    */
  final case class RecordClick() extends Command

  /** Get current stats for debugging/monitoring (deterministic) */
  final case class GetStats(replyTo: ActorRef[StatsSnapshot]) extends Command

  /** Response with Thompson-sampled weight */
  final case class Quoted(category: String, weight: Double, timestamp: Instant) extends promovolve.CborSerializable

  /** Deterministic stats snapshot for monitoring.
    *
    * @param ctr     Raw CTR: clicks / wins (0 if no wins)
    * @param meanCtr Bayesian posterior mean: (clicks + α) / (wins + α + β)
    */
  final case class StatsSnapshot(
      categoryId: String,
      siteId: String,
      wins: Double,      // Impressions served
      clicks: Double,    // Clicks on served impressions
      revenue: Double,   // Total revenue (for monitoring)
      ctr: Double,       // Raw CTR: clicks / wins
      meanCtr: Double,   // Bayesian posterior mean CTR
      timestamp: Instant
  ) extends promovolve.CborSerializable

  /** Settings for CTR-based Thompson Sampling.
    *
    * @param priorAlpha Beta prior α: pseudo-clicks (default 1.0 for uniform prior)
    * @param priorBeta  Beta prior β: pseudo-non-clicks (default 1.0 for uniform prior)
    */
  final case class Settings(
      halfLife: FiniteDuration   = 7.days,
      priorAlpha: Double         = 1.0,     // Beta prior: pseudo-clicks
      priorBeta: Double          = 1.0,     // Beta prior: pseudo-non-clicks
      flushEvery: FiniteDuration = 5.seconds
  )

  /** Stats for this category on this site - persisted.
    *
    * Field semantics:
    *  - wins:    Impressions served (used in Thompson Sampling)
    *  - clicks:  Clicks on served impressions (used in Thompson Sampling)
    *  - revenue: Total revenue in currency units (monitoring only)
    */
  final case class Stats(
      wins: Double      = 0.0,
      clicks: Double    = 0.0,
      revenue: Double   = 0.0,
      updatedMs: Long   = System.currentTimeMillis
  ) extends CborSerializable

  // ---------------- Persistent State ----------------

  /** Complete persisted state for a TaxonomyRankerEntity. */
  final case class State(
      categoryId: String,
      siteId: String,
      stats: Stats
  ) extends CborSerializable

  object Settings {

    /** Load settings from Typesafe Config */
    def fromConfig(config: Config): Settings = {
      val c = config.getConfig("promovolve.taxonomy-ranker")
      Settings(
        halfLife   = c.getDuration("half-life").toMillis.millis,
        priorAlpha = c.getDouble("prior-alpha"),
        priorBeta  = c.getDouble("prior-beta"),
        flushEvery = c.getDuration("flush-every").toMillis.millis
      )
    }
  }

  object State {
    def empty(categoryId: String, siteId: String): State = State(
      categoryId = categoryId,
      siteId     = siteId,
      stats      = Stats()
    )
  }

  private case object FlushTick extends Command
}
