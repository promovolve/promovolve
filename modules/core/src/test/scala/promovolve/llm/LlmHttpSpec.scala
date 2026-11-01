package promovolve.llm

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{ Await, Future }
import scala.util.Try

/**
 * The one retry policy every LLM call goes through (#432). Before the
 * gateway each call site retried its own way (4, 5 or 6 attempts; a token
 * once or per attempt; only page classification honoured Retry-After).
 */
class LlmHttpSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val testKit = ActorTestKit()
  given system: org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system
  given ec: scala.concurrent.ExecutionContext = system.executionContext

  override def afterAll(): Unit = testKit.shutdownTestKit()

  // Answers each request with the next (status, body) in `replies`.
  private def stub(replies: (StatusCode, String)*): (Uri, AtomicInteger) = {
    val calls = new AtomicInteger(0)
    val route = extractRequest { _ =>
      val (status, body) = replies(math.min(calls.getAndIncrement(), replies.size - 1))
      complete(HttpResponse(status, entity = HttpEntity(body)))
    }
    val binding = Await.result(Http(system.toClassic).newServerAt("127.0.0.1", 0).bind(route), 5.seconds)
    (Uri(s"http://127.0.0.1:${binding.localAddress.getPort}/"), calls)
  }

  private def counting(): (() => Future[Unit], AtomicInteger) = {
    val n = new AtomicInteger(0)
    (() => { n.incrementAndGet(); Future.unit }, n)
  }

  private val http = new LlmHttp

  "LlmHttp.send" should {

    "retry a 503 and return the 200 that follows, taking a token for each attempt" in {
      val (uri, calls) = stub(StatusCodes.ServiceUnavailable -> "busy", StatusCodes.OK -> "answer")
      val (acquire, tokens) = counting()
      val reply = Await.result(http.send(HttpRequest(HttpMethods.POST, uri), "test", acquire), 10.seconds)
      reply shouldBe LlmHttp.Reply(StatusCodes.OK, "answer")
      calls.get shouldBe 2
      tokens.get shouldBe 2
    }

    "return a 400 without retrying" in {
      val (uri, calls) = stub(StatusCodes.BadRequest -> "bad request")
      val (acquire, _) = counting()
      val reply = Await.result(http.send(HttpRequest(HttpMethods.POST, uri), "test", acquire), 10.seconds)
      reply shouldBe LlmHttp.Reply(StatusCodes.BadRequest, "bad request")
      calls.get shouldBe 1
    }

    "fail without sending, and without retrying, when the rate limiter refuses a token" in {
      // A refused token is local back-pressure: retrying it only adds load.
      val (uri, calls) = stub(StatusCodes.OK -> "answer")
      val refusals = new AtomicInteger(0)
      val refuse = () => { refusals.incrementAndGet(); Future.failed(new RuntimeException("queue full")) }
      Try(Await.result(http.send(HttpRequest(HttpMethods.POST, uri), "test", refuse), 10.seconds)).isFailure shouldBe true
      calls.get shouldBe 0
      refusals.get shouldBe 1
    }

    "retry a network failure, then fail" in {
      val (acquire, tokens) = counting()
      // Nothing listens on port 1.
      val sent = http.send(HttpRequest(HttpMethods.POST, Uri("http://127.0.0.1:1/")), "test", acquire, maxAttempts = 2)
      Try(Await.result(sent, 10.seconds)).isFailure shouldBe true
      tokens.get shouldBe 2
    }
  }

  "LlmHttp.retryDelay" should {

    "wait what the server's Retry-After says, plus under a second of jitter" in {
      val d = LlmHttp.retryDelay(attempt = 0, status = Some(429), retryAfter = Some(7.seconds))
      d should (be >= 7.seconds and be < 8.seconds)
    }

    "back off from 5s on a 429 and from 1s otherwise (full jitter)" in {
      (1 to 50).foreach { _ =>
        LlmHttp.retryDelay(attempt = 1, status = Some(429), retryAfter = None) should be <= 10.seconds
        LlmHttp.retryDelay(attempt = 1, status = Some(503), retryAfter = None) should be <= 2.seconds
        LlmHttp.retryDelay(attempt = 1, status = None, retryAfter = None) should be <= 2.seconds
      }
      (1 to 50).map(_ => LlmHttp.retryDelay(attempt = 1, status = Some(429), retryAfter = None)).max should be > 2.seconds
    }
  }

  "LlmHttp.retryAfter" should {
    "read integer seconds and ignore anything else" in {
      LlmHttp.retryAfter(HttpResponse(headers = List(RawHeader("Retry-After", "12")))) shouldBe Some(12.seconds)
      LlmHttp.retryAfter(HttpResponse(headers = List(RawHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")))) shouldBe None
      LlmHttp.retryAfter(HttpResponse()) shouldBe None
    }
  }
}
