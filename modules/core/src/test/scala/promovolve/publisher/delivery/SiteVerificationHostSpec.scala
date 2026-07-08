package promovolve.publisher.delivery

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.publisher.SiteEntity.{
  canonicalHost, dnsRecordMatches, dnsVerificationName, isBlockedRedirectTarget,
  verificationCandidates, verificationRecord,
}
import promovolve.publisher.delivery.AdServer.hostMatches

/** Pure-function coverage for the WordPress-friendly host-verification
  * changes: HTTPS-first fetch candidates, the SSRF redirect guard, and
  * serve-time `www.` canonicalization (the mechanism behind AdServer's
  * `hostOk` match). The live HTTP fetch + redirect loop stays
  * integration-only; these pin the decision logic it hangs off.
  */
class SiteVerificationHostSpec extends AnyWordSpec with Matchers {

  "verificationCandidates" should {
    "try HTTPS before HTTP for a public host" in {
      verificationCandidates("example.com") shouldBe List(
        "https://example.com/.well-known/promovolve.txt",
        "http://example.com/.well-known/promovolve.txt",
      )
    }
    "preserve a non-standard port in every candidate" in {
      verificationCandidates("example.com:8443") shouldBe List(
        "https://example.com:8443/.well-known/promovolve.txt",
        "http://example.com:8443/.well-known/promovolve.txt",
      )
    }
    "stay HTTP-only for loopback / LAN / .local dev hosts" in {
      verificationCandidates("localhost:8888") shouldBe List(
        "http://localhost:8888/.well-known/promovolve.txt")
      verificationCandidates("192.168.86.242:8888") shouldBe List(
        "http://192.168.86.242:8888/.well-known/promovolve.txt")
      verificationCandidates("mybox.local") shouldBe List(
        "http://mybox.local/.well-known/promovolve.txt")
    }
  }

  "isBlockedRedirectTarget" should {
    "block loopback, RFC-1918, link-local, and unspecified literals" in {
      isBlockedRedirectTarget("127.0.0.1") shouldBe true
      isBlockedRedirectTarget("localhost") shouldBe true
      isBlockedRedirectTarget("10.1.2.3") shouldBe true
      isBlockedRedirectTarget("192.168.0.5") shouldBe true
      isBlockedRedirectTarget("172.16.0.1") shouldBe true
      isBlockedRedirectTarget("169.254.169.254") shouldBe true // cloud metadata
      isBlockedRedirectTarget("0.0.0.0") shouldBe true
    }
    "allow ordinary public hosts" in {
      isBlockedRedirectTarget("example.com") shouldBe false
      isBlockedRedirectTarget("www.example.com") shouldBe false
      // 172.x outside the private 16–31 range is public
      isBlockedRedirectTarget("172.15.0.1") shouldBe false
      isBlockedRedirectTarget("172.32.0.1") shouldBe false
    }
    "ignore an appended port when classifying" in {
      isBlockedRedirectTarget("127.0.0.1:9000") shouldBe true
    }
  }

  "canonicalHost" should {
    "strip a single leading www. and lowercase" in {
      canonicalHost("www.example.com") shouldBe "example.com"
      canonicalHost("WWW.Example.COM") shouldBe "example.com"
    }
    "make www and apex match each other" in {
      canonicalHost("www.example.com") shouldBe canonicalHost("example.com")
    }
    "preserve the port" in {
      canonicalHost("www.example.com:8080") shouldBe "example.com:8080"
    }
    "not strip non-www subdomains or a bare 'www' label" in {
      canonicalHost("blog.example.com") shouldBe "blog.example.com"
      canonicalHost("wwwexample.com") shouldBe "wwwexample.com"
    }
  }

  "hostMatches (serve-time gate)" should {
    val verified = Some("example.com")

    "accept an exact host" in {
      hostMatches("https://example.com/some/article", verified) shouldBe true
    }
    "accept the www variant of a verified apex (and vice-versa)" in {
      hostMatches("https://www.example.com/post", verified) shouldBe true
      hostMatches("https://example.com/post", Some("www.example.com")) shouldBe true
    }
    "reject a different registrable domain" in {
      hostMatches("https://evil.com/post", verified) shouldBe false
    }
    "reject an unrelated subdomain (only www is canonicalized)" in {
      hostMatches("https://blog.example.com/post", verified) shouldBe false
    }
    "reject when no host has been verified yet" in {
      hostMatches("https://example.com/post", None) shouldBe false
    }
    "relax the port only between two private/LAN hosts" in {
      hostMatches("http://localhost:8888/page", Some("localhost")) shouldBe true
      hostMatches("http://192.168.1.5:9000/page", Some("192.168.1.5")) shouldBe true
      // a public host still needs its port to line up via exact canonical match
      hostMatches("http://example.com:8888/page", Some("example.com")) shouldBe false
    }
  }

  "DNS verification helpers" should {
    "underscore-prefix the canonical host and strip any port" in {
      dnsVerificationName("example.com") shouldBe "_promovolve.example.com"
      dnsVerificationName("www.example.com") shouldBe "_promovolve.example.com"
      dnsVerificationName("blog.example.com") shouldBe "_promovolve.blog.example.com"
      dnsVerificationName("Example.com:8443") shouldBe "_promovolve.example.com"
    }
    "share one record between www and apex" in {
      dnsVerificationName("www.example.com") shouldBe dnsVerificationName("example.com")
    }
    "expect the same record value as the .well-known file" in {
      verificationRecord("abc-123") shouldBe "promovolve-site-verification=abc-123"
    }
    "match a TXT record carrying the token, quoted or bare" in {
      val expected = verificationRecord("tok9")
      dnsRecordMatches(List("promovolve-site-verification=tok9"), expected) shouldBe true
      dnsRecordMatches(List("\"promovolve-site-verification=tok9\""), expected) shouldBe true
      // ignores unrelated TXT records alongside it (SPF, other verifications)
      dnsRecordMatches(List("v=spf1 -all", "\"promovolve-site-verification=tok9\""), expected) shouldBe true
    }
    "reject when no TXT record carries the token" in {
      val expected = verificationRecord("tok9")
      dnsRecordMatches(Nil, expected) shouldBe false
      dnsRecordMatches(List("v=spf1 -all", "promovolve-site-verification=WRONG"), expected) shouldBe false
    }
  }
}
