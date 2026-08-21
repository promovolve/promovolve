package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.*

/**
 * Tier 2 of docs/design/GEOGRAPHIC_CONTEXT.md — the publisher's declared
 * reader audience. Covers the pure pieces: the config default that keeps
 * Jackson recovery of pre-geo snapshots working, and the edit-merge rule
 * whose None/Some(empty) distinction decides whether "remove my
 * declaration" actually removes anything.
 *
 * The SiteEntity → AuctioneerEntity → CategoryBidder push is a live-cluster
 * path; it is verified at runtime, as with PersistedClassificationsSpec.
 */
class SiteAudienceSpec extends AnyWordSpec with Matchers {

  private def config(audience: Set[String] = Set.empty): SiteEntity.SiteConfig =
    SiteEntity.SiteConfig(
      publisherId = PublisherId("pub-1"),
      domain = "example.com",
      seedUrl = "https://example.com",
      cronSchedule = "0 0 2 * * ?",
      maxDepth = 2,
      concurrency = 1,
      hostRegex = ".*",
      targetElements = Nil,
      taxonomyIds = Set("483"),
      audienceRegions = audience
    )

  "SiteConfig.audienceRegions" should {

    // Default-empty is what lets a pre-geo persisted snapshot recover
    // without a migration.
    "default to empty" in {
      config().audienceRegions shouldBe empty
    }

    "hold declared codes" in {
      config(Set("JP", "JP-14")).audienceRegions shouldBe Set("JP", "JP-14")
    }
  }

  "mergeAudienceRegions" should {

    "leave the declaration alone when the edit does not mention it" in {
      SiteEntity.mergeAudienceRegions(Set("JP"), None) shouldBe Set("JP")
    }

    // The one that matters. Collapsing Some(empty) into "no change" makes
    // clearing a declaration a silent no-op, and the site keeps receiving
    // audience-targeted demand it no longer claims.
    "clear the declaration when the publisher submits an empty set" in {
      SiteEntity.mergeAudienceRegions(Set("JP"), Some(Vector.empty)) shouldBe empty
    }

    "replace the declaration with the submitted codes" in {
      SiteEntity.mergeAudienceRegions(Set("JP"), Some(Vector("FR", "FR-IDF"))) shouldBe Set("FR", "FR-IDF")
    }

    "drop codes the vocabulary does not know, keeping the rest" in {
      SiteEntity.mergeAudienceRegions(Set.empty, Some(Vector("JP", "XX-99", "Tokyo"))) shouldBe Set("JP")
    }

    // Consistent with the create path: an unknown code is a bug or a
    // hand-rolled request, and neither should be able to fail a site edit.
    "clear when nothing submitted survives validation" in {
      SiteEntity.mergeAudienceRegions(Set("JP"), Some(Vector("XX-99"))) shouldBe empty
    }
  }
}
