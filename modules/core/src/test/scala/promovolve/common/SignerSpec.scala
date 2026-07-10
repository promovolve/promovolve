package promovolve.common

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Pure tests for `Signer`, focused on the tracking-token binding contract.
 *
 * Tracking beacons carry `camp` / `adv` / `cpm` as query params. They MUST be
 * bound into the HMAC (via `Signer.bind`) so a client that received one valid
 * serve token can't rewrite the beacon to charge a competitor's campaign or
 * inflate the amount. These tests pin that contract at the primitive level;
 * the route-level wiring lives in ServeRoutes/TrackRoutes.
 */
class SignerSpec extends AnyWordSpec with Matchers {

  private val secret = "test-secret-bytes-min-32-bytes-long".getBytes("UTF-8")

  private val pub = "publisher-1"
  private val url = "https://example.com/article"
  private val slot = "leader-top"
  private val cid = "ad_7f3a9b"
  private val ver = 1234567890L
  private val bucket = 29000000L

  // Mirror the mint/verify sites: canonical + bound (camp, adv, cpm, rid).
  private def impToken(
      camp: Option[String],
      adv: Option[String],
      cpm: Option[String],
      rid: Option[String]
  ): String =
    Signer.hmac256(
      Signer.canonical(pub, url, slot, cid, ver, bucket, "imp") + Signer.bind(camp, adv, cpm, rid),
      secret
    )

  "Signer.bind" should {
    "render present fields as |=value and absent ones as a bare separator" in {
      Signer.bind(Some("a"), Some("b"), Some("c")) shouldBe "|=a|=b|=c"
      Signer.bind(Some("a"), None, Some("c")) shouldBe "|=a||=c"
      Signer.bind(None, None, None) shouldBe "|||"
    }

    "be order-sensitive so a field swap changes the string" in {
      Signer.bind(Some("camp"), Some("adv")) should not be
      Signer.bind(Some("adv"), Some("camp"))
    }

    "be injective: a value containing the delimiter cannot emulate two fields" in {
      // Without URL-encoding, camp="c|a" (adv absent) rendered identically to
      // camp="c", adv="a" — letting a client shift bytes across field
      // boundaries on the beacon. The encoding forbids literal `|` in values.
      Signer.bind(Some("c|a"), None) should not be Signer.bind(Some("c"), Some("a"))
    }

    "be injective: shifting a value into a neighbouring absent slot changes the string" in {
      Signer.bind(Some("x"), None) should not be Signer.bind(None, Some("x"))
    }

    "distinguish an absent field from an empty one" in {
      Signer.bind(Some("")) should not be Signer.bind(None)
    }
  }

  "an impression token binding camp/adv/cpm" should {
    val camp = Some("camp-legit")
    val adv = Some("adv-legit")
    val cpm = Some("2.5")
    val rid = Some("01H0RID")
    val good = impToken(camp, adv, cpm, rid)

    "verify against the exact same fields" in {
      Signer.safeEq(good, impToken(camp, adv, cpm, rid)) shouldBe true
    }

    "reject a campaign rewrite (spend redirection to a competitor)" in {
      Signer.safeEq(good, impToken(Some("camp-victim"), adv, cpm, rid)) shouldBe false
    }

    "reject an advertiser rewrite" in {
      Signer.safeEq(good, impToken(camp, Some("adv-victim"), cpm, rid)) shouldBe false
    }

    "reject a cpm inflation" in {
      Signer.safeEq(good, impToken(camp, adv, Some("999999"), rid)) shouldBe false
    }

    "reject dropping camp/adv/cpm entirely" in {
      Signer.safeEq(good, impToken(None, None, None, rid)) shouldBe false
    }
  }

  "Signer.safeEq" should {
    "be false for unequal lengths without leaking via early return" in {
      Signer.safeEq("abc", "abcd") shouldBe false
    }
    "be true for identical strings" in {
      Signer.safeEq("abcdef", "abcdef") shouldBe true
    }
  }
}
