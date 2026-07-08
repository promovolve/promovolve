package promovolve

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.{AskPattern, Behaviors}
import org.apache.pekko.cluster.typed.{ClusterSingleton, ClusterSingletonSettings, SingletonActor}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.state.RecoveryCompleted
import org.apache.pekko.persistence.typed.state.scaladsl.{DurableStateBehavior, Effect}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.*

/** Cluster-wide token-bucket rate limiter built as a Pekko cluster
  * singleton. One actor owns the bucket; every node sends `Acquire`
  * to the same logical address; Pekko routes it to whichever node
  * holds the singleton at the moment. The actor's single-threaded
  * message loop means token math has no races.
  *
  * See `TokenBucketLimiter.md` for sequence diagrams covering the
  * fast path, the queue+Drain path, and the mixed-queue case the
  * `drainGranting` helper was written to handle.
  *
  * Persistence model: durable state is `(tokens, updatedAtMillis)`.
  * Refill is computed lazily from elapsed time on every command, so
  * the actor doesn't have to wake up on a fixed schedule just to
  * advance the bucket. An idle bucket therefore never writes to the
  * journal — the first command after a quiet period reconstructs the
  * correct count from the persisted timestamp.
  *
  * The waiter queue is in-memory by design. On failover any pending
  * ask future times out and the caller's normal retry path takes
  * over. Persisting waiter refs would buy nothing because reply refs
  * only complete the Future on the actor system that issued the ask.
  *
  * All externally observable side effects (replies, queue mutation,
  * timer scheduling, logs about what was done) happen inside
  * `.thenRun`, so a persist failure cannot leak a reply that the
  * recovered state then contradicts.
  *
  * For Gemini-specific defaults see [[GeminiRateLimiter]] which is a
  * thin wrapper supplying the env-var-driven Settings + persistenceId.
  */
object TokenBucketLimiter {

  // -- Protocol --

  sealed trait Command extends CborSerializable
  // Package-private so tests can construct Acquire directly with a
  // chosen expiresAtMillis to exercise the deadline-expired branch.
  // Application code should use the [[acquire]] / [[acquireOrFail]]
  // API rather than building this directly.
  private[promovolve] final case class Acquire(
      replyTo: ActorRef[Permit],
      expiresAtMillis: Long,
  ) extends Command
  // Internal timer: drain as many live waiters as currently-available
  // tokens permit. Self-scheduled while the queue is non-empty.
  // Package-private so tests can trigger a deterministic Drain pass
  // rather than racing the auto-scheduled timer. Application code
  // should not send this — the actor schedules it for itself.
  private[promovolve] case object Drain extends Command
  // One-shot self-message after recovery, only when the loaded count
  // exceeds the current `Settings.maxTokens` (cap shrank since last
  // save). Purely a journal-convergence step: `refill` already clamps
  // every read to the current cap so runtime grants are bounded
  // regardless of whether this message has landed yet. ClampToCap
  // exists so a subsequent recovery doesn't reload a stale over-cap
  // count — without it, an idle limiter would keep loading tokens=9
  // (or whatever the pre-shrink count was) on every restart until
  // the first organic Acquire writes a new state.
  private final case class ClampToCap(cap: Int) extends Command
  case object Stop extends Command

  /** Reply for [[acquire]]. */
  sealed trait Permit extends CborSerializable
  object Permit {
    case object Granted   extends Permit
    case object QueueFull extends Permit
    case object Expired   extends Permit
    case object Stopping  extends Permit
  }

  // -- Persistent State --
  //
  // tokens advances lazily: `refill(state, now)` recomputes the count
  // from elapsed time on demand. Only token-changing transitions
  // (grant, drain, clamp) actually persist.
  final case class State(
      tokens: Double,
      updatedAtMillis: Long,
  ) extends CborSerializable

  private final case class Waiter(
      replyTo: ActorRef[Permit],
      expiresAtMillis: Long,
  )

  // -- Settings --

  /** @param persistenceId    Durable-state journal key. Pick something
    *                         stable per logical limiter so the token
    *                         count survives restart.
    * @param singletonName    Cluster-singleton name. Usually matches
    *                         persistenceId.
    * @param maxTokens        Burst cap — the most tokens the bucket
    *                         can hold at once.
    * @param tokensPerSecond  Drip rate. The average sustained throughput.
    * @param maxQueueSize     Backpressure cap on the in-memory waiter
    *                         queue. New acquires past this get
    *                         [[Permit.QueueFull]].
    * @param askTimeout       Default ask timeout for [[acquire]] /
    *                         [[acquireOrFail]] when the caller does
    *                         not supply one.
    * @param singletonRole    Cluster role that hosts the singleton.
    *                         `None` allows any node.
    */
  final case class Settings(
      persistenceId: String,
      singletonName: String,
      maxTokens: Int,
      tokensPerSecond: Double,
      maxQueueSize: Int = 10_000,
      askTimeout: FiniteDuration = 30.seconds,
      singletonRole: Option[String] = Some("singleton"),
  )

