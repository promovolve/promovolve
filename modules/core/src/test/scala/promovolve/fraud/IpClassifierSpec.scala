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
}
