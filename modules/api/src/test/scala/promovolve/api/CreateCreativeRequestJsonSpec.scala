package promovolve.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.*

/**
 * Guards the wire contract of CreateCreativeRequest: the platform (and
 * RunScenario, and any pre-fonts client) posts bodies WITHOUT `lpFonts`,
 * and those must keep deserializing. spray's jsonFormatN does not apply
 * case-class defaults — a non-Option field silently becomes a REQUIRED
 * member, which is exactly the regression this pins against.
 */
class CreateCreativeRequestJsonSpec extends AnyWordSpec with Matchers with ApiJsonFormats {
  import ApiModels.*

  private val minimal =
    """{
      |  "name": "t",
      |  "pages": [],
      |  "landingUrl": "https://example.com"
      |}""".stripMargin

  "CreateCreativeRequest format" should {
    "parse a body without lpFonts (legacy/platform default)" in {
      val req = minimal.parseJson.convertTo[CreateCreativeRequest]
      req.lpFonts shouldBe None
    }

    "parse a body with lpFonts present" in {
      val req =
        """{"name":"t","pages":[],"landingUrl":"https://example.com",
          |"lpFonts":[{"family":"Prata","weight":400,"hash":"abc"}]}""".stripMargin
          .parseJson.convertTo[CreateCreativeRequest]
      req.lpFonts.map(_.map(_.family)) shouldBe Some(Vector("Prata"))
    }
  }
}