  // -- Behavior --

  def apply(settings: Settings): Behavior[Command] =
    Behaviors.setup { ctx =>
      require(settings.persistenceId.nonEmpty, "persistenceId must be non-empty")
      require(settings.singletonName.nonEmpty, "singletonName must be non-empty")
      require(settings.maxTokens > 0, "maxTokens must be > 0")
      require(settings.tokensPerSecond > 0.0, "tokensPerSecond must be > 0")
      require(settings.maxQueueSize >= 0, "maxQueueSize must be >= 0")
      require(settings.askTimeout > Duration.Zero, "askTimeout must be > 0")

      val log =
        LoggerFactory.getLogger(s"promovolve.TokenBucketLimiter.${settings.persistenceId}")

      // Ephemeral waiter queue — never persisted. See class doc.
      val waiting: mutable.Queue[Waiter] = mutable.Queue.empty

      def nowMillis(): Long = System.currentTimeMillis()

      // Refill returns a view of `state` advanced to `now`, with tokens
      // capped at the *current* `settings.maxTokens`. The clamp is
      // applied unconditionally — even when the clock didn't advance,
      // and even when the persisted count loaded from the journal
      // exceeds the cap (recovery after `maxTokens` shrank, or a
      // backward clock jump). This is the load-bearing invariant that
      // keeps runtime correct without depending on ClampToCap to land
      // first. ClampToCap's job is to make the journal *converge* to
      // the new cap; refill's job is to ensure that even if the
      // journal is temporarily over-cap, no caller observes more than
      // `maxTokens` worth of tokens at any read.
      def refill(state: State, now: Long): State = {
        val capped = math.min(state.tokens, settings.maxTokens.toDouble)
        if (now <= state.updatedAtMillis) {
          if (capped == state.tokens) state
          else state.copy(tokens = capped)
        } else {
          val elapsedSeconds = (now - state.updatedAtMillis).toDouble / 1000.0
          val refilled       = capped + elapsedSeconds * settings.tokensPerSecond
          state.copy(
            tokens          = math.min(refilled, settings.maxTokens.toDouble),
            updatedAtMillis = now,
          )
        }
      }

      def millisUntilNextToken(state: State, now: Long): Long = {
        val refilled = refill(state, now)
        if (refilled.tokens >= 1.0) 0L
        else {
          val missing = 1.0 - refilled.tokens
          val millis  = math.ceil((missing / settings.tokensPerSecond) * 1000.0).toLong
          math.max(1L, millis)
        }
      }

      Behaviors.withTimers { timers =>

        def scheduleNextDrain(state: State): Unit =
          if (waiting.nonEmpty) {
            val now   = nowMillis()
            val delay = millisUntilNextToken(state, now).millis
            // startSingleTimer replaces any prior Drain — only one
            // outstanding wake-up is needed at a time. Re-arming with
            // the same key is intentional.
            timers.startSingleTimer(Drain, delay)
          }

        // Walk the entire queue, replying to each waiter in FIFO order.
        // Expired waiters (deadline ≤ now) get Permit.Expired regardless
        // of position. Up to `grantCount` *live* waiters get
        // Permit.Granted in queue order; any live waiters past that cap
        // are re-enqueued behind the surviving live ones. Returns
        // (granted, expired) so the caller can log both — the granted
        // count must match its `grantCount` budget (asserted at the
        // call site) and the expired count documents how many stale
        // entries were swept in this pass.
        //
        // Called only inside `thenRun` so any persisted token decrement
        // is already durable when these replies go out — a caller never
        // sees Granted for tokens the limiter hasn't booked.
        //
        // Earlier versions counted only the expired *prefix* of the
        // queue and then granted from the rest of the queue by raw
        // size. That treated a live→expired→live queue as having three
        // grantable waiters and could send Granted to the middle
        // expired one. The full-queue walk below preserves FIFO order
        // among live waiters and never grants an expired one.
        def drainGranting(now: Long, grantCount: Int): (Int, Int) = {
          var granted   = 0
          var expired   = 0
          val survivors = mutable.Queue.empty[Waiter]
          while (waiting.nonEmpty) {
            val w = waiting.dequeue()
            if (w.expiresAtMillis <= now) {
              w.replyTo ! Permit.Expired
              expired += 1
            } else if (granted < grantCount) {
              w.replyTo ! Permit.Granted
              granted += 1
            } else {
              survivors.enqueue(w)
            }
          }
          waiting.enqueueAll(survivors)
          (granted, expired)
        }

        def commandHandler(state: State, cmd: Command): Effect[State] = cmd match {

          case Acquire(replyTo, expiresAtMillis) =>
            val now      = nowMillis()
            val refilled = refill(state, now)

            if (expiresAtMillis <= now) {
              // Caller's deadline already passed. No durable change.
              Effect.none.thenRun(_ => replyTo ! Permit.Expired)

            } else if (waiting.isEmpty && refilled.tokens >= 1.0) {
              // Fast path: nobody is waiting and tokens are available.
              val next = refilled.copy(tokens = refilled.tokens - 1.0)
              Effect.persist(next).thenRun(_ => replyTo ! Permit.Granted)

            } else if (waiting.size >= settings.maxQueueSize) {
              Effect.none.thenRun { _ =>
                log.warn(
                  "Token bucket queue full: size={}, maxQueueSize={}",
                  waiting.size,
                  settings.maxQueueSize,
                )
                replyTo ! Permit.QueueFull
              }

            } else {
              // Fairness: when a queue already exists, every new
              // arrival joins the tail rather than stealing a token
              // an earlier waiter is entitled to. Drain handles FIFO
              // release; if tokens are available now, the scheduled
              // delay collapses to 0 and Drain fires immediately.
              Effect.none.thenRun { _ =>
                waiting.enqueue(Waiter(replyTo, expiresAtMillis))
                log.debug(
                  "Queued request: waiting={}, tokens={}",
                  waiting.size,
                  f"${refilled.tokens}%.3f",
                )
                scheduleNextDrain(refilled)
              }
            }

          case Drain =>
            val now       = nowMillis()
            val refilled  = refill(state, now)
            // Count *live* waiters across the whole queue, not just the
            // prefix — interleaved expired waiters mustn't inflate this.
            val liveCount = waiting.count(_.expiresAtMillis > now)
            val available = math.floor(refilled.tokens).toInt
            val toGrant   = math.min(available, liveCount)

            if (toGrant > 0) {
              val next = refilled.copy(tokens = refilled.tokens - toGrant.toDouble)
              Effect.persist(next).thenRun { _ =>
                val (granted, expired) = drainGranting(now, toGrant)
                // Cheap invariant check: drainGranting computed grants
                // against the same `waiting` snapshot we just sized
                // `toGrant` from, so the count must match. If it
                // doesn't, the queue was mutated between sizing and
                // draining, and the persisted decrement is now wrong.
                assert(
                  granted == toGrant,
                  s"drainGranting granted $granted of intended $toGrant",
                )
                if (granted > 0 || expired > 0 || waiting.nonEmpty)
                  log.debug(
                    "Drained: granted={}, expired={}, still queued={}",
                    granted,
                    expired,
                    waiting.size,
                  )
                scheduleNextDrain(next)
              }
            } else if (waiting.nonEmpty) {
              // Queue has waiters but no token to give. Sweep expired
              // ones (no durable change for that — they didn't consume
              // tokens) and re-arm Drain only if any live ones remain.
              Effect.none.thenRun { _ =>
                val (_, expired) = drainGranting(now, 0)
                if (expired > 0 || waiting.nonEmpty)
                  log.debug(
                    "Drain sweep: expired={}, still queued={}",
                    expired,
                    waiting.size,
                  )
                scheduleNextDrain(refilled)
              }
            } else {
              Effect.none
            }

          case ClampToCap(cap) =>
            val now           = nowMillis()
            val refilled      = refill(state, now)
            val clampedTokens = math.min(refilled.tokens, cap.toDouble)

            if (clampedTokens == state.tokens) {
              // No real change — bumping updatedAtMillis alone isn't
              // worth a journal write; lazy refill handles it.
              Effect.none
            } else {
              val clamped = refilled.copy(tokens = clampedTokens)
              Effect.persist(clamped).thenRun { _ =>
                log.info(
                  "Clamped tokens {} -> {} after cap shrink",
                  f"${state.tokens}%.3f",
                  f"$clampedTokens%.3f",
                )
              }
            }

          case Stop =>
            Effect
              .none[State]
              .thenRun { _ =>
                log.info("Stopping, failing {} queued requests", waiting.size)
                waiting.foreach(_.replyTo ! Permit.Stopping)
                waiting.clear()
              }
              .thenStop()
        }

        val initialNow = nowMillis()
        log.info(
          "Starting: maxTokens={}, rate={} tokens/s ({} per minute), maxQueueSize={}, askTimeout={}",
          settings.maxTokens,
          f"${settings.tokensPerSecond}%.3f",
          f"${settings.tokensPerSecond * 60}%.0f",
          settings.maxQueueSize,
          settings.askTimeout,
        )

        DurableStateBehavior[Command, State](
          persistenceId = PersistenceId.ofUniqueId(settings.persistenceId),
          // Start full on first ever boot. Subsequent boots load
          // whatever was persisted; lazy refill catches up from
          // updatedAtMillis on the first command.
          emptyState = State(
            tokens          = settings.maxTokens.toDouble,
            updatedAtMillis = initialNow,
          ),
          commandHandler = commandHandler,
        ).receiveSignal {
          case (state, RecoveryCompleted) =>
            val now       = nowMillis()
            val recovered = refill(state, now)
            log.info(
              "Recovered: {}/{} tokens, updatedAtMillis={}, rate={} tokens/s",
              f"${recovered.tokens}%.3f",
              settings.maxTokens,
              recovered.updatedAtMillis,
              f"${settings.tokensPerSecond}%.3f",
            )
            // Compare against the *raw* persisted count, not the
            // post-refill view: refill caps to maxTokens on read, so
            // checking `recovered.tokens` would almost never fire.
            // We want the journal itself to converge to the new cap
            // after a shrink, not just have it look right at runtime.
            if (state.tokens > settings.maxTokens.toDouble)
              ctx.self ! ClampToCap(settings.maxTokens)
        }
      }
    }

