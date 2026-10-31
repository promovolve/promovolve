package promovolve.api

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{ Cluster, Join }
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.browser.{ BannerWorker, LPAnalysisResult, LPWorker }
import promovolve.publisher.assets.ImageStorage
import promovolve.publisher.delivery.ServeIndexDData
import spray.json.*

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.*

class BrowserDispatchSpec extends AnyWordSpec with Matchers {
  private def withKit(role: String = "browser")(test: ActorTestKit => Unit): Unit = {
    val config = ConfigFactory.parseString(s"""
      pekko.actor.provider = cluster
      pekko.remote.artery.canonical.hostname = "127.0.0.1"
      pekko.remote.artery.canonical.port = 0
      pekko.cluster.seed-nodes = []
      pekko.cluster.roles = [$role]
      pekko.cluster.distributed-data.durable.keys = []
      CDN_BASE_URL = "http://storage.invalid"
    """).withFallback(ConfigFactory.parseResources("application.conf"))
      .withFallback(ConfigFactory.defaultReference()).resolve()
    val kit = ActorTestKit(config)
    try {
      val cluster = Cluster(kit.system)
      cluster.manager ! Join(cluster.selfMember.address)
      kit.createTestProbe[Nothing]().awaitAssert(cluster.selfMember.status shouldBe MemberStatus.Up, 10.seconds)
      test(kit)
    } finally kit.shutdownTestKit()
  }

  private val storage = new ImageStorage {
    def store(hash: String, bytes: Array[Byte], mime: String): Future[String] =
      Future.failed(new AssertionError("API must not store worker renders"))
    def fetch(hash: String): Future[Option[Array[Byte]]] = Future.successful(Some(Array[Byte](1, 2, 3)))
    def exists(hash: String): Future[Boolean] = Future.successful(false)
  }

  "LP dispatch" should {
    "serve synchronous and asynchronous requests without a local analyzer" in withKit() { kit =>
      given ActorSystem[?] = kit.system
      val sharding = ClusterSharding(kit.system)
      LPWorker.initSharding(kit.system, sharding,
        request => {
          request.progressTo ! LPWorker.AnalyzeLPProgress(request.url, "http://storage.invalid/shot.png")
          Future.successful(LPWorker.AnalyzeLPDone(request.url, LPAnalysisResult(request.url, Vector.empty),
            Some("http://storage.invalid/shot.png"), None))
        }, LPWorker.Settings())
      val endpoint = new EndpointRoutes(sharding, kit.createTestProbe[ServeIndexDData.Cmd]().ref,
        cdnBaseUrl = "http://storage.invalid", lpAnalyzer = None, lpWorkerEnabled = true)
      val handle = Route.toFunction(endpoint.routes)
      def post(path: String) = HttpRequest(HttpMethods.POST, uri = path,
        entity = HttpEntity(ContentTypes.`application/json`, """{"url":"https://example.com"}"""))
      Await.result(handle(post("/v1/creatives/analyze-lp")), 10.seconds).status shouldBe StatusCodes.OK
      val response = Await.result(handle(post("/v1/creatives/analyze-lp/start")), 10.seconds)
      response.status shouldBe StatusCodes.OK
      val body = Await.result(response.entity.toStrict(3.seconds), 3.seconds).data.utf8String.parseJson.asJsObject
      val jobId = body.fields("jobId").convertTo[String](using DefaultJsonProtocol.StringJsonFormat)
      kit.createTestProbe[Nothing]().awaitAssert {
        val status = Await.result(handle(HttpRequest(uri = s"/v1/creatives/analyze-lp/status/$jobId")), 3.seconds)
        val text = Await.result(status.entity.toStrict(3.seconds), 3.seconds).data.utf8String
        text should include("\"done\"")
        text should include("\"screenshotReady\":true")
      }
    }
  }

  "Banner dispatch" should {
    "fetch the stored image rather than send screenshot bytes over the cluster" in withKit() { kit =>
      given ActorSystem[?] = kit.system
      val sharding = ClusterSharding(kit.system)
      BannerWorker.init(kit.system, sharding,
        _ =>
          Future.successful(
            BannerWorker.Result("hash", "assets/hash.webp", "image/webp", 2, None)))
      val image = Await.result(BannerRenderer.remote(sharding, storage)("[]", 300, 250), 10.seconds)
      image.key shouldBe "assets/hash.webp"
      image.bytes.toSeq shouldBe Seq[Byte](1, 2, 3)
      image.brokenImages shouldBe 2
    }

    "fail within the ask timeout when the browser role is absent" in withKit("api") { kit =>
      given ActorSystem[?] = kit.system
      val sharding = ClusterSharding(kit.system)
      BannerWorker.init(kit.system, sharding, _ => Future.failed(new AssertionError("No worker should run here")))
      intercept[java.util.concurrent.TimeoutException] {
        Await.result(BannerRenderer.remote(sharding, storage, 100.millis)("[]", 300, 250), 3.seconds)
      }
    }
  }
}
