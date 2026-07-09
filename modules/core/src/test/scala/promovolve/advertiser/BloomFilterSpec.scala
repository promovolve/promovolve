package promovolve.advertiser

import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import promovolve.SiteId
import promovolve.common.{ add, mightContain, BloomFilter }

class BloomFilterSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {

  "BloomFilter" should {

    "never have false negatives" in {
      forAll(Gen.choose(100, 1000), Gen.listOfN(100, Gen.alphaNumStr)) { (expectedN, items) =>
        val filter = BloomFilter.create(expectedN, 0.01)
        val distinctItems = items.distinct.filter(_.nonEmpty)

        // Add all items
        distinctItems.foreach { item =>
          val hash = hashString(item)
          filter.add(hash)
        }

        // All added items must be found (no false negatives)
        distinctItems.foreach { item =>
          val hash = hashString(item)
          filter.maybeContains(hash) shouldBe true
        }
      }
    }

    "maintain monotonicity - once added, stays in filter" in {
      val filter = BloomFilter.create(1000, 0.01)
      val item = "test-publisher-123"
      val hash = hashString(item)

      // Not present initially
      filter.maybeContains(hash) shouldBe false

      // Add it
      filter.add(hash)
      filter.maybeContains(hash) shouldBe true

      // Add more items, original should still be present
      (1 to 100).foreach { i =>
        filter.add(hashString(s"other-$i"))
      }
      filter.maybeContains(hash) shouldBe true
    }

    "have approximate false positive rate" in {
      val expectedN = 1000
      val fpr = 0.01 // 1%
      val filter = BloomFilter.create(expectedN, fpr)

      // Add known items
      val knownItems = (1 to expectedN).map(i => s"known-$i")
      knownItems.foreach { item =>
        filter.add(hashString(item))
      }

      // Test on unknown items
      val unknownItems = (1 to 1000).map(i => s"unknown-$i")
      val falsePositives = unknownItems.count { item =>
        filter.maybeContains(hashString(item))
      }

      val observedFpr = falsePositives.toDouble / unknownItems.size

      // Allow 3x tolerance (statistical variance)
      observedFpr should be <= (fpr * 3)
    }

    "estimate cardinality within reasonable bounds" in {
      val expectedN = 500
      val filter = BloomFilter.create(expectedN, 0.01)

      // Add exactly expectedN distinct items
      val items = (1 to expectedN).map(i => s"item-$i")
      items.foreach { item =>
        filter.add(hashString(item))
      }

      val estimate = filter.estimateInsertCount

      // Should be within ±30% of actual count
      estimate should be >= (expectedN * 0.7).toInt
      estimate should be <= (expectedN * 1.3).toInt
    }

    "handle empty filter correctly" in {
      val filter = BloomFilter.create(1000, 0.01)

      filter.estimateInsertCount shouldBe 0
      filter.countSetBits shouldBe 0
      filter.maybeContains(hashString("any-item")) shouldBe false
    }

    "handle single item correctly" in {
      val filter = BloomFilter.create(1000, 0.01)
      val item = "single-item"
      val hash = hashString(item)

      filter.add(hash)
      filter.maybeContains(hash) shouldBe true
      filter.estimateInsertCount should be >= 1
      filter.countSetBits should be > 0L
    }

    "clear all bits" in {
      val filter = BloomFilter.create(1000, 0.01)

      // Add items
      (1 to 100).foreach { i =>
        filter.add(hashString(s"item-$i"))
      }
      filter.countSetBits should be > 0L

      // Clear
      filter.clear()
      filter.countSetBits shouldBe 0L
      filter.estimateInsertCount shouldBe 0
    }

    "load from array snapshot" in {
      val filter1 = BloomFilter.create(1000, 0.01)
      val items = (1 to 50).map(i => s"item-$i")
      items.foreach { item => filter1.add(hashString(item)) }

      // Take snapshot
      val snapshot = filter1.snapshotWords

      // Load into new filter
      val filter2 = BloomFilter.create(1000, 0.01)
      filter2.loadFromArray(snapshot)

      // Should contain same items
      items.foreach { item =>
        filter2.maybeContains(hashString(item)) shouldBe true
      }
    }

    // Constructor validation tested via factory method behavior
    // (private constructor not directly accessible)
  }

