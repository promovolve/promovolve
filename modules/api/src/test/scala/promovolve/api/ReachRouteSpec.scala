package promovolve.api

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.headers.`User-Agent`
import org.apache.pekko.http.scaladsl.model.{ HttpRequest, StatusCodes }
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import promovolve.common.PublisherSecretsRepo
import promovolve.fraud.{ IpClassifier, RequestHygiene, RequestRateGate }
import promovolve.publisher.ReachRepo

import java.time.LocalDate
import scala.collection.mutable
import scala.concurrent.Future

/** The reach beacon end to end through the route (GH #238): a URL minted by
  * ReachBeacon is accepted and counted with the right days-since; anything
  * tampered, malformed or from a bot is not counted. */
class ReachRouteSpec extends AnyWordSpec with Matchers with ScalaFutures with BeforeAndAfterAll {

  private val testKit = ActorTestKit()
  private given system: ActorSystem[?] = testKit.system
  override given patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))
  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val secret = "reach-test-secret-0123456789abcdef".getBytes("UTF-8")
  private val secrets = new PublisherSecretsRepo {
    def secretFor(pub: String): Future[Option[Array[Byte]]] =
      Future.successful(if (pub == "site-a") Some(secret) else None)
  }
  private object NoEvents extends EventLog {
    def logImpression(e: TrackEvent): Unit = ()
    def logClick(e: TrackEvent): Unit = ()
    def logCTAClick(e: TrackEvent): Unit = ()
    def logFold(e: TrackEvent): Unit = ()
    def logUnfold(e: TrackEvent): Unit = ()
  }
  private final class Recorder extends ReachRepo {
    val calls = mutable.Buffer.empty[(String, String, LocalDate, Int, String)]
    def record(c: String, s: String, d: LocalDate, ds: Int, f: String): Future[Boolean] = synchronized {
      calls += ((c, s, d, ds, f)); Future.successful(true)
    }
  }

  private val fresh = "0123456789abcdef0123456789abcdef"
  private val day = 20_000L

  private def signed(dayInUrl: Long = day): String = {
    val b = System.currentTimeMillis() / 60_000L
    val u = ReachBeacon.url("http://api.test/v1", secret, "site-a", "https://pub.example/a", "slot1", "cr1", 7L, b,
      "camp1", "adv1", day, "rid-1")
    if (dayInUrl == day) u else u.replace(s"&day=$day", s"&day=$dayInUrl")
  }

  private def call(url: String, ua: String = "Mozilla/5.0", hygiene: RequestHygiene = RequestHygiene.disabled)
      : (Int, Recorder) = {
    val rec = new Recorder
    val routes = new TrackRoutes(secrets, NoEvents, hygiene = hygiene, reach = Some(rec)).routes
    val path = url.stripPrefix("http://api.test")
    val res = Route.toFunction(routes)(using system)(HttpRequest(uri = path).withHeaders(`User-Agent`(ua))).futureValue
    res.discardEntityBytes()
    // record() runs after the response is decided; let it land.
    Thread.sleep(50)
    (res.status.intValue, rec)
  }

  "GET /v1/reach" should {

    "count a first-ever report as days_since 0 on the signed day" in {
      val (status, rec) = call(signed() + s"&prev=never&fresh=$fresh")
      status shouldBe StatusCodes.NoContent.intValue
      rec.calls.toList shouldBe List(("camp1", "site-a", LocalDate.ofEpochDay(day), ReachRepo.Never, fresh))
    }

    "count a returning browser with the gap from its stored day" in {
      val (_, rec) = call(signed() + s"&prev=${day - 3}&fresh=$fresh")
      rec.calls.map(_._4).toList shouldBe List(3)
    }

    "reject a rewritten day" in {
      val (status, rec) = call(signed(dayInUrl = day + 1) + s"&prev=never&fresh=$fresh")
      status shouldBe StatusCodes.Forbidden.intValue
      rec.calls shouldBe empty
    }

    "not count malformed browser input" in {
      call(signed() + "&prev=never&fresh=not-hex")._2.calls shouldBe empty
      call(signed() + s"&prev=$day&fresh=$fresh")._2.calls shouldBe empty // not before today
      call(signed() + s"&prev=yesterday&fresh=$fresh")._2.calls shouldBe empty
    }

    "not count bots" in {
      val botAware = new RequestHygiene(IpClassifier.empty, new RequestRateGate(Double.MaxValue, Double.MaxValue))
      val (status, rec) = call(signed() + s"&prev=never&fresh=$fresh", ua = "Googlebot/2.1", hygiene = botAware)
      status shouldBe StatusCodes.NoContent.intValue
      rec.calls shouldBe empty
    }
  }
}
