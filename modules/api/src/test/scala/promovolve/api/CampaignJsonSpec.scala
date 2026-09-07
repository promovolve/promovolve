package promovolve.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.*

/**
 * Wire-contract pins for the campaign DTOs around `frequencyCap`
 * (docs/design/FREQUENCY_CAPPING.md). spray's jsonFormatN ignores case-class
 * defaults, so every new field must be Option AND be proven to parse when
 * absent; and the `Campaign` response format is hand-written, so its read and
 * write halves must be proven to agree.
 */
class CampaignJsonSpec extends AnyWordSpec with Matchers with ApiJsonFormats {
  import ApiModels.*

  private val createMinimal =
    """{
      |  "name": "Kinosaki spring",
      |  "budget": { "daily": "100.0000" },
      |  "schedule": { "startAt": "2026-08-24T00:00:00Z" },
      |  "adProductCategory": "123",
      |  "bidding": { "strategy": "fixed", "maxCpm": "5.0000" },
      |  "landingUrl": "https://example.com/onsen"
      |}""".stripMargin

  "CreateCampaignRequest format" should {
    "parse a body without frequencyCap (older dashboards)" in {
      createMinimal.parseJson.convertTo[CreateCampaignRequest].frequencyCap shouldBe None
    }
    "parse a body with frequencyCap" in {
      val body = createMinimal.parseJson.asJsObject
      val withCap = JsObject(body.fields +
        ("frequencyCap" -> JsObject(
          "impressions" -> JsNumber(3),
          "window" -> JsString("day")
        )))
      withCap.convertTo[CreateCampaignRequest].frequencyCap shouldBe Some(FrequencyCapDto(3, "day"))
    }
  }

  "UpdateCampaignRequest format" should {
    "parse an empty patch and a patch that clears the cap (impressions 0)" in {
      "{}".parseJson.convertTo[UpdateCampaignRequest].frequencyCap shouldBe None
      """{"frequencyCap":{"impressions":0,"window":"day"}}""".parseJson
        .convertTo[UpdateCampaignRequest].frequencyCap shouldBe Some(FrequencyCapDto(0, "day"))
    }
  }

  "Campaign format (hand-written)" should {
    val base = Campaign(
      id = "c1", advertiserId = "a1", name = "n", status = "paused",
      budget = CampaignBudget("100.0000"), schedule = CampaignSchedule("2026-08-24T00:00:00Z"),
      adProductCategory = "123", bidding = CampaignBidding(Some("fixed"), "5.0000"),
      landingUrl = "https://example.com", creativeIds = Vector.empty,
      createdAt = "2026-08-24T00:00:00Z", updatedAt = "2026-08-24T00:00:00Z"
    )
    "round-trip an uncapped campaign as frequencyCap: null" in {
      val js = base.toJson.asJsObject
      js.fields("frequencyCap") shouldBe JsNull
      js.convertTo[Campaign].frequencyCap shouldBe None
    }
    "round-trip a capped campaign" in {
      val capped = base.copy(frequencyCap = Some(FrequencyCapDto(2, "week")))
      val js = capped.toJson
      js.asJsObject.fields("frequencyCap") shouldBe JsObject("impressions" -> JsNumber(2), "window" -> JsString("week"))
      js.convertTo[Campaign] shouldBe capped
    }
    "read a response written by an older server (no frequencyCap key at all)" in {
      val js = JsObject(base.toJson.asJsObject.fields - "frequencyCap")
      js.convertTo[Campaign].frequencyCap shouldBe None
    }
  }
}
