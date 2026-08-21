package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*

/**
 * The approval boundary, pinned.
 *
 * This is the one path where a creative can reach serving without a
 * publisher having looked at it, so the rules that govern it are asserted
 * here rather than left to reading.
 *
 * It is also where docs/design/GEOGRAPHIC_CONTEXT.md's constraint lands —
 * "a regional variant must not bypass approval by being treated as an
 * already-approved creative". What protects that is IDENTITY: approval is
 * keyed per creativeId, so a variant is a distinct creative with its own
 * decision. Trust anchors are a separate, opt-in, off-by-default mechanism.
 */
class AutoApproveTrustSpec extends AnyWordSpec with Matchers {

  private def candidate(
      creativeId: String,
      campaignId: String = "camp-1",
      domain: String = "acme.com",
      placeHops: Int = 0
  ): Candidate =
    Candidate(
      creativeId = CreativeId(creativeId),
      campaignId = CampaignId(campaignId),
      advertiserId = AdvertiserId("adv-1"),
      cpm = CPM(1.0),
      category = CategoryId("483"),
      landingDomain = domain,
      placeHops = placeHops
    )

  private def partition(
      candidates: Vector[Candidate],
      enabled: Boolean = true,
      campaigns: Set[String] = Set.empty,
      domains: Set[String] = Set.empty
  ) = AdServer.partitionAutoApprovable(
    candidates, enabled, campaigns.map(CampaignId.apply), domains)

  "auto-approve" should {

    // The default posture. A publisher who has not opted in gets the
    // constraint for free: nothing skips the queue, ever.
    "queue everything when the site has not opted in" in {
      val (auto, pending) = partition(Vector(candidate("c1")), enabled = false, campaigns = Set("camp-1"))
      auto shouldBe empty
      pending should have size 1
    }

    "queue everything when no trust has been earned" in {
      val (auto, pending) = partition(Vector(candidate("c1")))
      auto shouldBe empty
      pending should have size 1
    }

    "let a trusted campaign's creative skip the queue" in {
      val (auto, _) = partition(Vector(candidate("c1")), campaigns = Set("camp-1"))
      auto.map(_.creativeId.value) shouldBe Vector("c1")
    }

    "let a trusted landing domain's creative skip the queue" in {
      val (auto, _) = partition(Vector(candidate("c1")), domains = Set("acme.com"))
      auto.map(_.creativeId.value) shouldBe Vector("c1")
    }

    "queue a creative from an untrusted campaign and domain" in {
      val (auto, pending) = partition(
        Vector(candidate("c1", campaignId = "other", domain = "elsewhere.com")),
        campaigns = Set("camp-1"), domains = Set("acme.com"))
      auto shouldBe empty
      pending should have size 1
    }
  }

  "geographic targeting" should {

    // The decision recorded in GEOGRAPHIC_CONTEXT.md step 10, asserted so it
    // is a choice rather than an oversight. Approval judges the creative's
    // content and suitability; geography changes which of the publisher's
    // pages it runs on, not what it is. A rule that auto-approved a seasonal
    // variant but queued a regional one would be arbitrary.
    "not change whether a creative may skip the queue" in {
      val near = candidate("c-near", placeHops = 0)
      val far = candidate("c-far", placeHops = 2)
      val (auto, pending) = partition(Vector(near, far), campaigns = Set("camp-1"))
      auto.map(_.creativeId.value) should contain theSameElementsAs Vector("c-near", "c-far")
      pending shouldBe empty
    }
  }

  "the identity guarantee" should {

    // The constraint the whole feature had to preserve. Trust is granted per
    // CAMPAIGN or DOMAIN, but approval is recorded per CREATIVE — so a new
    // regional variant is a new creativeId and gets its own decision. What
    // trust buys is skipping the QUEUE on an opted-in site, never inheriting
    // another creative's approval.
    "treat a regional variant as a distinct creative, not an approved one" in {
      val original = candidate("c-original")
      val variant = candidate("c-regional-variant")
      original.creativeId should not be variant.creativeId

      // With no opt-in, the variant is queued like anything else.
      val (auto, pending) = partition(Vector(variant), enabled = false, campaigns = Set("camp-1"))
      auto shouldBe empty
      pending.map(_.creativeId.value) shouldBe Vector("c-regional-variant")
    }

    // Auto-approval consumes trust, it does not widen it: the partition
    // reports which creatives skipped, and nothing here mints a new anchor.
    "return the skipping creatives so the caller records them explicitly" in {
      val (auto, _) = partition(Vector(candidate("c1"), candidate("c2")), campaigns = Set("camp-1"))
      auto.map(_.creativeId.value) should contain theSameElementsAs Vector("c1", "c2")
    }
  }
}
