package promovolve.fraud

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class RequestHygieneSpec extends AnyWordSpec with Matchers {

  private val ua = Some(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36")

  private def db =
    IpClassifier.load(new ByteArrayInputStream(
      Seq(
        "5.9.0.0\t5.9.255.255\t24940\tDE\tHETZNER-AS", // datacenter
        "93.184.216.0\t93.184.216.255\t64501\tUS\tEYEBALL-ISP" // residential
      ).mkString("\n").getBytes(StandardCharsets.UTF_8)))

  private def gate(rate: Double = 1000.0, burst: Double = 1000.0) =
    new RequestRateGate(rate, burst)

  "RequestHygiene" should {

    "mark datacenter IPs regardless of a clean UA" in {
      val h = new RequestHygiene(db, gate())
      h.classify("5.9.1.1", ua) shouldBe Some(Suspect.DatacenterAsn)
    }

    "mark bot UAs on a residential IP" in {
      val h = new RequestHygiene(db, gate())
      h.classify("93.184.216.34", Some("curl/8.4.0")) shouldBe Some(Suspect.BotUa)
    }

    "prioritise datacenter over bot UA" in {
      val h = new RequestHygiene(db, gate())
      // Both would match; datacenter is the reported reason.
      h.classify("5.9.1.1", Some("curl/8.4.0")) shouldBe Some(Suspect.DatacenterAsn)
    }

    "mark over-rate clean requests" in {
      val h = new RequestHygiene(db, gate(rate = 1.0, burst = 1.0))
      h.classify("93.184.216.34", ua) shouldBe None // first passes
      h.classify("93.184.216.34", ua) shouldBe Some(Suspect.RateCap) // second over cap
    }

    "pass a clean residential human" in {
      val h = new RequestHygiene(db, gate())
      h.classify("93.184.216.34", ua) shouldBe None
    }

    "fail open when disabled" in {
      RequestHygiene.disabled.classify("5.9.1.1", Some("curl/8.4.0")) shouldBe None
    }

    "fail open with no db (unknown IP) but still rate-gate" in {
      val h = new RequestHygiene(IpClassifier.empty, gate(rate = 1.0, burst = 1.0))
      h.classify("5.9.1.1", ua) shouldBe None // datacenter unknown → clean
      h.classify("5.9.1.1", ua) shouldBe Some(Suspect.RateCap) // rate still enforced
    }

    "swap the db at runtime" in {
      val h = new RequestHygiene(IpClassifier.empty, gate())
      h.classify("5.9.1.1", ua) shouldBe None
      h.updateDb(db)
      h.classify("5.9.1.1", ua) shouldBe Some(Suspect.DatacenterAsn)
    }
  }
}