  "BloomFilterOps" should {

    "serialize and deserialize correctly" in {
      val items = List(SiteId("pub-123"), SiteId("pub-456"), SiteId("pub-789"))

      var serialized = Array.empty[Byte]

      // Add items sequentially
      items.foreach { pub =>
        serialized = serialized.add(pub)
      }

      // All items should be found
      items.foreach { pub =>
        serialized.mightContain(pub) shouldBe true
      }
    }

    "handle empty filter" in {
      val empty = Array.empty[Byte]
      empty.mightContain(SiteId("any-publisher")) shouldBe false
    }

    "preserve immutability - adding returns new array" in {
      val original = Array.empty[Byte].add(SiteId("pub-1"))
      val originalCopy = original.clone()

      val updated = original.add(SiteId("pub-2"))

      // Original should be unchanged
      original shouldEqual originalCopy
      // But references should be different
      updated should not be theSameInstanceAs(original)
    }

    "work with realistic publisher approval scenario" in {
      // Simulate creative approval tracking
      val creative1Approved = List(SiteId("pub-1"), SiteId("pub-2"), SiteId("pub-5"))
      val creative1Rejected = List(SiteId("pub-3"), SiteId("pub-4"))

      var approvedFilter = Array.empty[Byte]
      var rejectedFilter = Array.empty[Byte]

      // Track approvals
      creative1Approved.foreach { pub =>
        approvedFilter = approvedFilter.add(pub)
      }

      // Track rejections
      creative1Rejected.foreach { pub =>
        rejectedFilter = rejectedFilter.add(pub)
      }

      // Verify approved
      creative1Approved.foreach { pub =>
        approvedFilter.mightContain(pub) shouldBe true
      }

      // Verify rejected
      creative1Rejected.foreach { pub =>
        rejectedFilter.mightContain(pub) shouldBe true
      }

      // Verify separation (approved not in rejected, vice versa)
      creative1Approved.foreach { pub =>
        rejectedFilter.mightContain(pub) shouldBe false
      }
      creative1Rejected.foreach { pub =>
        approvedFilter.mightContain(pub) shouldBe false
      }
    }

    "handle large scale (5000 publishers)" in {
      val publishers = (1 to 5000).map(i => SiteId(f"publisher-$i%04d"))

      var filter = Array.empty[Byte]

      // Add all publishers
      publishers.foreach { pub =>
        filter = filter.add(pub, expectedInsertions = 5000, fpp = 0.0001)
      }

      // Verify all are found (no false negatives)
      val notFound = publishers.filterNot { pub =>
        filter.mightContain(pub)
      }

      notFound shouldBe empty

      // Check memory usage is reasonable (~12 KB for 5000@0.01% FPR)
      // Much smaller than Map[String, ApprovalStatus] which would be ~250 KB
      filter.length should be < 15000 // ~12 KB actual, leaves room for overhead
      filter.length should be > 10000 // Sanity check it's actually using space
    }

    "have consistent hashing - same string always hashes to same value" in {
      val pub = SiteId("consistent-publisher-id")

      val filter1 = Array.empty[Byte].add(pub)
      val filter2 = Array.empty[Byte].add(pub)

      // Both should contain the publisher
      filter1.mightContain(pub) shouldBe true
      filter2.mightContain(pub) shouldBe true

      // Serialized representations should be identical
      filter1 shouldEqual filter2
    }

    "measure observed false positive rate at 0.01%" in {
      val knownPubs = (1 to 1000).map(i => SiteId(s"known-$i"))
      val unknownPubs = (1 to 10000).map(i => SiteId(s"unknown-$i"))

      var filter = Array.empty[Byte]
      knownPubs.foreach { pub =>
        filter = filter.add(pub, expectedInsertions = 1000, fpp = 0.0001)
      }

      // Count false positives on unknown publishers
      val falsePositives = unknownPubs.count { pub =>
        filter.mightContain(pub)
      }

      val observedFpr = falsePositives.toDouble / unknownPubs.size

      // 0.01% = 0.0001, allow 3x tolerance (0.03%)
      observedFpr should be <= 0.0003

      println(s"Observed FPR: ${observedFpr * 100}% (expected: 0.01%)")
      println(s"False positives: $falsePositives out of ${unknownPubs.size} checks")
      println(s"Filter size: ${filter.length} bytes")
    }
  }

  "BloomFilter sizing" should {

    "compute correct parameters for various configurations" in {
      val configs = Table(
        ("expectedN", "fpr", "maxMemoryBytes"),
        (1000, 0.01, 2000), // 1K items @ 1% FPR
        (1000, 0.001, 3000), // 1K items @ 0.1% FPR
        (1000, 0.0001, 4000), // 1K items @ 0.01% FPR
        (5000, 0.0001, 15000), // 5K items @ 0.01% FPR
        (10000, 0.0001, 30000) // 10K items @ 0.01% FPR
      )

      forAll(configs) { (n, fpr, maxBytes) =>
        val (mBits, k) = BloomFilter.sizing(n, fpr)

        mBits should be > 0
        k should be > 0

        val memUsage = BloomFilter.memoryUsage(n, fpr)
        memUsage should be < maxBytes.toLong

        println(s"Config: n=$n fpr=$fpr => mBits=$mBits k=$k memUsage=${memUsage}bytes")
      }
    }

    "have reasonable k (hash functions) values" in {
      val (_, k1) = BloomFilter.sizing(1000, 0.01)
      val (_, k2) = BloomFilter.sizing(1000, 0.001)
      val (_, k3) = BloomFilter.sizing(1000, 0.0001)

      // Lower FPR requires more hash functions
      k1 should be < k2
      k2 should be < k3

      // But should stay reasonable (typically 7-20)
      k1 should be >= 1
      k3 should be <= 30
    }
  }

  // Helper to hash strings (matches BloomFilterOps implementation)
  private def hashString(s: String): Long = {
    var hash = 0xCBF29CE484222325L
    val bytes = s.getBytes("UTF-8")
    var i = 0
    while (i < bytes.length) {
      hash ^= (bytes(i) & 0xFF).toLong
      hash *= 0x100000001B3L
      i += 1
    }
    hash
  }
}
