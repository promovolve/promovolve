package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{AdvertiserId, CampaignId, Candidate, CategoryId, CPM, CreativeId}

/** Pure-function tests for AdServer's advertiser-side site-domain blocklist
  * filter. Covers the two helpers used at serve time:
  *   - mySiteDomainOpt: normalize the verified host into the bare-domain
  *     shape blocklists are stored as.
  *   - partitionByAdvertiserBlocklist: drop a candidate when its
  *     advertiser blocks the serving site's domain.
  */
class AdvertiserBlocklistFilterSpec extends AnyWordSpec with Matchers {

  private def candidate(advertiser: String, creative: String = "c1"): Candidate =
    Candidate(
      creativeId   = CreativeId(creative),
      campaignId   = CampaignId(s"camp-$advertiser"),
      advertiserId = AdvertiserId(advertiser),
      cpm          = CPM(1.0),
      category     = CategoryId("cat")
    )

  "mySiteDomainOpt" should {

    "return None for an unknown verified host" in {
      AdServer.mySiteDomainOpt(None) shouldBe None
    }

    "return None for an empty / whitespace-only host" in {
      AdServer.mySiteDomainOpt(Some(""))    shouldBe None
      AdServer.mySiteDomainOpt(Some("   ")) shouldBe None
    }

    "lowercase the host" in {
      AdServer.mySiteDomainOpt(Some("Example.COM")) shouldBe Some("example.com")
    }

    "strip the port if present" in {
      AdServer.mySiteDomainOpt(Some("example.com:8080")) shouldBe Some("example.com")
      AdServer.mySiteDomainOpt(Some("LOCALHOST:9000"))   shouldBe Some("localhost")
    }

    "trim surrounding whitespace before stripping" in {
      AdServer.mySiteDomainOpt(Some("  Example.com  ")) shouldBe Some("example.com")
    }
  }

  "partitionByAdvertiserBlocklist" should {

    "pass everything through when the site domain is unknown" in {
      val pool = Vector(candidate("a1"), candidate("a2"))
      val blocklists = Map(AdvertiserId("a1") -> Set("example.com"))
      val (blocked, allowed) =
        AdServer.partitionByAdvertiserBlocklist(pool, blocklists, None)
      blocked shouldBe empty
      allowed shouldBe pool
    }

    "pass everything through when no advertiser has any blocklist" in {
      val pool = Vector(candidate("a1"), candidate("a2"))
      val (blocked, allowed) =
        AdServer.partitionByAdvertiserBlocklist(pool, Map.empty, Some("example.com"))
      blocked shouldBe empty
      allowed shouldBe pool
    }

    "drop only candidates whose advertiser has blocked this domain" in {
      val good   = candidate("a-clean", "cid-good")
      val mixed  = candidate("a-blocks-other", "cid-mixed") // blocks a different domain
      val target = candidate("a-blocks-us", "cid-target")
      val pool   = Vector(good, mixed, target)
      val blocklists = Map(
        AdvertiserId("a-blocks-other") -> Set("other.com"),
        AdvertiserId("a-blocks-us")    -> Set("example.com", "another.com")
      )
      val (blocked, allowed) =
        AdServer.partitionByAdvertiserBlocklist(pool, blocklists, Some("example.com"))
      blocked.map(_.creativeId.value) shouldBe Vector("cid-target")
      allowed.map(_.creativeId.value) shouldBe Vector("cid-good", "cid-mixed")
    }

    "match exact-domain only — case-sensitive against the supplied site domain" in {
      // Caller is responsible for lowercasing site domain via mySiteDomainOpt.
      // The partition itself does NOT re-normalize blocklist entries; AdvertiserEntity
      // already stores them lowercased on persist, so case mismatches here would mean
      // a corrupt store rather than a missed filter.
      val c = candidate("a1")
      val blocklists = Map(AdvertiserId("a1") -> Set("Example.com"))
      val (blocked, _) =
        AdServer.partitionByAdvertiserBlocklist(Vector(c), blocklists, Some("example.com"))
      blocked shouldBe empty
    }

    "block multiple candidates from the same advertiser" in {
      val a = candidate("adv", "ca")
      val b = candidate("adv", "cb")
      val pool = Vector(a, b)
      val blocklists = Map(AdvertiserId("adv") -> Set("example.com"))
      val (blocked, allowed) =
        AdServer.partitionByAdvertiserBlocklist(pool, blocklists, Some("example.com"))
      blocked.map(_.creativeId.value).toSet shouldBe Set("ca", "cb")
      allowed                                shouldBe empty
    }
  }
}
