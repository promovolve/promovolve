package promovolve.taxonomy

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class Iab2xTo3xMigrationSpec extends AnyWordSpec with Matchers {

  "Iab2xTo3xMigration" should {

    "load the lookup table from the bundled TSV" in {
      Iab2xTo3xMigration.isLoaded shouldBe true
      Iab2xTo3xMigration.size should be > 1000
    }

    "preserve the numeric id for tier-1 categories that survived 2.x → 3.0" in {
      Iab2xTo3xMigration.toV30("483") shouldBe Some("483")    // Sports
      Iab2xTo3xMigration.toV30("596") shouldBe Some("596")    // Technology & Computing
      Iab2xTo3xMigration.toV30("1")   shouldBe Some("1")      // Automotive
      Iab2xTo3xMigration.toV30("210") shouldBe Some("210")    // Food & Drink
    }

    "remap restructured tier-1s to their new 3.0 parents" in {
      // 2.x "Music and Audio" tier-1 → 3.0 "Entertainment" (alphanumeric id)
      Iab2xTo3xMigration.toV30("338") shouldBe Some("338")    // id-preserved (Music)
      // 2.x "Events and Attractions" tier-1 (id 150) is "Attractions" in 3.0
      Iab2xTo3xMigration.toV30("150") shouldBe Some("150")
    }

    "drop descriptive-vector legacy ids that 3.0 removed from topical content" in {
      Iab2xTo3xMigration.toV30("1000") shouldBe None  // Content Channel
      Iab2xTo3xMigration.toV30("1010") shouldBe None  // Content Type
      Iab2xTo3xMigration.toV30("1020") shouldBe None  // News (under Content Type)
      Iab2xTo3xMigration.toV30("1022") shouldBe None  // Content Media Format
      Iab2xTo3xMigration.toV30("1030") shouldBe None  // Content Language

      Iab2xTo3xMigration.resolve("1000") shouldBe Iab2xTo3xMigration.Resolution.Dropped
    }

    "pass through ids not in the table (assumed already 3.0 or unknown)" in {
      Iab2xTo3xMigration.toV30("JLBCU7") shouldBe Some("JLBCU7")  // new 3.0 tier-1
      Iab2xTo3xMigration.toV30("zzz-unknown") shouldBe Some("zzz-unknown")
      Iab2xTo3xMigration.resolve("JLBCU7") shouldBe Iab2xTo3xMigration.Resolution.Unknown
    }

    "drop descriptive-vector ids in bulk migration" in {
      val mixed = Set("483", "1000", "596", "1010", "1")
      Iab2xTo3xMigration.migrateSet(mixed) shouldBe Set("483", "596", "1")
    }
  }
}
