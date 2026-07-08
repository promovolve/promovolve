package promovolve.api

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

class PendingEventHubSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "PendingEventHub" should {

    "accept subscriptions and broadcast events to subscribers" in {
      val hub = testKit.spawn(PendingEventHub())
      val probe = testKit.createTestProbe[PendingEventHub.PendingEvent]()

      // Subscribe
      hub ! PendingEventHub.Subscribe("test-site", probe.ref)

      // Publish an event
      hub ! PendingEventHub.PublishPendingUpdate(
        siteId = "test-site",
        url = "https://example.com/page",
        slotId = "slot-1",
        count = 3,
        topCreativeId = "creative-123"
      )

      // Verify subscriber receives the event
      val event = probe.expectMessageType[PendingEventHub.PendingUpdated]
      event.siteId shouldBe "test-site"
      event.url shouldBe "https://example.com/page"
      event.slotId shouldBe "slot-1"
      event.count shouldBe 3
      event.topCreativeId shouldBe "creative-123"
    }

    "not broadcast events to subscribers of different sites" in {
      val hub = testKit.spawn(PendingEventHub())
      val probe = testKit.createTestProbe[PendingEventHub.PendingEvent]()

      // Subscribe to site-a
      hub ! PendingEventHub.Subscribe("site-a", probe.ref)

      // Publish to site-b
      hub ! PendingEventHub.PublishPendingUpdate(
        siteId = "site-b",
        url = "https://example.com/page",
        slotId = "slot-1",
        count = 1,
        topCreativeId = "creative-456"
      )

      // Subscriber should not receive the event
      probe.expectNoMessage()
    }

    "handle unsubscribe correctly" in {
      val hub = testKit.spawn(PendingEventHub())
      val probe = testKit.createTestProbe[PendingEventHub.PendingEvent]()

      // Subscribe
      hub ! PendingEventHub.Subscribe("test-site", probe.ref)

      // Unsubscribe
      hub ! PendingEventHub.Unsubscribe("test-site", probe.ref)

      // Publish an event
      hub ! PendingEventHub.PublishPendingUpdate(
        siteId = "test-site",
        url = "https://example.com/page",
        slotId = "slot-1",
        count = 2,
        topCreativeId = "creative-789"
      )

      // Unsubscribed probe should not receive the event
      probe.expectNoMessage()
    }

    "support multiple subscribers for the same site" in {
      val hub = testKit.spawn(PendingEventHub())
      val probe1 = testKit.createTestProbe[PendingEventHub.PendingEvent]()
      val probe2 = testKit.createTestProbe[PendingEventHub.PendingEvent]()

      // Subscribe both
      hub ! PendingEventHub.Subscribe("test-site", probe1.ref)
      hub ! PendingEventHub.Subscribe("test-site", probe2.ref)

      // Publish an event
      hub ! PendingEventHub.PublishPendingUpdate(
        siteId = "test-site",
        url = "https://example.com/page",
        slotId = "slot-1",
        count = 5,
        topCreativeId = "creative-abc"
      )

      // Both subscribers should receive the event
      probe1.expectMessageType[PendingEventHub.PendingUpdated]
      probe2.expectMessageType[PendingEventHub.PendingUpdated]
    }
  }
}
