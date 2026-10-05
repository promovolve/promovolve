package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ReachRepoSpec extends AnyWordSpec with Matchers {

  "ReachRepo.daysSince" should {
    "be Never for a browser that hadn't seen the campaign here" in {
      ReachRepo.daysSince(20_000L, None) shouldBe Some(ReachRepo.Never)
    }
    "be the gap in days, capped at Beyond" in {
      ReachRepo.daysSince(20_000L, Some(19_997L)) shouldBe Some(3)
      ReachRepo.daysSince(20_000L, Some(20_000L - 90)) shouldBe Some(90)
      ReachRepo.daysSince(20_000L, Some(20_000L - 400)) shouldBe Some(ReachRepo.Beyond)
    }
    "reject a previous day that isn't before today" in {
      ReachRepo.daysSince(20_000L, Some(20_000L)) shouldBe None
      ReachRepo.daysSince(20_000L, Some(20_001L)) shouldBe None
    }
  }

  "ReachRepo.validFresh" should {
    "accept 32 lowercase hex characters only" in {
      ReachRepo.validFresh("0123456789abcdef0123456789abcdef") shouldBe true
      ReachRepo.validFresh("0123456789ABCDEF0123456789ABCDEF") shouldBe false
      ReachRepo.validFresh("abc") shouldBe false
      ReachRepo.validFresh("'; DROP TABLE x; --0123456789abcd") shouldBe false
    }
  }
}
