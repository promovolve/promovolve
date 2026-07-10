package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{ AdvertiserId, CPM, CampaignId, CategoryId, CreativeId, SlotId }
import promovolve.publisher.{ CDNPath, CandidateView, MimeType }
import promovolve.publisher.delivery.Protocol.{ BatchSlotOutcome, DogearOutcome }

/**
 * Pure tests for the batch serve path's accounting helpers, extracted so the
 * two behaviors this branch fixes are pinned without spinning up the actor:
 *
 *  - `impressedCreatives` — which served winners record a per-creative
 *    impression: every filled slot EXCEPT honored dog-ear pins. Regression for
 *    the dead-Thompson bug (the batch path — the only production serve path —
 *    never recorded impressions, so every creative scored cold forever).
 *  - `reclassifyInMs` — the freshness token honours the publisher's
 *    content-recency window. Regression for the window once hardcoded to 0
 *    downstream, which silently forced the 48h default.
 */
class AdServerServeAccountingSpec extends AnyWordSpec with Matchers {

  private def cand(cid: String): CandidateView =
    CandidateView(
      creativeId = CreativeId(cid),
      campaignId = CampaignId(s"camp-$cid"),
      advertiserId = AdvertiserId(s"adv-$cid"),
      assetUrl = CDNPath(s"/assets/$cid.png"),
      mime = MimeType.imagePng,
      width = 300,
      height = 250,
      category = CategoryId("cat-test"),
      cpm = CPM(1.0),
      classifiedAtMs = 0L,
      categoryScore = 0.5
    )

  private def slot(
      id: String,
      winner: Option[String],
      honoredPin: Boolean = false,
      dogear: Boolean = false
  ): BatchSlotOutcome =
    BatchSlotOutcome(
      slotId = SlotId(id),
      winner = winner.map(cand),
      dogear = if (dogear || honoredPin) Some(DogearOutcome(honored = honoredPin)) else None
    )

  "AdServer.impressedCreatives" should {
    "impress every filled slot's winner" in {
      val outcomes = Vector(slot("s1", Some("a")), slot("s2", Some("b")))
      AdServer.impressedCreatives(outcomes) shouldBe Vector(CreativeId("a"), CreativeId("b"))
    }

    "skip unfilled slots (winner = None)" in {
      val outcomes = Vector(slot("s1", None), slot("s2", Some("b")))
      AdServer.impressedCreatives(outcomes) shouldBe Vector(CreativeId("b"))
    }

    "NOT impress an honored dog-ear pin (it stays learning-silent)" in {
      // A pin re-encounter that was honored must not teach CTR — matches the
      // billing/CTR treatment in LearningEventLog.
      val outcomes = Vector(slot("s1", Some("pinned"), honoredPin = true), slot("s2", Some("normal")))
      AdServer.impressedCreatives(outcomes) shouldBe Vector(CreativeId("normal"))
    }

    "impress a winner whose dog-ear fell through (honored = false)" in {
      val outcomes = Vector(slot("s1", Some("a"), dogear = true)) // DogearOutcome(honored = false)
      AdServer.impressedCreatives(outcomes) shouldBe Vector(CreativeId("a"))
    }

    "be empty when nothing filled" in {
      AdServer.impressedCreatives(Vector(slot("s1", None))) shouldBe empty
    }
  }

  "AdServer.reclassifyInMs" should {
    val now = 1_000_000_000_000L
    val oneHour = 3600000L

    "return 0 for a never-classified page (cold)" in {
      AdServer.reclassifyInMs(classifiedAtMs = None, recencyWindowMs = oneHour, now = now) shouldBe 0L
    }

    "be > 0 (fresh) when classified within the recency window" in {
      // classified 10 min ago, window 1h → ~50 min of freshness left.
      AdServer.reclassifyInMs(Some(now - 600000L), recencyWindowMs = oneHour, now = now) should be > 0L
    }

    "be <= 0 (stale) when classified longer ago than the recency window" in {
      // classified 2h ago, window 1h → aged out.
      AdServer.reclassifyInMs(Some(now - 2 * oneHour), recencyWindowMs = oneHour, now = now) should be <= 0L
    }

    "respect the publisher's recency window, not the 48h default (the threading fix)" in {
      // Regression: the window was hardcoded to 0 downstream, so a page
      // classified 3h ago read as fresh under the 48h default. With the
      // publisher's 1h window threaded through it is correctly stale — and
      // window = 0 still falls back to the 48h default (the old behaviour),
      // proving the value actually flows.
      val threeHoursAgo = now - 3 * oneHour
      AdServer.reclassifyInMs(Some(threeHoursAgo), recencyWindowMs = oneHour, now = now) should be <= 0L
      AdServer.reclassifyInMs(Some(threeHoursAgo), recencyWindowMs = 0L, now = now) should be > 0L
    }
  }
}
