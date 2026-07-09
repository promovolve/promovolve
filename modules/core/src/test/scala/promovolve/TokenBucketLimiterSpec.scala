package promovolve

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.state.DurableStateStoreRegistry
import org.apache.pekko.persistence.testkit.PersistenceTestKitDurableStateStorePlugin
import org.apache.pekko.persistence.testkit.state.scaladsl.PersistenceTestKitDurableStateStore
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Span }
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class TokenBucketLimiterSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with Eventually {

  // serialize-messages = on forces every message through serialization
  // even in local-only mode, so the test exercises the CBOR codec the
  // singleton uses cross-node. allow-java-serialization = off makes a
  // Java fallback fail loudly rather than silently mask a missing
  // CborSerializable mixin on Command or Permit.
  private val testConfig =
    PersistenceTestKitDurableStateStorePlugin.config.withFallback(
      ConfigFactory.parseString(
        """
          |pekko {
          |  loglevel = "WARNING"
          |  actor {
          |    provider = "local"
          |    allow-java-serialization = off
          |    warn-about-java-serializer-usage = on
          |    serialize-messages = on
          |    serializers {
          |      jackson-cbor = "org.apache.pekko.serialization.jackson.JacksonCborSerializer"
          |    }
          |    serialization-bindings {
          |      "promovolve.CborSerializable" = jackson-cbor
          |    }
          |  }
          |}
          |""".stripMargin
      )
    )

  private val testKit: ActorTestKit =
    ActorTestKit("TokenBucketLimiterSpec", testConfig)

  given ActorSystem[?] = testKit.system
  given scala.concurrent.ExecutionContext = testKit.system.executionContext

  override def afterAll(): Unit = testKit.shutdownTestKit()

  override implicit def patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(8000, Millis), interval = Span(20, Millis))

  import TokenBucketLimiter.{ Acquire, Drain, Permit, Stop }

  private def settings(
      id: String,
      maxTokens: Int,
      tokensPerSecond: Double,
      maxQueueSize: Int = 10_000
  ): TokenBucketLimiter.Settings =
    TokenBucketLimiter.Settings(
      persistenceId = id,
      singletonName = id,
      maxTokens = maxTokens,
      tokensPerSecond = tokensPerSecond,
      maxQueueSize = maxQueueSize
    )

  private def uniqueId(prefix: String): String =
    s"$prefix-${UUID.randomUUID().toString.take(8)}"

  "TokenBucketLimiter" should {

    "grant immediately when tokens are available" in {
      val id = uniqueId("grant")
      val limiter = testKit.spawn(
        TokenBucketLimiter(settings(id, maxTokens = 3, tokensPerSecond = 1.0))
      )
      TokenBucketLimiter.acquire(limiter, 2.seconds).futureValue shouldBe Permit.Granted
    }

    "serve queued waiters in FIFO order when the bucket starves" in {
      val id = uniqueId("fifo")
      // 10 tok/s → one drain every ~100ms. maxTokens=1 ensures everyone
      // past the first acquire is queued.
      val limiter = testKit.spawn(
        TokenBucketLimiter(settings(id, maxTokens = 1, tokensPerSecond = 10.0))
      )
      // Drain the starting token so the next three all queue.
      TokenBucketLimiter.acquire(limiter, 2.seconds).futureValue shouldBe Permit.Granted

      val completionOrder = new ConcurrentLinkedQueue[Int]()
      def acquireTagged(tag: Int): Future[Permit] =
        TokenBucketLimiter.acquire(limiter, 5.seconds).map { p =>
          completionOrder.add(tag); p
        }

      val all = Future.sequence(Seq(acquireTagged(1), acquireTagged(2), acquireTagged(3)))
      all.futureValue.foreach(_ shouldBe Permit.Granted)

      completionOrder.asScala.toSeq shouldBe Seq(1, 2, 3)
    }

    "reject acquires past maxQueueSize with QueueFull" in {
      val id = uniqueId("full")
      // Very slow refill so the queue does not drain during the test.
      val limiter = testKit.spawn(
        TokenBucketLimiter(
          settings(id, maxTokens = 1, tokensPerSecond = 0.01, maxQueueSize = 2)
        )
      )
      TokenBucketLimiter.acquire(limiter, 30.seconds).futureValue shouldBe Permit.Granted

      val w1 = TokenBucketLimiter.acquire(limiter, 30.seconds)
      val w2 = TokenBucketLimiter.acquire(limiter, 30.seconds)

      // Give w1, w2 time to actually land in the queue before we send w3.
      eventually {
        w1.isCompleted shouldBe false
        w2.isCompleted shouldBe false
      }
      Thread.sleep(100)

      TokenBucketLimiter.acquire(limiter, 30.seconds).futureValue shouldBe Permit.QueueFull
    }

    "reply Expired when the request's deadline has already passed" in {
      val id = uniqueId("expired")
      val limiter = testKit.spawn(
        TokenBucketLimiter(settings(id, maxTokens = 3, tokensPerSecond = 1.0))
      )
      val probe = testKit.createTestProbe[Permit]()
      // Stale deadline — actor must reply Expired and not consume a token.
      limiter ! Acquire(probe.ref, expiresAtMillis = System.currentTimeMillis() - 1000)
      probe.expectMessage(Permit.Expired)

      // Bucket should still have its 3 tokens — drain them to verify.
      val probe2 = testKit.createTestProbe[Permit]()
      val future = System.currentTimeMillis() + 10_000
      limiter ! Acquire(probe2.ref, expiresAtMillis = future)
      probe2.expectMessage(Permit.Granted)
      limiter ! Acquire(probe2.ref, expiresAtMillis = future)
      probe2.expectMessage(Permit.Granted)
      limiter ! Acquire(probe2.ref, expiresAtMillis = future)
      probe2.expectMessage(Permit.Granted)
    }

    "reply Stopping to queued waiters when the actor stops" in {
      val id = uniqueId("stopping")
      val limiter = testKit.spawn(
        TokenBucketLimiter(settings(id, maxTokens = 1, tokensPerSecond = 0.01))
      )
      // Drain the starting token.
      TokenBucketLimiter.acquire(limiter, 30.seconds).futureValue shouldBe Permit.Granted

      val queued = TokenBucketLimiter.acquire(limiter, 30.seconds)
      // Let the Acquire actually land in the waiter queue before stopping.
      Thread.sleep(100)
      queued.isCompleted shouldBe false

      limiter ! Stop

      queued.futureValue shouldBe Permit.Stopping
    }

    "Drain sweeps an expired waiter in the middle of the queue without granting it" in {
      // The test owns Drain triggering explicitly via `limiter ! Drain`
      // — no dependency on the auto-scheduled timer or on a specific
      // refill latency. tokensPerSecond is set so low that, even on a
      // slow CI box, the bucket cannot meaningfully refill during the
      // test window; the only token in play is the starting one,
      // which a warmup Acquire consumes upfront. After that, every
      // subsequent Acquire queues.
      //
      // Pre-fix bug: queueSnapshot only counted the expired *prefix*
      // (zero here, since live1 is first). liveCount was reported as
      // 3 and the prefix-drain dequeued all three in order — sending
      // Permit.Granted to the middle expired waiter. The new
      // drainGranting walks the entire queue, classifies each waiter
      // by deadline regardless of position, and sweeps the middle as
      // Permit.Expired while leaving live waiters untouched (here,
      // because no tokens are available, they stay queued).
      val id = uniqueId("middle-expired-sweep")
      val limiter = testKit.spawn(
        TokenBucketLimiter(settings(id, maxTokens = 1, tokensPerSecond = 0.001))
      )
      val warmup = testKit.createTestProbe[Permit]("warmup")
      val far = System.currentTimeMillis() + 60_000
      limiter ! Acquire(warmup.ref, expiresAtMillis = far)
      warmup.expectMessage(Permit.Granted)

      val baseNow = System.currentTimeMillis()
      val live1 = testKit.createTestProbe[Permit]("live1")
      val expiring = testKit.createTestProbe[Permit]("expiring")
      val live3 = testKit.createTestProbe[Permit]("live3")

      // Generous live deadlines, short-but-not-tiny expiring deadline.
      // 300ms gives plenty of room above scheduler jitter on CI.
      limiter ! Acquire(live1.ref, expiresAtMillis = baseNow + 30_000)
      limiter ! Acquire(expiring.ref, expiresAtMillis = baseNow + 300)
      limiter ! Acquire(live3.ref, expiresAtMillis = baseNow + 30_000)

      // Wait until the middle waiter's deadline is comfortably in the
      // past. The sleep gap (700ms) is much larger than the deadline
      // (300ms) so a slow CI box still sees expiring as expired.
      Thread.sleep(700)
      limiter ! Drain

      // The bucket has effectively zero tokens (rate is 0.001/s), so
      // Drain takes the no-tokens branch and only sweeps expired
      // waiters. expiring is replied Permit.Expired; the live waiters
      // stay queued. The pre-fix bug would have miscounted liveCount
      // as 3 and either granted the prefix in order (sending Granted
      // to expiring) or otherwise over-consumed.
      expiring.expectMessage(2.seconds, Permit.Expired)
      live1.expectNoMessage(200.millis)
      live3.expectNoMessage(100.millis)
    }

    "Drain grants live waiters and skips an interleaved expired one even with tokens available" in {
      // Sister of the sweep test above: the bucket eventually has
      // enough tokens to grant the live waiters. The test's correctness
      // doesn't depend on a specific refill latency — it depends only
      // on (a) the queue forming in the order [live1, expiring, live3]
      // and (b) enough time passing that, when Drain fires, expiring's
      // deadline is in the past and at least one token has refilled.
      //
      // To get the queue formed in exactly that order, the bucket must
      // hold zero tokens at the moment each Acquire arrives — otherwise
      // the fast-path branch consumes a token and the Acquire never
      // queues. Slow refill rate + warmup matched to maxTokens gives
      // us that: by the time the 3 warmups complete, the bucket is at
      // ~0 tokens and the rate is too slow for any to refill in the
      // microseconds before the queueing Acquires arrive.
      //
      // After queue formation we wait long enough for two things to
      // be true: expiring's deadline has comfortably passed, and the
      // bucket has refilled enough to grant the live waiters across
      // one or more Drain cycles (auto-scheduled or explicit). The
      // explicit `limiter ! Drain` afterwards is a nudge — if the
      // auto-scheduled Drain has already fired, this one is a no-op
      // on grants and a cheap sweep on any new expired entries.
      //
      // Load-bearing invariant: expiring NEVER receives Permit.Granted,
      // only Permit.Expired. The pre-fix bug would have prefix-drained
      // it as Granted when liveCount was over-counted.
      val id = uniqueId("middle-expired-grants")
      val limiter = testKit.spawn(
        TokenBucketLimiter(settings(id, maxTokens = 3, tokensPerSecond = 1.0))
      )
      // Drain the starting 3 tokens so the next Acquires queue.
      val warmup = testKit.createTestProbe[Permit]("warmup")
      val far = System.currentTimeMillis() + 60_000
      (1 to 3).foreach(_ => limiter ! Acquire(warmup.ref, expiresAtMillis = far))
      (1 to 3).foreach(_ => warmup.expectMessage(Permit.Granted))

      val baseNow = System.currentTimeMillis()
      val live1 = testKit.createTestProbe[Permit]("live1")
      val expiring = testKit.createTestProbe[Permit]("expiring")
      val live3 = testKit.createTestProbe[Permit]("live3")

      // expiring's deadline (+300ms) is much shorter than the live
      // deadlines (+30s) and well below the per-token refill interval
      // (1s), so expiring is guaranteed to be dead by the time any
      // token-granting Drain pass runs. live1 and live3 stay alive
      // well past the end of the test.
      limiter ! Acquire(live1.ref, expiresAtMillis = baseNow + 30_000)
      limiter ! Acquire(expiring.ref, expiresAtMillis = baseNow + 300)
      limiter ! Acquire(live3.ref, expiresAtMillis = baseNow + 30_000)

      // Wait until expiring is definitely expired before issuing the
      // explicit Drain. 600ms is 2× the expiring deadline + headroom
      // for CI scheduling jitter; well below the first auto-Drain
      // firing time (~1000ms at rate=1/s), so the auto-Drain timer
      // hasn't yet pre-empted us.
      Thread.sleep(600)
      limiter ! Drain
      // Eventually, after enough time for one token to refill, Drain
      // grants live1 and expires expiring. After another token, live3
      // gets granted. The TestProbe.expectMessage windows (3 seconds)
      // are well above the natural per-token interval (1 second), so
      // they tolerate ordinary CI jitter without baking in a specific
      // refill cycle expectation.
      expiring.expectMessage(3.seconds, Permit.Expired)
      live1.expectMessage(3.seconds, Permit.Granted)
      live3.expectMessage(3.seconds, Permit.Granted)
      // Belt-and-suspenders: expiring is not granted by a subsequent
      // Drain pass either.
      expiring.expectNoMessage(200.millis)
    }

    "when Granted is delivered, the persisted token state already reflects the decrement" in {
      // Pekko's DurableState test store doesn't expose a failNext-style
      // hook in 1.4.0, so we can't directly assert "persist failed →
      // no Granted." The next-best test for the same invariant is the
      // positive direction: by the time the caller observes Granted,
      // the durable state has already been written. If TokenBucketLimiter
      // ever regressed to sending Granted before Effect.persist, this
      // would race — sometimes the assertion would see the pre-decrement
      // value and fail. The current code sends Granted from inside
      // `.thenRun`, so the assertion is deterministic.
      val id = uniqueId("grant-after-persist")
      val limiter = testKit.spawn(
        TokenBucketLimiter(settings(id, maxTokens = 5, tokensPerSecond = 1.0))
      )
      val store = DurableStateStoreRegistry(testKit.system)
        .durableStateStoreFor[PersistenceTestKitDurableStateStore[TokenBucketLimiter.State]](
          PersistenceTestKitDurableStateStore.Identifier
        )

      // Synchronous: acquire and block until the reply lands.
      TokenBucketLimiter.acquire(limiter, 5.seconds).futureValue shouldBe Permit.Granted

      // Right after Granted, the journal must show tokens decremented.
      val record = store.getObject(id).futureValue
      record.value should not be empty
      // Starting tokens = 5, one consumed, so persisted count should
      // be ≤ 4 (lazy refill may have added back a fraction of a second
      // since persist, but never more than one full token).
      record.value.get.tokens should be < 5.0
      record.value.get.tokens should be > 3.5
    }

    "clamp tokens on recovery when maxTokens has shrunk across restart" in {
      val id = uniqueId("clamp")

      // Lifecycle 1: maxTokens=10. Acquire once to force a journal write
      // (Permit.Granted only arrives after persist completes).
      val limiter1 = testKit.spawn(
        TokenBucketLimiter(settings(id, maxTokens = 10, tokensPerSecond = 1.0)),
        s"$id-1"
      )
      TokenBucketLimiter.acquire(limiter1, 5.seconds).futureValue shouldBe Permit.Granted
      testKit.stop(limiter1)
      Thread.sleep(100)

      // Lifecycle 2: same persistenceId, smaller cap. Recovery loads
      // tokens=9. Runtime correctness is guaranteed by `refill`
      // clamping every read to the current `maxTokens` regardless of
      // whether ClampToCap has landed yet — no Thread.sleep needed.
      // ClampToCap's role is purely journal convergence (so a
      // subsequent recovery doesn't reload a stale over-cap count).
      val limiter2 = testKit.spawn(
        TokenBucketLimiter(settings(id, maxTokens = 5, tokensPerSecond = 1.0)),
        s"$id-2"
      )

      val probe = testKit.createTestProbe[Permit]()
      def farFuture() = System.currentTimeMillis() + 10_000

      // Drain 5 — all should be Granted from the clamped pool, even
      // if Acquires arrive before ClampToCap fires. refill clamps the
      // local view to maxTokens=5 on every call, so the loaded
      // tokens=9 is never observable at the auction boundary.
      (1 to 5).foreach { _ =>
        limiter2 ! Acquire(probe.ref, expiresAtMillis = farFuture())
        probe.expectMessage(Permit.Granted)
      }

      // The 6th must NOT be granted within the deadline. If the cap
      // hadn't applied, the bucket would still have ~4 tokens left
      // from the persisted-9 count and would grant this one too.
      val nearDeadline = System.currentTimeMillis() + 100
      limiter2 ! Acquire(probe.ref, expiresAtMillis = nearDeadline)
      // Acquire queues (no tokens), waiter expires at +100ms, Drain
      // fires at ~+1s (rate=1/s) and replies Expired to the head.
      probe.expectMessage(3.seconds, Permit.Expired)
    }
  }
}