  // -- Singleton Init --

  def singletonInit(
      system: ActorSystem[?],
      settings: Settings,
  ): ActorRef[Command] = {
    val baseSettings = ClusterSingletonSettings(system)
    val singletonSettings = settings.singletonRole match {
      case Some(role) => baseSettings.withRole(role)
      case None       => baseSettings
    }
    ClusterSingleton(system).init(
      SingletonActor(
        Behaviors
          .supervise(TokenBucketLimiter(settings))
          .onFailure[Exception](SupervisorStrategy.restart),
        settings.singletonName,
      ).withStopMessage(Stop)
        .withSettings(singletonSettings)
    )
  }

  // -- Client API --

  /** Acquire a token. Completes with [[Permit.Granted]] once the
    * caller may proceed, or one of the denied variants when the
    * limiter refuses (queue full, deadline passed, shutting down).
    * Fails with `AskTimeoutException` after the ask timeout when the
    * singleton is unreachable.
    *
    * `timeout` controls both the ask deadline and the actor-side
    * expiry stamped on the request, so a queued caller whose Future
    * has already timed out client-side won't get served by Drain.
    */
  def acquire(
      limiter: ActorRef[Command],
      timeout: FiniteDuration,
  )(using system: ActorSystem[?]): Future[Permit] = {
    given Timeout = Timeout(timeout)
    import AskPattern.*
    val expiresAtMillis = System.currentTimeMillis() + timeout.toMillis
    limiter.ask[Permit](replyTo => Acquire(replyTo, expiresAtMillis))
  }

