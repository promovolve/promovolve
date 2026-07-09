package promovolve.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import promovolve.api.ApiModels.*
import promovolve.api.ApiJsonFormats.given
import spray.json.*

/**
 * Guards the brand-kit handoff contract: the LP-derived palette + font
 * faces must survive JSON serialization so the creative editor (and the
 * Designer it hands off to) can seed the brand kit from the LP.
 */
class AnalyzeLPResponseJsonSpec extends AnyFlatSpec with Matchers {

  "AnalyzeLPResponse" should "round-trip palette and fonts through JSON" in {
    val resp = AnalyzeLPResponse(
      url = "https://example.com/lp",
      sections = Vector(
        AnalyzeLPSection("Headline", "Body copy",
          Vector(AnalyzeLPImage("https://img/1.png", 800, 600, "alt")))
      ),
      dominantColor = Some("rgb(10, 20, 30)"),
      textColor = Some("rgb(240, 240, 240)"),
      palette = Vector("#0a141e", "#f0f0f0", "#ff6600"),
      fonts = Vector("Poppins", "Georgia")
    )

    val parsed = resp.toJson.convertTo[AnalyzeLPResponse]

    parsed shouldBe resp
    parsed.palette shouldBe Vector("#0a141e", "#f0f0f0", "#ff6600")
    parsed.fonts shouldBe Vector("Poppins", "Georgia")
  }

  it should "serialize empty palette and fonts as empty JSON arrays" in {
    val resp = AnalyzeLPResponse(url = "https://example.com", sections = Vector.empty)
    val obj = resp.toJson.asJsObject

    obj.fields("palette") shouldBe JsArray()
    obj.fields("fonts") shouldBe JsArray()
    obj.convertTo[AnalyzeLPResponse] shouldBe resp
  }
}
