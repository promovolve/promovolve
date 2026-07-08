package promovolve.advertiser

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{AdvertiserId, CreativeId, SiteId}
import promovolve.publisher.ApprovalStatus

/** Pure-data tests for AdvertiserEntity.State helpers around the
  * site-domain blocklist. Exercises the case-class transformations
  * directly — no actor system, no DData, no persistence. The DData
  * publish + retry behavior lives in the command-handler closure and
  * is verified with an end-to-end run, not here.
  */
class AdvertiserEntitySpec extends AnyWordSpec with Matchers {

  private val advId  = AdvertiserId("adv-1")
  private val emptyState = AdvertiserEntity.State.empty(advId)

  "State.blockDomains" should {

    "lowercase domains as it adds them" in {
      val s = emptyState.blockDomains(Set("Foo.COM", "BAR.com"))
      s.siteDomainBlocklist shouldBe Set("foo.com", "bar.com")
    }

    "be idempotent on duplicate adds" in {
      val s = emptyState.blockDomains(Set("foo.com")).blockDomains(Set("foo.com", "FOO.COM"))
      s.siteDomainBlocklist shouldBe Set("foo.com")
    }

    "preserve previously-blocked domains" in {
      val s = emptyState.blockDomains(Set("foo.com")).blockDomains(Set("bar.com"))
      s.siteDomainBlocklist shouldBe Set("foo.com", "bar.com")
    }
  }

  "State.unblockDomains" should {

    "remove a domain regardless of input casing" in {
      val s = emptyState.blockDomains(Set("foo.com", "bar.com")).unblockDomains(Set("FOO.com"))
      s.siteDomainBlocklist shouldBe Set("bar.com")
    }

    "no-op on a domain that was never blocked" in {
      val s = emptyState.blockDomains(Set("foo.com")).unblockDomains(Set("baz.com"))
      s.siteDomainBlocklist shouldBe Set("foo.com")
    }

    "leave the blocklist empty after removing the only entry" in {
      val s = emptyState.blockDomains(Set("foo.com")).unblockDomains(Set("foo.com"))
      s.siteDomainBlocklist shouldBe emptyState.siteDomainBlocklist
      s.siteDomainBlocklist shouldBe Set.empty[String]
    }
  }

  "State.toInfo" should {

    "carry the site-domain blocklist into AdvertiserInfo" in {
      val s    = emptyState.blockDomains(Set("Foo.COM"))
      val info = s.toInfo
      info.advertiserId        shouldBe advId
      info.siteDomainBlocklist shouldBe Set("foo.com")
    }

    "include an empty blocklist when nothing is blocked" in {
      emptyState.toInfo.siteDomainBlocklist shouldBe Set.empty[String]
    }
  }

  "isValidDomain" should {

    "accept conventional FQDNs" in {
      AdvertiserEntity.isValidDomain("example.com")         shouldBe true
      AdvertiserEntity.isValidDomain("a.b.c.example.co.uk") shouldBe true
      AdvertiserEntity.isValidDomain("xn--bcher-kva.de")    shouldBe true
    }

    "accept bare single-label hosts (dev convenience)" in {
      AdvertiserEntity.isValidDomain("localhost") shouldBe true
    }

    "reject empty and whitespace" in {
      AdvertiserEntity.isValidDomain("")    shouldBe false
      AdvertiserEntity.isValidDomain(" ")   shouldBe false
    }

    "reject uppercase / non-normalized input" in {
      // Caller is expected to lowercase first; predicate is intentionally strict.
      AdvertiserEntity.isValidDomain("Example.com") shouldBe false
    }

    "reject leading / trailing dot" in {
      AdvertiserEntity.isValidDomain(".example.com") shouldBe false
      AdvertiserEntity.isValidDomain("example.com.") shouldBe false
    }

    "reject leading / trailing hyphen" in {
      AdvertiserEntity.isValidDomain("-example.com") shouldBe false
      AdvertiserEntity.isValidDomain("example.com-") shouldBe false
    }

    "reject consecutive dots" in {
      AdvertiserEntity.isValidDomain("example..com") shouldBe false
    }

    "reject HTML / scheme-style payloads" in {
      AdvertiserEntity.isValidDomain("<script>")              shouldBe false
      AdvertiserEntity.isValidDomain("http://example.com")    shouldBe false
      AdvertiserEntity.isValidDomain("example.com/path")      shouldBe false
      AdvertiserEntity.isValidDomain("user@example.com")      shouldBe false
    }

    "reject excessive length" in {
      AdvertiserEntity.isValidDomain("a" * 254) shouldBe false
    }
  }