  /** Acquire a token using the limiter's configured `askTimeout`.
    * Prefer this overload in production — keeping the timeout on the
    * Settings record means there's a single source of truth for the
    * acquire deadline (instead of an independent default tucked into
    * the API surface that drifts from what the limiter was configured
    * for). */
  def acquire(
      limiter: ActorRef[Command],
      settings: Settings,
  )(using system: ActorSystem[?]): Future[Permit] =
    acquire(limiter, settings.askTimeout)

  /** Convenience: acquire a token and return a unit Future that
    * fails with a [[LimiterDenied]] subtype when the limiter
    * declined. Callers that don't need to distinguish denial reasons
    * can use this and let their normal failure path handle all four
    * outcomes (timeout, queue full, expired, stopping). */
  def acquireOrFail(
      limiter: ActorRef[Command],
      timeout: FiniteDuration,
  )(using system: ActorSystem[?]): Future[Unit] = {
    given scala.concurrent.ExecutionContext = system.executionContext
    acquire(limiter, timeout).flatMap {
      case Permit.Granted   => Future.successful(())
      case Permit.QueueFull => Future.failed(LimiterQueueFull)
      case Permit.Expired   => Future.failed(LimiterAcquireExpired)
      case Permit.Stopping  => Future.failed(LimiterStopping)
    }
  }

  /** [[acquireOrFail]] using the limiter's configured `askTimeout`. */
  def acquireOrFail(
      limiter: ActorRef[Command],
      settings: Settings,
  )(using system: ActorSystem[?]): Future[Unit] =
    acquireOrFail(limiter, settings.askTimeout)

  sealed abstract class LimiterDenied(msg: String)
      extends RuntimeException(msg)
      with scala.util.control.NoStackTrace
  case object LimiterQueueFull       extends LimiterDenied("rate limiter queue full")
  case object LimiterAcquireExpired  extends LimiterDenied("rate limiter acquire expired")
  case object LimiterStopping        extends LimiterDenied("rate limiter stopping")
}
