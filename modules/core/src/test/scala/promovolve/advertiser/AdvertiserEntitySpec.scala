package promovolve.advertiser

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.{ AdvertiserId, CreativeId, SiteId, Spend }
import promovolve.common.Timezones
import promovolve.publisher.ApprovalStatus

import java.time.Instant

/**
 * Pure-data tests for AdvertiserEntity.State helpers around the
 * site-domain blocklist. Exercises the case-class transformations
 * directly — no actor system, no DData, no persistence. The DData
 * publish + retry behavior lives in the command-handler closure and
 * is verified with an end-to-end run, not here.
 */
class AdvertiserEntitySpec extends AnyWordSpec with Matchers {

  private val advId = AdvertiserId("adv-1")
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
      val s = emptyState.blockDomains(Set("Foo.COM"))
      val info = s.toInfo
      info.advertiserId shouldBe advId
      info.siteDomainBlocklist shouldBe Set("foo.com")
    }

    "include an empty blocklist when nothing is blocked" in {
      emptyState.toInfo.siteDomainBlocklist shouldBe Set.empty[String]
    }
  }

  "isValidDomain" should {

    "accept conventional FQDNs" in {
      AdvertiserEntity.isValidDomain("example.com") shouldBe true
      AdvertiserEntity.isValidDomain("a.b.c.example.co.uk") shouldBe true
      AdvertiserEntity.isValidDomain("xn--bcher-kva.de") shouldBe true
    }

    "accept bare single-label hosts (dev convenience)" in {
      AdvertiserEntity.isValidDomain("localhost") shouldBe true
    }

    "reject empty and whitespace" in {
      AdvertiserEntity.isValidDomain("") shouldBe false
      AdvertiserEntity.isValidDomain(" ") shouldBe false
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
      AdvertiserEntity.isValidDomain("<script>") shouldBe false
      AdvertiserEntity.isValidDomain("http://example.com") shouldBe false
      AdvertiserEntity.isValidDomain("example.com/path") shouldBe false
      AdvertiserEntity.isValidDomain("user@example.com") shouldBe false
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

  "State timezone helpers (advertiser-zone budget days)" should {

    val JST = "Asia/Tokyo"
    // 15:00Z is JST midnight: t1459/t1501 straddle it within one UTC day;
    // t2359/t0001 straddle UTC midnight within one JST day.
    val t1459 = Instant.parse("2026-07-13T14:59:00Z")
    val t1501 = Instant.parse("2026-07-13T15:01:00Z")
    val t2359 = Instant.parse("2026-07-13T23:59:00Z")
    val t0001 = Instant.parse("2026-07-14T00:01:00Z")

    "needsRoll at the account zone's midnight, not UTC's (instant-based)" in {
      val jst = emptyState.copy(timezone = JST, lastResetAt = t1459)
      jst.needsRoll(t1459) shouldBe false
      jst.needsRoll(t1501) shouldBe true // crossed 00:00 JST inside one UTC day

      val jstLate = emptyState.copy(timezone = JST, lastResetAt = t2359)
      jstLate.needsRoll(t0001) shouldBe false // crossed UTC midnight only
    }

    "needsRoll at UTC midnight for the default (empty) zone" in {
      emptyState.copy(lastResetAt = t2359).needsRoll(t0001) shouldBe true
      emptyState.copy(lastResetAt = t1459).needsRoll(t1501) shouldBe false
    }

    "fall back to the day-number comparison for legacy states (lastResetAt == EPOCH)" in {
      val now = Instant.parse("2026-07-13T10:00:00Z")
      val legacySameDay = emptyState.copy(lastResetEpochDay = Timezones.localEpochDay(now, ""))
      legacySameDay.lastResetAt shouldBe Instant.EPOCH
      legacySameDay.needsRoll(now) shouldBe false
      // State.empty carries lastResetEpochDay = 0 → any modern instant rolls.
      emptyState.needsRoll(now) shouldBe true
    }

    "withTimezone stamps lastResetAt only when legacy (EPOCH) and never touches spend" in {
      val now = Instant.parse("2026-07-13T10:00:00Z")
      val legacy = emptyState.copy(
        spendToday = Spend(5.0),
        lastResetEpochDay = Timezones.localEpochDay(now, "")
      )
      val adopted = legacy.withTimezone(JST, now)
      adopted.timezone shouldBe JST
      adopted.lastResetAt shouldBe now // relabeled: current day starts "now"
      adopted.spendToday shouldBe Spend(5.0) // spend NEVER resets here
      adopted.needsRoll(now) shouldBe false // no immediate double roll
    }

    "withTimezone keeps a non-EPOCH lastResetAt (previous roll under UTC)" in {
      val rolled = emptyState
        .addSpend(Spend(7.0))
        .rollWindow(Timezones.localEpochDay(t1459, ""), t1459)
      val relabeled = rolled.withTimezone(JST, t1501)
      relabeled.timezone shouldBe JST
      relabeled.lastResetAt shouldBe t1459 // untouched
      relabeled.spendToday shouldBe Spend.zero // rollWindow reset it, not withTimezone
    }

    "rollWindow resets spend and stamps the roll instant" in {
      val s = emptyState.addSpend(Spend(9.0))
      val rolled = s.rollWindow(12345L, t1501)
      rolled.spendToday shouldBe Spend.zero
      rolled.lastResetEpochDay shouldBe 12345L
      rolled.lastResetAt shouldBe t1501
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

    val siteId = SiteId("test-site")
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

    val siteId = SiteId("test-site")
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
      val revoked = approved.withRevocation(siteId)
      revoked.isApprovedFor(siteId) shouldBe false
      revoked.isEligibleFor(siteId) shouldBe true
    }

    "NOT restore approval on un-rejection — un-flag returns the creative to PENDING" in {
      val rejected = creative.withApproval(siteId, ApprovalStatus.Rejected)
      val unrejected = rejected.withUnrejection(siteId)
      unrejected.isEligibleFor(siteId) shouldBe true // bids again (pending)
      unrejected.isApprovedFor(siteId) shouldBe false // but does not teach the floor
    }

    "track approval per site" in {
      val otherSite = SiteId("other-site")
      val approved = creative.withApproval(siteId, ApprovalStatus.Approved)
      approved.isApprovedFor(siteId) shouldBe true
      approved.isApprovedFor(otherSite) shouldBe false
    }
  }
}
