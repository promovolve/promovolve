package promovolve.common

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.*

/**
 * Pure tests for FoldToken — the HMAC-signed credential that authenticates
 * a fold gesture against a specific auction win. These cover the
 * mint/verify roundtrip and every documented Left(reason) branch.
 */
class FoldTokenSpec extends AnyWordSpec with Matchers {

  private val secret = "test-secret-bytes-min-32-bytes-long".getBytes("UTF-8")
  private val otherSecret = "another-secret-also-32-bytes-long-x".getBytes("UTF-8")

  private val pub = "publisher-1"
  private val url = "https://example.com/article"
  private val slot = "leader-top"
  private val cid = "ad_7f3a9b"
  private val ver = 1234567890L
  private val camp = "camp-42"
  private val adv = "adv-7"

  "FoldToken.mint then verify" should {

    "round-trip with the same secret and recover all canonical fields" in {
      val token = FoldToken.mint(pub, url, slot, cid, ver, camp, adv, secret)
      val result = FoldToken.verify(token, slot, cid, secret)

      result.isRight shouldBe true
      val ctx = result.toOption.get
      ctx.pub shouldBe pub
      ctx.url shouldBe url
      ctx.slot shouldBe slot
      ctx.cid shouldBe cid
      ctx.ver shouldBe ver
      ctx.camp shouldBe camp
      ctx.adv shouldBe adv
      ctx.nonce should not be empty
    }

    "produce different tokens on each mint (nonce uniqueness)" in {
      val a = FoldToken.mint(pub, url, slot, cid, ver, camp, adv, secret)
      val b = FoldToken.mint(pub, url, slot, cid, ver, camp, adv, secret)
      (a should not).equal(b)
    }
  }

  "FoldToken.verify" should {

    "reject a token signed with a different secret" in {
      val token = FoldToken.mint(pub, url, slot, cid, ver, camp, adv, secret)
      FoldToken.verify(token, slot, cid, otherSecret) shouldBe Left("bad_signature")
    }

    "reject when the expected slot doesn't match the token's slot" in {
      val token = FoldToken.mint(pub, url, slot, cid, ver, camp, adv, secret)
      FoldToken.verify(token, "different-slot", cid, secret) shouldBe Left("slot_mismatch")
    }

    "reject when the expected creative doesn't match the token's cid" in {
      val token = FoldToken.mint(pub, url, slot, cid, ver, camp, adv, secret)
      FoldToken.verify(token, slot, "different-creative", secret) shouldBe Left("creative_mismatch")
    }

    "reject a token older than the 30-minute freshness window" in {
      val mintedAt = 1_700_000_000_000L
      // 31 minutes after mint — outside the 30-min skew window.
      val verifyAt = mintedAt + 31.minutes.toMillis
      val token = FoldToken.mint(pub, url, slot, cid, ver, camp, adv, secret, mintedAt)
      FoldToken.verify(token, slot, cid, secret, verifyAt) shouldBe Left("stale")
    }

    "accept a token at the edge of the 30-minute freshness window" in {
      val mintedAt = 1_700_000_000_000L
      // 29 minutes — still within the window.
      val verifyAt = mintedAt + 29.minutes.toMillis
      val token = FoldToken.mint(pub, url, slot, cid, ver, camp, adv, secret, mintedAt)
      FoldToken.verify(token, slot, cid, secret, verifyAt).isRight shouldBe true
    }

    "reject a token with no period separator (bad_format)" in {
      FoldToken.verify("nopayloadnoperiodnotmac", slot, cid, secret) shouldBe Left("bad_format")
    }

    "reject a token whose payload doesn't decode to the right number of fields" in {
      // Manually build a token whose payload has only 3 segments instead of 9.
      val malformedPayload = java.util.Base64.getUrlEncoder.withoutPadding
        .encodeToString("only|three|segments".getBytes("UTF-8"))
      val fakeToken = s"$malformedPayload.deadbeef"
      FoldToken.verify(fakeToken, slot, cid, secret) shouldBe Left("bad_payload")
    }

    "reject a token whose ver field isn't a number" in {
      val malformedPayload = java.util.Base64.getUrlEncoder.withoutPadding
        .encodeToString(s"$pub|$url|$slot|$cid|notanumber|123|$camp|$adv|nonceXYZ".getBytes("UTF-8"))
      val fakeToken = s"$malformedPayload.deadbeef"
      FoldToken.verify(fakeToken, slot, cid, secret) shouldBe Left("bad_payload")
    }

    "reject a token whose payload is not valid base64url (bad_payload)" in {
      val fakeToken = "***not-base64***.deadbeef"
      FoldToken.verify(fakeToken, slot, cid, secret) shouldBe Left("bad_payload")
    }

    "reject when the camp/adv segments are tampered after signing" in {
      // Forge a token by mutating the payload but keeping the original HMAC.
      // The HMAC was computed over the original camp/adv, so the new payload
      // should fail signature verification.
      val good = FoldToken.mint(pub, url, slot, cid, ver, camp, adv, secret)
      val Array(_, mac) = good.split('.'): @unchecked
      val now = FoldToken.nowBucket()
      val forged = java.util.Base64.getUrlEncoder.withoutPadding
        .encodeToString(s"$pub|$url|$slot|$cid|$ver|$now|attacker-camp|attacker-adv|nonce".getBytes("UTF-8"))
      FoldToken.verify(s"$forged.$mac", slot, cid, secret) shouldBe Left("bad_signature")
    }
  }

  "FoldToken.fresh" should {

    "accept buckets within +/- 30 buckets of now" in {
      val nowMs = 1_700_000_000_000L
      val nowB = FoldToken.nowBucket(nowMs)
      FoldToken.fresh(nowB, nowMs) shouldBe true
      FoldToken.fresh(nowB - 29, nowMs) shouldBe true
      FoldToken.fresh(nowB + 29, nowMs) shouldBe true
    }

    "reject buckets outside the window" in {
      val nowMs = 1_700_000_000_000L
      val nowB = FoldToken.nowBucket(nowMs)
      FoldToken.fresh(nowB - 31, nowMs) shouldBe false
      FoldToken.fresh(nowB + 31, nowMs) shouldBe false
    }
  }
}
