package promovolve

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}

import scala.concurrent.Future

/** Thin wrapper around [[TokenBucketLimiter]] supplying Gemini-specific
  * defaults: persistenceId/singletonName, plus env-driven cap/rate
  * pulled from `GEMINI_BURST` and `GEMINI_TOKENS_PER_MINUTE`.
  *
  * Defaults sized for Gemini AI Studio free-tier `gemini-2.5-flash`
  * (10 RPM upstream limit). Bucket stays strictly below — small burst
  * (5) prevents dumping a minute's worth in 100ms, which still 429s
  * even when the per-minute average is fine. Override via env for
  * paid/Vertex deploys:
  *   GEMINI_TOKENS_PER_MINUTE=1000  GEMINI_BURST=50
  *
  * `Command` and `acquire` are aliased through so existing call sites
  * (`ActorRef[GeminiRateLimiter.Command]`, `GeminiRateLimiter.acquire`)
  * continue to work unchanged.
  */
object GeminiRateLimiter {

  type Command = TokenBucketLimiter.Command
  type Permit  = TokenBucketLimiter.Permit
  val Stop: Command = TokenBucketLimiter.Stop

  def settings: TokenBucketLimiter.Settings = TokenBucketLimiter.Settings(
    persistenceId = "gemini-rate-limiter",
    singletonName = "gemini-rate-limiter",
    maxTokens     = sys.env.get("GEMINI_BURST").flatMap(_.toIntOption).getOrElse(5),
    tokensPerSecond = sys.env
      .get("GEMINI_TOKENS_PER_MINUTE")
      .flatMap(_.toDoubleOption)
      .map(_ / 60.0)
      .getOrElse(8.0 / 60.0), // 8 RPM — 20% below the 10 RPM ceiling
  )

  def singletonInit(system: ActorSystem[?]): ActorRef[Command] =
    TokenBucketLimiter.singletonInit(system, settings)

  /** Acquire a token; the returned Future fails with
    * [[TokenBucketLimiter.LimiterDenied]] if the limiter is shutting
    * down. The ask deadline is sourced from `settings.askTimeout` so
    * the API surface and the configured limiter agree on a single
    * value (no independent default to drift). */
  def acquire(limiter: ActorRef[Command])(using system: ActorSystem[?]): Future[Unit] =
    TokenBucketLimiter.acquireOrFail(limiter, settings)

  /** Lower-level: returns the raw [[Permit]] so callers can branch on
    * granted=true/false explicitly. */
  def acquirePermit(limiter: ActorRef[Command])(using system: ActorSystem[?]): Future[Permit] =
    TokenBucketLimiter.acquire(limiter, settings)
}
