package promovolve.llm

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCode }
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.pattern.after
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Random, Success }

/**
 * Sends an LLM request with the one retry policy every call site shares
 * (#432). Before this, page classification, campaign classification and
 * landing-page extraction each retried their own way.
 *
 *  - Retries the statuses in [[HttpRetryPolicy.RetryableStatuses]] and
 *    network-layer failures, up to `maxAttempts` (5) attempts.
 *  - Takes a rate-limit token before EVERY attempt: a retry is a real API
 *    call. A refused token fails at once and is not retried — it is local
 *    back-pressure, and retrying it only adds load.
 *  - Waits what the server's Retry-After says when it sends one; otherwise
 *    backs off from 5s on 429 (the provider's quota window is empty, and a
 *    short retry just burns it) and from 1s on anything else.
 *
 * Returns the final status and body; the caller decides what a non-2xx
 * means for it.
 */
final class LlmHttp(using system: ActorSystem[?], ec: ExecutionContext) {

  import LlmHttp.*

  private val log = LoggerFactory.getLogger(getClass)
  private val http = Http(system.toClassic)
  private val scheduler = system.toClassic.scheduler

  def send(
      request: HttpRequest,
      tag: String,
      acquire: () => Future[Unit] = () => Future.unit,
      maxAttempts: Int = 5
  ): Future[Reply] = {
    def attempt(n: Int): Future[Reply] =
      acquire().recoverWith { case ex => Future.failed(TokenRefused(ex)) }.flatMap { _ =>
        http.singleRequest(request).transformWith {
          case Success(response) =>
            val wait = retryAfter(response)
            Unmarshal(response.entity).to[String].flatMap { body =>
              if (HttpRetryPolicy.isRetryable(response.status) && n < maxAttempts - 1)
                retry(n, s"HTTP ${response.status.intValue()}", retryDelay(n, Some(response.status.intValue()), wait))
              else Future.successful(Reply(response.status, body))
            }
          case Failure(ex) if n < maxAttempts - 1 =>
            retry(n, s"${ex.getClass.getSimpleName}: ${ex.getMessage}", retryDelay(n, None, None))
          case Failure(ex) => Future.failed(ex)
        }
      }

    def retry(n: Int, reason: String, delay: FiniteDuration): Future[Reply] = {
      log.warn("[LLM {}] {} — retrying in {} (attempt {}/{})", tag, reason, delay, n + 1, maxAttempts)
      after(delay, scheduler)(attempt(n + 1))
    }

    attempt(0)
  }
}

object LlmHttp {

  final case class Reply(status: StatusCode, body: String)

  /** The rate limiter refused a token; nothing was sent. */
  final case class TokenRefused(cause: Throwable)
      extends RuntimeException(s"rate limiter refused a token: ${cause.getMessage}", cause)

  def retryDelay(attempt: Int, status: Option[Int], retryAfter: Option[FiniteDuration]): FiniteDuration =
    retryAfter match {
      case Some(wait) => wait + Random.nextLong(1000L).millis
      case None       =>
        HttpRetryPolicy.backoff(attempt, initialDelay = if (status.contains(429)) 5.seconds else 1.second)
    }

  /** Google sends integer seconds; RFC 7231 also allows an HTTP-date, which Gemini does not use. */
  def retryAfter(response: HttpResponse): Option[FiniteDuration] =
    response.headers.find(_.lowercaseName == "retry-after").flatMap(_.value.trim.toIntOption).map(_.seconds)
}
