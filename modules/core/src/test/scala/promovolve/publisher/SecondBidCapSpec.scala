package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The applied floor, site-wide and per category: with two or more bidders
 * it never exceeds the second bid × 0.99, so the top two always clear;
 * it never drops below the publisher's minimum.
 */
class SecondBidCapSpec extends AnyWordSpec with Matchers {

  "capAtSecondBid" should {
    "pull a competitive floor under the second bid" in {
      SiteEntity.capAtSecondBid(floor = 4.95, bidders = 2, secondBid = 2.0, minFloor = 0.5) shouldBe 1.98 +- 1e-9
    }
    "leave a floor already under the second bid alone" in {
      SiteEntity.capAtSecondBid(floor = 1.5, bidders = 3, secondBid = 2.0, minFloor = 0.5) shouldBe 1.5
    }
    "not cap a monopoly or an unknown second bid" in {
      SiteEntity.capAtSecondBid(floor = 4.95, bidders = 1, secondBid = 0.0, minFloor = 0.5) shouldBe 4.95
      SiteEntity.capAtSecondBid(floor = 4.95, bidders = 2, secondBid = 0.0, minFloor = 0.5) shouldBe 4.95
    }
    "never go below the publisher minimum" in {
      SiteEntity.capAtSecondBid(floor = 4.95, bidders = 2, secondBid = 0.3, minFloor = 0.5) shouldBe 0.5
    }
  }
}
