package promovolve.api

import java.io.File
import scala.io.Source

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.*

/**
 * Producer half of the Scala ⇄ Go reporting contract (GH #24).
 *
 * The Go dashboard decodes these responses into hand-written structs in
 * `platform/internal/handler/report.go` and `publisher_report.go`. Nothing in
 * the compiler links the two sides, so a renamed field or a Long-turned-String
 * compiles here, passes Go's build, and only breaks when a publisher opens the
 * report page.
 *
 * The contract source is the `contracts/reports` directory — ONE canonical
 * body per response, read by this spec and by the Go-side
 * `report_contract_test.go`. Round-tripping
 * through the AST pins the field NAMES and TYPES in both directions: parsing
 * proves we accept the fixture, re-encoding proves we emit it exactly (an
 * added, dropped or renamed field changes the JsObject and fails here).
 *
 * To change the wire format: edit the fixture and both sides in one PR. A
 * fixture edit alone fails whichever side has not caught up, which is the
 * point.
 */
class ReportContractSpec extends AnyWordSpec with Matchers with ApiJsonFormats {
  import ApiModels.*

  /**
   * sbt runs tests with the working directory at the subproject base when
   * forked and at the root otherwise, so walk up to whichever ancestor holds
   * `contracts/` rather than hard-coding either.
   */
  private def fixture(name: String): JsValue = {
    def find(dir: File): File =
      if (dir == null) fail(s"contracts/reports/$name not found above ${new File(".").getAbsolutePath}")
      else {
        val candidate = new File(dir, s"contracts/reports/$name")
        if (candidate.isFile) candidate else find(dir.getParentFile)
      }
    val file = find(new File(".").getAbsoluteFile)
    val text = Source.fromFile(file, "UTF-8")
    try text.mkString.parseJson
    finally text.close()
  }

  /** Parse into the DTO, re-encode, and require the AST to come back identical. */
  private def pin[A: RootJsonFormat](name: String)(check: A => Unit): Unit = {
    val json = fixture(name)
    val dto = json.convertTo[A]
    check(dto)
    dto.toJson shouldBe json
  }

  "AdvertiserReportResponse" should {
    "match the canonical multi-row body" in {
      pin[AdvertiserReportResponse]("advertiser-report.json") { r =>
        r.advertiserId shouldBe "adv_7f3a"
        r.from shouldBe "2026-08-01"
        r.to shouldBe "2026-08-03"
        r.rows.map(_.day) shouldBe Vector("2026-08-01", "2026-08-03")

        val first = r.rows.head
        first.campaignId shouldBe "cmp_kinosaki"
        first.impressions shouldBe 120345L
        first.clicks shouldBe 981L
        first.ctaClicks shouldBe 77L
        // Money stays a 4-decimal STRING on the wire — the Go side parses it
        // to float64 for display only. A Double here would round-trip
        // 1234.5678 into scientific notation or lose the trailing digit.
        first.spend shouldBe "1234.5678"
        first.dogearedImpressions shouldBe 4102L
        first.folds shouldBe 512L
        first.unfolds shouldBe 488L
        first.dogearedClicks shouldBe 61L
        first.dogearedCtaClicks shouldBe 9L

        // Sub-cent spend must survive as-is: the dashboard's funnel math
        // divides by it, and "0.0001" truncated to "0.00" would read as free.
        r.rows(1).spend shouldBe "0.0001"
      }
    }

    "match the canonical empty body" in {
      pin[AdvertiserReportResponse]("advertiser-report-empty.json") { r =>
        // Days without delivery have no row: an empty range is `[]`, never
        // null — Go ranges over the slice without a nil check.
        r.rows shouldBe empty
      }
    }
  }

  "AdvertiserReportBreakdownResponse" should {
    "match the canonical body, including coverageFrom and blank keys" in {
      pin[AdvertiserReportBreakdownResponse]("advertiser-report-breakdown.json") { r =>
        r.dim shouldBe "site"
        // '' = no rollup coverage yet; the platform renders the "data starts"
        // note from this, so its absence or a null would misdate the report.
        r.coverageFrom shouldBe "2026-07-15"
        r.rows.map(_.key) shouldBe Vector("site_8821", "")
        r.rows.head.label shouldBe "onsen.example.jp"
        r.rows.head.spend shouldBe "987.6543"
        // key '' = uncategorized / no publisher row; label '' = no display
        // name, and the platform falls back to the key. Both stay blank
        // strings rather than becoming null.
        r.rows(1).label shouldBe ""
        r.rows(1).spend shouldBe "0.0100"
      }
    }
  }

  "PublisherSiteCategoryReportResponse" should {
    "match the canonical multi-row body" in {
      pin[PublisherSiteCategoryReportResponse]("publisher-site-categories.json") { r =>
        r.publisherId shouldBe "pub_22c1"
        r.coverageFrom shouldBe "2026-07-15"
        r.rows.map(_.siteId) shouldBe Vector("site_8821", "site_9004")
        // grossRevenue is advertiser spend BEFORE platform margin. The Go
        // side nets it down; sending net here would double-deduct.
        r.rows.head.grossRevenue shouldBe "1000.0000"
        r.rows.head.host shouldBe "onsen.example.jp"
        r.rows.head.category shouldBe "52"
        r.rows(1).host shouldBe ""
        r.rows(1).category shouldBe ""
        r.rows(1).grossRevenue shouldBe "0.0001"
      }
    }

    "match the canonical empty body" in {
      pin[PublisherSiteCategoryReportResponse]("publisher-site-categories-empty.json") { r =>
        r.rows shouldBe empty
        r.coverageFrom shouldBe ""
      }
    }
  }
}
