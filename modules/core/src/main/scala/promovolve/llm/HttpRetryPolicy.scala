package promovolve.llm

import org.apache.pekko.http.scaladsl.model.StatusCode

import scala.concurrent.duration.*
import scala.util.Random

/**
 * Retry policy aligned with Google Vertex AI's recommended strategy
 * for generative-AI HTTP calls: retry 408 / 429 / 500 / 502 / 503 /
 * 504 (plus network-layer failures) with capped exponential backoff
 * and full jitter, up to 5 attempts.
 *
 * The Vertex Java SDK exposes this via `HttpRetryOptions`; we hit
 * Gemini directly over Pekko HTTP, so we replicate the same behavior
 * in-process ([[LlmHttp]] applies it to every LLM call). All call sites
 * share one policy so the whole system degrades uniformly under upstream
 * pressure — client-side token-bucket rate limiting prevents sending too
 * fast; this retries when the server still returns transient failures.
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
}
