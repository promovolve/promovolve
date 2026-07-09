package promovolve.browser

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.concurrent.Promise
import scala.concurrent.duration.*

/**
 * Covers [[LPWorker]]'s per-partition concurrency bounding (the crawler-tier
 * counterpart to [[CrawlWorker]]). The real analysis drives Playwright + an
 * R2 upload, so the worker is built with a stub `RunAnalysis` seam: it reports
 * each started URL to a probe and hands back a Promise the test completes, so
 * the grant/queue/drain logic is exercised hermetically.
 */
class LPWorkerSpec extends AnyWordSpec with Matchers {

  import LPWorker.*

  private def withKit(body: ActorTestKit => Unit): Unit = {
    val kit = ActorTestKit(
      s"LPWorkerSpec-${UUID.randomUUID().toString.take(8)}",
      ConfigFactory.parseString("""pekko.actor.provider = "local" """)
    )
    try body(kit)
    finally kit.shutdownTestKit()
  }

  private def doneFor(url: String): AnalyzeLPDone =
    AnalyzeLPDone(url, LPAnalysisResult(url, Vector.empty), Some(s"$url/shot.png"), None)

  /**
   * `RunAnalysis` stub: reports each started url to `report` and returns a
   * Promise the test completes via `finish` to simulate the analysis ending.
   */
  private final class StubRuns(report: ActorRef[String]) {
    private val promises = TrieMap.empty[String, Promise[AnalyzeLPDone]]
    val run: RunAnalysis = (req: AnalyzeLP) => {
      report ! req.url
      promises.getOrElseUpdate(req.url, Promise[AnalyzeLPDone]()).future
    }
    def finish(url: String): Unit = promises.get(url).foreach(_.trySuccess(doneFor(url)))
  }

  "workerIndexFor" should {
    "be deterministic and in range, even for a negative hashCode" in {
      LPWorker.workerIndexFor("https://x.example/a", 4) should (be >= 0 and be < 4)
      LPWorker.workerIndexFor("u", 4) shouldBe LPWorker.workerIndexFor("u", 4)
    }
  }

  "LPWorker" should {

    "run an analysis under cap and reply only once the Future completes" in withKit { kit =>
      val starts = kit.createTestProbe[String]()
      val stub = new StubRuns(starts.ref)
      val w = kit.spawn(LPWorker(0, stub.run, Settings(maxConcurrentPerWorker = 1)))
      val reply = kit.createTestProbe[AnalyzeLPDone]()
      val prog = kit.createTestProbe[AnalyzeLPProgress]()

      w ! AnalyzeLP("u1", "heading", reply.ref, prog.ref)
      starts.expectMessage("u1")
      reply.expectNoMessage(200.millis) // pending until the analysis finishes
      stub.finish("u1")
      reply.expectMessageType[AnalyzeLPDone].url shouldBe "u1"
    }

    "queue over-cap requests and grant them as slots free" in withKit { kit =>
      val starts = kit.createTestProbe[String]()
      val stub = new StubRuns(starts.ref)
      val w = kit.spawn(LPWorker(0, stub.run, Settings(maxConcurrentPerWorker = 1)))
      val r1 = kit.createTestProbe[AnalyzeLPDone]()
      val r2 = kit.createTestProbe[AnalyzeLPDone]()
      val prog = kit.createTestProbe[AnalyzeLPProgress]()

      w ! AnalyzeLP("a", "heading", r1.ref, prog.ref)
      w ! AnalyzeLP("b", "heading", r2.ref, prog.ref)
      starts.expectMessage("a")
      starts.expectNoMessage(200.millis) // b waits (cap = 1)

      stub.finish("a")
      r1.expectMessageType[AnalyzeLPDone].url shouldBe "a"
      starts.expectMessage("b") // slot freed → b granted
      stub.finish("b")
      r2.expectMessageType[AnalyzeLPDone].url shouldBe "b"
    }

    "treat a duplicate AnalyzeLP for an in-flight url as a no-op" in withKit { kit =>
      val starts = kit.createTestProbe[String]()
      val stub = new StubRuns(starts.ref)
      val w = kit.spawn(LPWorker(0, stub.run, Settings(maxConcurrentPerWorker = 2)))
      val r = kit.createTestProbe[AnalyzeLPDone]()
      val prog = kit.createTestProbe[AnalyzeLPProgress]()

      w ! AnalyzeLP("dup", "heading", r.ref, prog.ref)
      w ! AnalyzeLP("dup", "heading", r.ref, prog.ref)
      starts.expectMessage("dup")
      starts.expectNoMessage(200.millis) // only one analysis spawned
    }
  }
}
