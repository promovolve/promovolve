package promovolve.fraud

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class IpClassifierSpec extends AnyWordSpec with Matchers {

  import IpClassifier.IpClass

  // Fixture mirrors the iptoasn combined TSV shape:
  // start \t end \t asn \t country \t description
  private val tsv = Seq(
    "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET",
    "5.9.0.0\t5.9.255.255\t24940\tDE\tHETZNER-AS", // curated datacenter ASN
    "10.100.0.0\t10.100.0.255\t0\tNone\tNot routed", // asn 0 → skipped
    "62.0.0.0\t62.0.255.255\t64500\tGB\tSHINY VPS HOSTING LTD", // name heuristic
    "93.184.216.0\t93.184.216.255\t64501\tUS\tEXAMPLE-RESIDENTIAL-ISP",
    "2001:db8::\t2001:db8::ffff\t24940\tDE\tHETZNER-AS",
    "2a00:100::\t2a00:100::ffff\t64502\tFR\tSOME EYEBALL NETWORK"
  ).mkString("\n")

  private val db =
    IpClassifier.load(new ByteArrayInputStream(tsv.getBytes(StandardCharsets.UTF_8)))

  "IpClassifier" should {

    "classify a curated hosting ASN as Datacenter (v4)" in {
      db.classify("5.9.12.34") shouldBe IpClass.Datacenter
    }

    "classify by the hosting-name heuristic" in {
      db.classify("62.0.100.1") shouldBe IpClass.Datacenter
    }

    "classify an eyeball network as Residential" in {
      db.classify("93.184.216.34") shouldBe IpClass.Residential
    }

    "classify range boundaries inclusively" in {
      db.classify("5.9.0.0") shouldBe IpClass.Datacenter
      db.classify("5.9.255.255") shouldBe IpClass.Datacenter
    }

    "return Unknown for gaps, unrouted space, and garbage" in {
      db.classify("4.4.4.4") shouldBe IpClass.Unknown // gap between ranges
      db.classify("10.100.0.7") shouldBe IpClass.Unknown // asn 0 row skipped
      db.classify("not-an-ip") shouldBe IpClass.Unknown
      db.classify("promovolve.example") shouldBe IpClass.Unknown // hostname: no DNS lookup
      db.classify("") shouldBe IpClass.Unknown
    }

    "classify IPv6 ranges" in {
      db.classify("2001:db8::1") shouldBe IpClass.Datacenter
      db.classify("2a00:100::42") shouldBe IpClass.Residential
      db.classify("2a01:beef::1") shouldBe IpClass.Unknown
    }

    "fail open when empty" in {
      IpClassifier.empty.classify("5.9.12.34") shouldBe IpClass.Unknown
    }
  }

  "countryOf" should {

    // The country column was parsed and discarded from the day this loader
    // was written. Tier 3 of docs/design/GEOGRAPHIC_CONTEXT.md keeps it —
    // as a per-site aggregate only, never against an event.
    "resolve the country for a routable v4 address" in {
      db.countryOf("93.184.216.34") shouldBe Some("US")
      db.countryOf("5.9.1.1") shouldBe Some("DE")
    }

    "resolve the country for a routable v6 address" in {
      db.countryOf("2a00:100::5") shouldBe Some("FR")
    }

    "say nothing for an address outside every range" in {
      db.countryOf("203.0.113.1") shouldBe None
    }

    "say nothing for a non-address" in {
      db.countryOf("") shouldBe None
      db.countryOf("example.com") shouldBe None
    }

    // iptoasn writes the literal string "None" for ranges it cannot
    // attribute. Passing that through would invent a country named None.
    "never report a country named None" in {
      val tsvWithNone =
        "8.8.8.0\t8.8.8.255\t15169\tNone\tGOOGLE"
      val d = IpClassifier.load(
        new ByteArrayInputStream(tsvWithNone.getBytes(StandardCharsets.UTF_8)))
      d.countryOf("8.8.8.8") shouldBe None
    }

    "report nothing from the empty database" in {
      IpClassifier.empty.countryOf("93.184.216.34") shouldBe None
    }
  }
}
