package promovolve.api

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.publisher.assets.ImageStorage

import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration.*

class BrowserRuntimeSpec extends AnyWordSpec with Matchers {
  "A browser-only node" should {
    "initialize real browser workers without initializing JDBC repositories" in {
      val config = ConfigFactory.parseString("""
        pekko.actor.provider = cluster
        pekko.remote.artery.canonical.hostname = "127.0.0.1"
        pekko.remote.artery.canonical.port = 0
        pekko.cluster.seed-nodes = []
        pekko.cluster.roles = [browser]
        pekko.cluster.distributed-data.durable.keys = []
        jdbc-durable-state-store.slick.db.driver = "must.not.load.DatabaseDriver"
        promovolve.browser.browser-pool.size = 1
        CDN_BASE_URL = "http://storage.invalid"
      """).withFallback(ConfigFactory.parseResources("application.conf"))
        .withFallback(ConfigFactory.defaultReference()).resolve()
      val kit = ActorTestKit(config)
      val storage = new ImageStorage {
        def store(hash: String, bytes: Array[Byte], mime: String): Future[String] = Future.successful(hash)
        def fetch(hash: String): Future[Option[Array[Byte]]] = Future.successful(None)
        def exists(hash: String): Future[Boolean] = Future.successful(false)
      }
      val started = Promise[Unit]()
      try {
        kit.spawn(Main.Guardian(config,
          (system, sharding) => {
            BrowserRuntime.start(system, sharding, storage)
            started.success(())
          }))
        Await.result(started.future, 10.seconds) shouldBe ()
      } finally kit.shutdownTestKit()
    }
  }
}
