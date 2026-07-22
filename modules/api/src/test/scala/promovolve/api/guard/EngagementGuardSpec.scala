package promovolve.api.guard

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.fraud.Suspect

import EngagementGuard.{ onClick, onCta, Record }

/**
 * The pure chain/timing decision core behind the sharded
 * EngagementGuard (fraud Layer 1). Floor = 100ms in these cases.
 */
class EngagementGuardSpec extends AnyWordSpec with Matchers {

  private val floor = 100L

  "onClick" should {

    "mark chain when no impression was recorded" in {
      val (rec, reason) = onClick(None, atMs = 5000, floorMs = floor)
      reason shouldBe Some(Suspect.Chain)
      rec.impAtMs shouldBe 5000 // the click seeds the record so a cta still resolves
      rec.clickAtMs shouldBe Some(5000)
    }

    "mark timing when the click is sub-human after the impression" in {
      val prior = Some(Record(impAtMs = 1000, clickAtMs = None))
      val (rec, reason) = onClick(prior, atMs = 1050, floorMs = floor) // 50ms < 100ms
      reason shouldBe Some(Suspect.Timing)
      rec.clickAtMs shouldBe Some(1050)
    }

    "pass a human-paced click" in {
      val prior = Some(Record(impAtMs = 1000, clickAtMs = None))
      val (rec, reason) = onClick(prior, atMs = 3000, floorMs = floor) // 2s
      reason shouldBe None
      rec.clickAtMs shouldBe Some(3000)
    }

    "treat the floor boundary as sub-human (strictly-less passes)" in {
      val prior = Some(Record(1000, None))
      onClick(prior, atMs = 1100, floorMs = floor)._2 shouldBe None // exactly 100ms → OK
      onClick(prior, atMs = 1099, floorMs = floor)._2 shouldBe Some(Suspect.Timing)
    }

    "keep the earliest click on a duplicate" in {
      val prior = Some(Record(1000, Some(3000)))
      val (rec, _) = onClick(prior, atMs = 4000, floorMs = floor)
      rec.clickAtMs shouldBe Some(3000)
    }
  }

  "onCta" should {

    "mark chain when nothing preceded" in {
      onCta(None, atMs = 5000, floorMs = floor) shouldBe Some(Suspect.Chain)
    }

    "mark chain when there was an impression but no click" in {
      onCta(Some(Record(1000, None)), atMs = 5000, floorMs = floor) shouldBe Some(Suspect.Chain)
    }

    "mark timing when the cta is sub-human after the click" in {
      onCta(Some(Record(1000, Some(3000))), atMs = 3050, floorMs = floor) shouldBe Some(Suspect.Timing)
    }

    "pass a human-paced cta" in {
      onCta(Some(Record(1000, Some(3000))), atMs = 5000, floorMs = floor) shouldBe None
    }
  }
}
