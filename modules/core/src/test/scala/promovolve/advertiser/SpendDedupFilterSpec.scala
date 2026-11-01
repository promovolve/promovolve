package promovolve.advertiser

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The spend idempotency filter must keep its false-positive rate near its
 * design value however many reservations a campaign takes in a day. A
 * false positive marks a NEW reservation as a duplicate: Reserved without
 * a charge, i.e. a free impression. A single fixed-size Bloom filter sized
 * for 50k ids gave ~2% at 100k and ~40% at 200k.
 */
class SpendDedupFilterSpec extends AnyWordSpec with Matchers {

  "the spend idempotency filter" should {
    "stay accurate far past its sized capacity" in {
      var s = CampaignEntity.EphemeralState.empty
      (0 until 200_000).foreach(i => s = s.markProcessed(s"seen-$i"))
      val fresh = 20_000
      val falsePositives = (0 until fresh).count(i => s.mightContain(s"new-$i"))
      (falsePositives.toDouble / fresh) should be < 0.001
    }

    "still catch a recent duplicate right after it rotates" in {
      var s = CampaignEntity.EphemeralState.empty
      (0 until 60_000).foreach(i => s = s.markProcessed(s"seen-$i"))
      s.mightContain("seen-59999") shouldBe true
      s.mightContain("seen-49990") shouldBe true
    }
  }
}
