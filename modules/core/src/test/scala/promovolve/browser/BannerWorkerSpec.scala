package promovolve.browser

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.{ Future, Promise }

class BannerWorkerSpec extends AnyWordSpec with Matchers {
  private def withKit(test: ActorTestKit => Unit): Unit = {
    val kit = ActorTestKit()
    try test(kit)
    finally kit.shutdownTestKit()
  }

  "BannerWorker" should {
    "return stored screenshot metadata" in withKit { kit =>
      val result = BannerWorker.Result("hash", "assets/hash.webp", "image/webp", 1, None)
      val worker = kit.spawn(BannerWorker(_ => Future.successful(result)))
      val reply = kit.createTestProbe[BannerWorker.Result]()
      worker ! BannerWorker.Render("[]", 300, 250, reply.ref)
      reply.expectMessage(result)
    }

    "report asynchronous and synchronous rendering failures" in withKit { kit =>
      for (run <- Seq[BannerWorker.Run](
          _ => Future.failed(new IllegalStateException("failed")),
          _ => throw new IllegalStateException("failed")
        )) {
        val worker = kit.spawn(BannerWorker(run))
        val reply = kit.createTestProbe[BannerWorker.Result]()
        worker ! BannerWorker.Render("[]", 300, 250, reply.ref)
        reply.receiveMessage().error shouldBe Some("failed")
      }
    }

    "reject excess work and accept another request after completion" in withKit { kit =>
      val pending = Promise[BannerWorker.Result]()
      val worker = kit.spawn(BannerWorker(_ => pending.future))
      val reply = kit.createTestProbe[BannerWorker.Result]()
      for (_ <- 1 to 3) worker ! BannerWorker.Render("[]", 300, 250, reply.ref)
      reply.receiveMessage().error.get should include("busy")
      val result = BannerWorker.Result("hash", "key", "image/png", 0, None)
      pending.success(result)
      reply.expectMessage(result)
      reply.expectMessage(result)
      worker ! BannerWorker.Render("[]", 300, 250, reply.ref)
      reply.expectMessage(result)
    }
  }
}