  "normalizeDomains" should {

    "lowercase + trim + drop invalid entries silently" in {
      val input = Set("  Example.COM ", "OTHER.org", "<script>", "", "  ")
      AdvertiserEntity.normalizeDomains(input) shouldBe Set("example.com", "other.org")
    }

    "collapse case-variant duplicates" in {
      AdvertiserEntity.normalizeDomains(Set("foo.com", "FOO.com")) shouldBe Set("foo.com")
    }

    "return empty when nothing survives" in {
      AdvertiserEntity.normalizeDomains(Set("", "<bad>", "..")) shouldBe Set.empty[String]
    }
  }

  "CachedSiteDomainBlocklist" should {

    "match domains case-insensitively against its set" in {
      val c = AdvertiserEntity.CachedSiteDomainBlocklist(Set("foo.com"))
      c.contains("Foo.COM") shouldBe true
      c.contains("bar.com") shouldBe false
    }

    "expose an empty constant" in {
      AdvertiserEntity.CachedSiteDomainBlocklist.empty.domains shouldBe Set.empty[String]
    }
  }

  "Creative.withRevocation" should {

    val siteId   = SiteId("test-site")
    val creative = AdvertiserEntity.Creative(id = CreativeId("cr-1"))

    "return the creative to PENDING: still bidding (its way back into the approval queue) but no longer approved" in {
      // Revoke is a soft undo — AdServer removes it from serving AND pending
      // selections, so re-winning an auction is its only path back. Blocking
      // its bid would make revoke an accidental permanent ban. Floors are
      // safe: pending (unapproved) bids don't teach floors.
      val revoked = creative.withApproval(siteId, ApprovalStatus.Approved).withRevocation(siteId)
      revoked.isEligibleFor(siteId) shouldBe true
      revoked.isApprovedFor(siteId) shouldBe false
    }

    "re-admit the creative as approved once it is re-approved" in {
      val revoked = creative.withApproval(siteId, ApprovalStatus.Approved).withRevocation(siteId)
      val reapproved = revoked.withApproval(siteId, ApprovalStatus.Approved)
      reapproved.isApprovedFor(siteId) shouldBe true
      reapproved.isEligibleFor(siteId) shouldBe true
    }

    "only affect the revoked site, not others" in {
      val otherSite = SiteId("other-site")
      val revoked = creative
        .withApproval(siteId, ApprovalStatus.Approved)
        .withApproval(otherSite, ApprovalStatus.Approved)
        .withRevocation(siteId)
      revoked.isApprovedFor(siteId) shouldBe false
      revoked.isApprovedFor(otherSite) shouldBe true
    }
  }

  "Creative approval state (approvedSites — floor-teaching demand)" should {

    val siteId   = SiteId("test-site")
    val creative = AdvertiserEntity.Creative(id = CreativeId("cr-1"))

    "start PENDING: eligible to bid (reaches the approval queue) but NOT approved (no floor influence)" in {
      creative.isEligibleFor(siteId) shouldBe true
      creative.isApprovedFor(siteId) shouldBe false
    }

    "record approval — approved demand teaches the floor" in {
      val approved = creative.withApproval(siteId, ApprovalStatus.Approved)
      approved.isApprovedFor(siteId) shouldBe true
      approved.isEligibleFor(siteId) shouldBe true
    }

    "clear approval on rejection (flag/reject)" in {
      val approved = creative.withApproval(siteId, ApprovalStatus.Approved)
      val rejected = approved.withApproval(siteId, ApprovalStatus.Rejected)
      rejected.isApprovedFor(siteId) shouldBe false
      rejected.isEligibleFor(siteId) shouldBe false
    }

    "clear approval on revocation — revoked goes back to PENDING (still bids, no floor influence)" in {
      val approved = creative.withApproval(siteId, ApprovalStatus.Approved)
      val revoked  = approved.withRevocation(siteId)
      revoked.isApprovedFor(siteId) shouldBe false
      revoked.isEligibleFor(siteId) shouldBe true
    }

    "NOT restore approval on un-rejection — un-flag returns the creative to PENDING" in {
      val rejected   = creative.withApproval(siteId, ApprovalStatus.Rejected)
      val unrejected = rejected.withUnrejection(siteId)
      unrejected.isEligibleFor(siteId) shouldBe true   // bids again (pending)
      unrejected.isApprovedFor(siteId) shouldBe false  // but does not teach the floor
    }

    "track approval per site" in {
      val otherSite = SiteId("other-site")
      val approved  = creative.withApproval(siteId, ApprovalStatus.Approved)
      approved.isApprovedFor(siteId) shouldBe true
      approved.isApprovedFor(otherSite) shouldBe false
    }
  }
}
