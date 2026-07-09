package promovolve.common

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CuckooFilterSpec extends AnyFlatSpec with Matchers {

  "CuckooFilter" should "insert and lookup items" in {
    val filter = CuckooFilter(1000)

    filter.insert("site_A") shouldBe true
    filter.insert("site_B") shouldBe true
    filter.insert("site_C") shouldBe true

    filter.mightContain("site_A") shouldBe true
    filter.mightContain("site_B") shouldBe true
    filter.mightContain("site_C") shouldBe true
    filter.mightContain("site_D") shouldBe false
  }

  it should "support deletion (unlike Bloom filter)" in {
    val filter = CuckooFilter(1000)

    filter.insert("site_A")
    filter.insert("site_B")

    filter.mightContain("site_A") shouldBe true
    filter.mightContain("site_B") shouldBe true

    // Delete site_A
    filter.delete("site_A") shouldBe true

    // site_A is gone, site_B still there
    filter.mightContain("site_A") shouldBe false
    filter.mightContain("site_B") shouldBe true
  }

  it should "serialize and deserialize correctly" in {
    val filter = CuckooFilter(1000)
    filter.insert("site_A")
    filter.insert("site_B")
    filter.insert("site_C")

    val bytes = filter.toBytes
    val restored = CuckooFilter.fromBytes(bytes)

    restored.mightContain("site_A") shouldBe true
    restored.mightContain("site_B") shouldBe true
    restored.mightContain("site_C") shouldBe true
    restored.mightContain("site_D") shouldBe false
    restored.size shouldBe 3
  }

  it should "handle 16-bit fingerprints with low error rate" in {
    val filter = CuckooFilter.lowError(1000)

    // Insert 500 items
    (1 to 500).foreach { i =>
      filter.insert(s"site_$i") shouldBe true
    }

    // All should be found
    (1 to 500).foreach { i =>
      filter.mightContain(s"site_$i") shouldBe true
    }

    // Check false positives on 1000 non-existent items
    // With 16-bit fingerprints, expect ~0.1 false positives (1 in 10,000)
    var falsePositives = 0
    (1001 to 2000).foreach { i =>
      if (filter.mightContain(s"site_$i")) falsePositives += 1
    }

    // Should be very low (likely 0 for 1000 checks)
    falsePositives should be < 5
  }

  it should "serialize and deserialize 16-bit filter correctly" in {
    val filter = CuckooFilter.lowError(1000)
    filter.insert("test_1")
    filter.insert("test_2")
    filter.delete("test_1")

    val bytes = filter.toBytes
    val restored = CuckooFilter.fromBytes(bytes)

    restored.mightContain("test_1") shouldBe false
    restored.mightContain("test_2") shouldBe true
    restored.fingerprintBits shouldBe 16
  }

  it should "track size correctly" in {
    val filter = CuckooFilter(1000)

    filter.size shouldBe 0

    filter.insert("a")
    filter.size shouldBe 1

    filter.insert("b")
    filter.size shouldBe 2

    filter.delete("a")
    filter.size shouldBe 1

    filter.delete("b")
    filter.size shouldBe 0
  }

  it should "handle the approve/reject use case" in {
    // Simulate creative approval workflow
    val approvedSites = CuckooFilter.lowError(2000)
    val rejectedSites = CuckooFilter.lowError(2000)

    def approve(siteId: String): Unit = {
      rejectedSites.delete(siteId) // Un-reject if previously rejected
      approvedSites.insert(siteId)
    }

    def reject(siteId: String): Unit = {
      approvedSites.delete(siteId) // Un-approve if previously approved
      rejectedSites.insert(siteId)
    }

    def unreject(siteId: String): Unit = {
      rejectedSites.delete(siteId) // Reversible!
    }

    def canDeliver(siteId: String): Boolean = {
      approvedSites.mightContain(siteId) && !rejectedSites.mightContain(siteId)
    }

    // Initial state - nothing approved or rejected
    canDeliver("site_1") shouldBe false
    canDeliver("site_2") shouldBe false

    // Approve site_1
    approve("site_1")
    canDeliver("site_1") shouldBe true
    canDeliver("site_2") shouldBe false

    // Reject site_2
    reject("site_2")
    canDeliver("site_1") shouldBe true
    canDeliver("site_2") shouldBe false
    rejectedSites.mightContain("site_2") shouldBe true

    // REVERSIBLE: Un-reject site_2
    unreject("site_2")
    rejectedSites.mightContain("site_2") shouldBe false

    // Now approve site_2
    approve("site_2")
    canDeliver("site_2") shouldBe true
  }
}
