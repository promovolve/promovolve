package promovolve.fraud

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RequestRateGateSpec extends AnyWordSpec with Matchers {

  "RequestRateGate" should {

    "allow the burst then mark, and refill over time" in {
      var now = 1000L
      val gate = new RequestRateGate(ratePerSec = 1.0, burst = 3.0, nowMillis = () => now)

      gate.allow("k") shouldBe true
      gate.allow("k") shouldBe true
      gate.allow("k") shouldBe true
      gate.allow("k") shouldBe false // burst exhausted

      now += 2000L // 2s at 1/s → 2 tokens back
      gate.allow("k") shouldBe true
      gate.allow("k") shouldBe true
      gate.allow("k") shouldBe false
    }

    "isolate keys" in {
      var now = 1000L
      val gate = new RequestRateGate(ratePerSec = 1.0, burst = 1.0, nowMillis = () => now)
      gate.allow("a") shouldBe true
      gate.allow("a") shouldBe false
      gate.allow("b") shouldBe true // b's bucket untouched by a
    }

    "evict idle buckets" in {
      var now = 1000L
      val gate =
        new RequestRateGate(ratePerSec = 1.0, burst = 1.0, idleEvictMillis = 5000L, nowMillis = () => now)
      gate.allow("a")
      gate.allow("b")
      gate.size shouldBe 2
      now += 70000L // past idle window AND the sweep interval
      gate.allow("c") // write triggers the sweep
      gate.size shouldBe 1
    }
  }
}
