package promovolve.common

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import promovolve.SiteId

class SiteApprovalFilterSpec extends AnyFlatSpec with Matchers {

  "Site approval filter" should "add and check sites using Cuckoo filter" in {
    var filter: Array[Byte] = Array.empty

    val site1 = SiteId("site-001")
    val site2 = SiteId("site-002")
    val site3 = SiteId("site-003")

    // Initially empty
    filter.mightContain(site1) shouldBe false
    filter.mightContain(site2) shouldBe false

    // Add site1
    filter = filter.add(site1)
    filter.mightContain(site1) shouldBe true
    filter.mightContain(site2) shouldBe false

    // Add site2
    filter = filter.add(site2)
    filter.mightContain(site1) shouldBe true
    filter.mightContain(site2) shouldBe true
    filter.mightContain(site3) shouldBe false
  }

  it should "support deletion (reversible approvals)" in {
    var filter: Array[Byte] = Array.empty

    val site1 = SiteId("site-001")
    val site2 = SiteId("site-002")

    // Add both sites
    filter = filter.add(site1)
    filter = filter.add(site2)
    filter.mightContain(site1) shouldBe true
    filter.mightContain(site2) shouldBe true

    // Remove site1 (reversal!)
    filter = filter.remove(site1)
    filter.mightContain(site1) shouldBe false  // Gone!
    filter.mightContain(site2) shouldBe true   // Still there

    // Can re-add after removal
    filter = filter.add(site1)
    filter.mightContain(site1) shouldBe true
  }

  it should "indicate Cuckoo filter supports deletion" in {
    var filter: Array[Byte] = Array.empty

    // Empty filter doesn't support deletion
    filter.supportsDeletion shouldBe false

    // After adding, it's a Cuckoo filter
    filter = filter.add(SiteId("test"))
    filter.supportsDeletion shouldBe true
  }

  it should "use 16-bit fingerprints for low error rate" in {
    var filter: Array[Byte] = Array.empty

    // Add 100 sites
    (1 to 100).foreach { i =>
      filter = filter.add(SiteId(s"site-$i"))
    }

    // All should be found
    (1 to 100).foreach { i =>
      filter.mightContain(SiteId(s"site-$i")) shouldBe true
    }

    // Check false positives on 1000 non-existent sites
    // With 32-bit fingerprints, expect 0 false positives
    var falsePositives = 0
    (101 to 1100).foreach { i =>
      if (filter.mightContain(SiteId(s"site-$i"))) falsePositives += 1
    }

    falsePositives shouldBe 0
  }

  it should "persist and restore across serialization" in {
    var filter: Array[Byte] = Array.empty

    val site1 = SiteId("persistent-site-1")
    val site2 = SiteId("persistent-site-2")

    filter = filter.add(site1)
    filter = filter.add(site2)
    filter = filter.remove(site1)

    // Simulate persistence by copying the byte array
    val restored = filter.clone()

    restored.mightContain(site1) shouldBe false
    restored.mightContain(site2) shouldBe true
  }

  it should "handle the approve then reject then un-reject flow" in {
    var approvedSites: Array[Byte] = Array.empty
    var rejectedSites: Array[Byte] = Array.empty

    val site = SiteId("test-site")

    // Step 1: Approve
    approvedSites = approvedSites.add(site)
    approvedSites.mightContain(site) shouldBe true
    rejectedSites.mightContain(site) shouldBe false

    // Step 2: Change to reject (remove from approved, add to rejected)
    approvedSites = approvedSites.remove(site)
    rejectedSites = rejectedSites.add(site)
    approvedSites.mightContain(site) shouldBe false
    rejectedSites.mightContain(site) shouldBe true

    // Step 3: Un-reject (REVERSIBLE!)
    rejectedSites = rejectedSites.remove(site)
    approvedSites.mightContain(site) shouldBe false
    rejectedSites.mightContain(site) shouldBe false

    // Step 4: Re-approve
    approvedSites = approvedSites.add(site)
    approvedSites.mightContain(site) shouldBe true
  }
}
