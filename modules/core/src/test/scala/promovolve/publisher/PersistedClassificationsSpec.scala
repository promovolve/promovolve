package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*
import promovolve.auction.AuctioneerEntity.AdSlotSpec

/**
 * Covers the pure pieces of the persist-page-classifications change:
 * State.withClassification eviction semantics and PersistedSlot ↔
 * AdSlotSpec round-trip. The full SiteEntity recovery → AuctioneerEntity
 * replay loop needs the live cluster; that's verified by run-time
 * inspection per the project note.
 */
class PersistedClassificationsSpec extends AnyWordSpec with Matchers {

  private val siteId = SiteId("test-site")

  private def entry(ts: Long): SiteEntity.ClassificationEntry =
    SiteEntity.ClassificationEntry(
      categories = Map("IAB1" -> 0.8),
      slots = Vector(SiteEntity.PersistedSlot(
        slotId = "slot-1",
        width = 300,
        height = 250,
        declaredSizes = Vector(SiteEntity.PersistedAdSize(300, 250)),
        prior = None,
        floorOverride = None
      )),
      classifiedAt = ts
    )

  "State.withClassification" should {

    "insert a new entry below the cap" in {
      val s = SiteEntity.State.empty(siteId)
      val s2 = s.withClassification("https://a", entry(100L))
      s2.pageClassifications.keySet shouldBe Set("https://a")
      s2.pageClassifications("https://a").classifiedAt shouldBe 100L
    }

    "replace an existing entry without evicting anything" in {
      val s = SiteEntity.State.empty(siteId)
        .withClassification("https://a", entry(100L))
        .withClassification("https://a", entry(200L))
      s.pageClassifications.keySet shouldBe Set("https://a")
      s.pageClassifications("https://a").classifiedAt shouldBe 200L
    }

    "evict the oldest entry when inserting a new URL at the cap" in {
      // Build a state pre-loaded to the cap with synthetic urls
      // numbered by classifiedAt (1, 2, ..., cap). The oldest is url-1.
      val cap = SiteEntity.MaxPersistedClassifications
      val full = (1 to cap).foldLeft(SiteEntity.State.empty(siteId)) { (s, i) =>
        s.withClassification(s"https://url-$i", entry(i.toLong))
      }
      full.pageClassifications.size shouldBe cap

      val s2 = full.withClassification("https://new", entry((cap + 1).toLong))
      s2.pageClassifications.size shouldBe cap
      s2.pageClassifications.contains("https://url-1") shouldBe false
      s2.pageClassifications.contains("https://new") shouldBe true
    }

    "not evict when an existing URL is updated at the cap" in {
      val cap = SiteEntity.MaxPersistedClassifications
      val full = (1 to cap).foldLeft(SiteEntity.State.empty(siteId)) { (s, i) =>
        s.withClassification(s"https://url-$i", entry(i.toLong))
      }
      val s2 = full.withClassification("https://url-1", entry(99999L))
      s2.pageClassifications.size shouldBe cap
      s2.pageClassifications("https://url-1").classifiedAt shouldBe 99999L
    }
  }

  "State.prunedToSeenSlots" should {

    def stateWithSlots(ids: String*): SiteEntity.State =
      SiteEntity.State.empty(siteId).withConfig(SiteEntity.SiteConfig(
        publisherId = PublisherId("pub"),
        domain = "example.com",
        seedUrl = "https://example.com/",
        cronSchedule = "0 0 2 * * ?",
        maxDepth = 3,
        concurrency = 1,
        hostRegex = ".*",
        targetElements = Nil,
        taxonomyIds = Set.empty,
        slots = ids.map(id => SiteEntity.AdSlotConfig(id, 300, 250)).toList
      ))

    "drop slots not seen this crawl and report them" in {
      val (pruned, removed) =
        stateWithSlots("a", "b", "c").prunedToSeenSlots(Set("a", "c"))
      removed shouldBe List("b")
      pruned.config.get.slots.map(_.slotId) shouldBe List("a", "c")
    }

    "be a no-op when every slot was seen" in {
      val s = stateWithSlots("a", "b")
      val (pruned, removed) = s.prunedToSeenSlots(Set("a", "b", "x"))
      removed shouldBe Nil
      pruned shouldBe s
    }

    "prune everything when a clean crawl saw no slots" in {
      val (pruned, removed) = stateWithSlots("a", "b").prunedToSeenSlots(Set.empty)
      removed shouldBe List("a", "b")
      pruned.config.get.slots shouldBe Nil
    }

    "preserve floor overrides on surviving slots" in {
      val s = SiteEntity.State.empty(siteId).withConfig(SiteEntity.SiteConfig(
        publisherId = PublisherId("pub"), domain = "example.com",
        seedUrl = "https://example.com/", cronSchedule = "0 0 2 * * ?",
        maxDepth = 3, concurrency = 1, hostRegex = ".*", targetElements = Nil,
        taxonomyIds = Set.empty,
        slots = List(
          SiteEntity.AdSlotConfig("keep", 300, 250, floorOverride = Some(CPM(3.5))),
          SiteEntity.AdSlotConfig("drop", 728, 90)
        )
      ))
      val (pruned, removed) = s.prunedToSeenSlots(Set("keep"))
      removed shouldBe List("drop")
      pruned.config.get.slots.map(_.slotId) shouldBe List("keep")
      pruned.config.get.slots.head.floorOverride shouldBe Some(CPM(3.5))
    }

    "no-op on a state with no config" in {
      val s = SiteEntity.State.empty(siteId)
      val (pruned, removed) = s.prunedToSeenSlots(Set("a"))
      removed shouldBe Nil
      pruned shouldBe s
    }
  }

  "PersistedSlot ↔ AdSlotSpec" should {

    "round-trip a minimal slot" in {
      val original = AdSlotSpec(
        slotId = SlotId("slot-A"),
        declaredSizes = List(AdSize(300, 250), AdSize(336, 280)),
        computedSize = AdSize(300, 250)
      )
      val persisted = SiteEntity.PersistedSlot.from(original)
      persisted.slotId shouldBe "slot-A"
      persisted.width shouldBe 300
      persisted.height shouldBe 250
      persisted.declaredSizes shouldBe Vector(
        SiteEntity.PersistedAdSize(300, 250),
        SiteEntity.PersistedAdSize(336, 280)
      )

      import SiteEntity.toAdSlotSpec
      val restored = persisted.toAdSlotSpec
      restored.slotId shouldBe original.slotId
      restored.computedSize shouldBe original.computedSize
      restored.declaredSizes shouldBe original.declaredSizes
      restored.prior shouldBe None
      restored.floorOverride shouldBe None
    }

    "round-trip a slot with prior + floor override" in {
      val original = AdSlotSpec(
        slotId = SlotId("slot-B"),
        declaredSizes = List(AdSize(728, 90)),
        computedSize = AdSize(728, 90),
        prior = Some(SiteEntity.SlotPrior(
          qualityScore = 0.82,
          aboveFold = true,
          region = "article"
        )),
        floorOverride = Some(CPM(2.75))
      )
      import SiteEntity.toAdSlotSpec
      val restored = SiteEntity.PersistedSlot.from(original).toAdSlotSpec
      restored.prior shouldBe original.prior
      restored.floorOverride shouldBe original.floorOverride
    }
  }
}
