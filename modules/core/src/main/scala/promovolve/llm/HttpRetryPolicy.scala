package promovolve.llm

import org.apache.pekko.actor.Scheduler
import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.pattern.after

import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Random, Success }

/**
 * Retry policy aligned with Google Vertex AI's recommended strategy
 * for generative-AI HTTP calls: retry 408 / 429 / 500 / 502 / 503 /
 * 504 (plus network-layer failures) with capped exponential backoff
 * and full jitter, up to 5 attempts.
 *
 * The Vertex Java SDK exposes this via `HttpRetryOptions`; we hit
 * Gemini directly over Pekko HTTP, so we replicate the same behavior
 * in-process. All Gemini-bound clients share one policy so the whole
 * system degrades uniformly under upstream pressure — client-side
 * token-bucket rate limiting prevents sending too fast; this retries
 * when the server still returns transient failures.
 *
 * See: https://docs.cloud.google.com/vertex-ai/generative-ai/docs/retry-strategy#java
 */
object HttpRetryPolicy {
  // Exactly the status codes Vertex's HttpRetryOptions default to.
  val RetryableStatuses: Set[Int] = Set(408, 429, 500, 502, 503, 504)

  def isRetryable(status: StatusCode): Boolean =
    RetryableStatuses.contains(status.intValue())

  /**
   * Capped exponential backoff with full jitter (AWS-style).
   * `attempt` is zero-indexed.
   */
  def backoff(
      attempt: Int,
      initialDelay: FiniteDuration = 1.second,
      maxDelay: FiniteDuration = 60.seconds
  ): FiniteDuration = {
    val rawMs = initialDelay.toMillis.toDouble * math.pow(2.0, attempt.toDouble)
    val cappedMs = math.min(rawMs, maxDelay.toMillis.toDouble)
    // Full jitter: uniform(0, cappedMs). Smoother tail than additive
    // or multiplicative jitter when many callers retry together.
    val jitteredMs = (Random.nextDouble() * cappedMs).toLong.max(0L)
    jitteredMs.millis
  }

  /**
   * Run `op` with Vertex-aligned retry. Retries on:
   *  - a successful result satisfying `shouldRetry` (caller decides —
   *    typically "HTTP status is retryable"), OR
   *  - a Future failure (network layer: connection reset, timeout, …).
   *
   * `onRetry(attempt, delay)` is invoked before each scheduled retry
   * so callers can log without the policy owning log-format choices.
   */
  def withRetry[T](
      op: () => Future[T],
      shouldRetry: T => Boolean,
      maxAttempts: Int = 5,
      initialDelay: FiniteDuration = 1.second,
      maxDelay: FiniteDuration = 60.seconds,
      onRetry: (Int, FiniteDuration, Either[Throwable, T]) => Unit =
        (_: Int, _: FiniteDuration, _: Either[Throwable, T]) => ()
  )(using ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    def run(attempt: Int): Future[T] = op().transformWith {
      case Success(res) if shouldRetry(res) && attempt < maxAttempts - 1 =>
        val delay = backoff(attempt, initialDelay, maxDelay)
        onRetry(attempt, delay, Right(res))
        after(delay, scheduler)(run(attempt + 1))
      case Success(res)                             => Future.successful(res)
      case Failure(ex) if attempt < maxAttempts - 1 =>
        val delay = backoff(attempt, initialDelay, maxDelay)
        onRetry(attempt, delay, Left(ex))
        after(delay, scheduler)(run(attempt + 1))
      case Failure(ex) => Future.failed(ex)
    }
    run(0)
  }
}
