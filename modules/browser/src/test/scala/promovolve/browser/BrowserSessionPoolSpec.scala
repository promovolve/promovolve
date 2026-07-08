package promovolve.browser

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Promise

/** Verifies the pool reserves session(s) for designer `Submit` work so the
  * interactive designer never queues behind a crawl flooding `Render`s.
  * Exercises the routing behavior with probe sessions (no Playwright). */
class BrowserSessionPoolSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private def crawlOpts = BrowserSession.CrawlOpts("example.com", ".*", None)

  private def renderMsg: BrowserSessionPool.Render = {
    val reply = createTestProbe[BrowserSession.PageScrapedResult]()
    BrowserSessionPool.Render("https://x.test", Array.empty, 0, crawlOpts, reply.ref)
  }

  private def submitMsg: BrowserSessionPool.Submit =
    BrowserSessionPool.Submit(_ => (), Promise[Any]())

  "reservedCount" should {
    "reserve the requested count when the crawler still keeps one" in {
      BrowserSessionPool.reservedCount(4, 1) shouldBe 1
      BrowserSessionPool.reservedCount(2, 1) shouldBe 1
    }
    "clamp so the crawler always keeps at least one session" in {
      BrowserSessionPool.reservedCount(4, 5) shouldBe 3
      BrowserSessionPool.reservedCount(1, 1) shouldBe 0 // size 1: no reservation possible
    }
    "never go below zero" in {
      BrowserSessionPool.reservedCount(4, 0) shouldBe 0
      BrowserSessionPool.reservedCount(4, -3) shouldBe 0
    }
  }

  "routing" should {
    "send Render only to crawler sessions, round-robin" in {
      val sessions = Vector.fill(4)(createTestProbe[BrowserSession.Command]())
      val crawler  = sessions.take(3).map(_.ref)
      val designer = sessions.drop(3).map(_.ref)
      val pool     = spawn(BrowserSessionPool.routing(crawler, designer))

      pool ! renderMsg
      pool ! renderMsg
      pool ! renderMsg
      sessions(0).expectMessageType[BrowserSession.Render]
      sessions(1).expectMessageType[BrowserSession.Render]
      sessions(2).expectMessageType[BrowserSession.Render]
      // reserved designer session sees nothing from crawler traffic
      sessions(3).expectNoMessage()

      // 4th render wraps back to crawler session 0
      pool ! renderMsg
      sessions(0).expectMessageType[BrowserSession.Render]
      sessions(3).expectNoMessage()
    }

    "round-robin Submit across ALL sessions (designer is not confined to the reserved lane)" in {
      val sessions = Vector.fill(4)(createTestProbe[BrowserSession.Command]())
      val crawler  = sessions.take(3).map(_.ref)
      val designer = sessions.drop(3).map(_.ref)
      val pool     = spawn(BrowserSessionPool.routing(crawler, designer))

      // 4 submits hit all 4 sessions once each — full parallelism, so the
      // banner render isn't stuck behind the LP analysis on one session.
      (1 to 4).foreach(_ => pool ! submitMsg)
      sessions.foreach(_.expectMessageType[BrowserSession.Submit])
    }

    "fall back to crawler sessions for Submit when none are reserved (size 1)" in {
      val only = createTestProbe[BrowserSession.Command]()
      val pool = spawn(BrowserSessionPool.routing(Vector(only.ref), Vector.empty))

      pool ! submitMsg
      pool ! renderMsg
      only.expectMessageType[BrowserSession.Submit]
      only.expectMessageType[BrowserSession.Render]
    }

    "broadcast Stop to every distinct session" in {
      val sessions = Vector.fill(3)(createTestProbe[BrowserSession.Command]())
      val crawler  = sessions.take(2).map(_.ref)
      val designer = sessions.drop(2).map(_.ref)
      val pool     = spawn(BrowserSessionPool.routing(crawler, designer))

      pool ! BrowserSessionPool.Stop
      sessions.foreach(_.expectMessage(BrowserSession.Stop))
    }
  }
}
