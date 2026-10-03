package promovolve.taxonomy

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import promovolve.taxonomy.IABTaxonomy.EmittedPlace

/**
 * Tier 1 of docs/design/GEOGRAPHIC_CONTEXT.md — the classifier answering
 * "where is this page about?" alongside "what is it about?".
 *
 * The model NAMES places; the shipped table supplies every code. Parsing
 * stays deliberately tolerant on shape and resolution deliberately strict
 * on content: a name the table cannot place is dropped or degraded, never
 * coerced.
 */
class IABTaxonomyPlacesSpec extends AnyWordSpec with Matchers {

  private def named(city: String = "", region: String = "", country: String) =
    EmittedPlace(
      city = Option(city).filter(_.nonEmpty),
      region = Option(region).filter(_.nonEmpty),
      country = Option(country).filter(_.nonEmpty))

  "placesFrom" should {

    "read the documented shape" in {
      IABTaxonomy.placesFrom(
        """{"selected_taxonomy_ids": [], "places": [{"city": "Kanazawa", "region": "Ishikawa", "country": "Japan"}]}"""
      ) shouldBe List(named("Kanazawa", "Ishikawa", "Japan"))
    }

    "read several places, each as coarse as the model left it" in {
      IABTaxonomy.placesFrom("""{"places": [{"region": "Hyogo", "country": "Japan"}, {"country": "Taiwan"}]}""")
        .shouldBe(List(named(region = "Hyogo", country = "Japan"), named(country = "Taiwan")))
    }

    // OpenAI strict mode requires every property in `required`, so "not
    // applicable" can only be said IN a value. Blank must mean absent or
    // every country-level answer would carry two empty strings.
    "treat a blank field as absent" in {
      IABTaxonomy.placesFrom("""{"places": [{"city": "", "region": "  ", "country": "Japan"}]}""")
        .shouldBe(List(named(country = "Japan")))
    }

    // A page about nowhere in particular is the COMMON case, and both the
    // empty array and the absent key must mean that rather than an error.
    "treat an empty array and an absent key alike" in {
      IABTaxonomy.placesFrom("""{"places": []}""") shouldBe Nil
      IABTaxonomy.placesFrom("""{"selected_taxonomy_ids": []}""") shouldBe Nil
    }

    // The pre-2026-08-25 code shapes. A model that ignored the schema and
    // answered the old way is still understood — reading it costs one
    // branch and saves the whole classification.
    "still read the legacy code shapes" in {
      IABTaxonomy.placesFrom("""{"places": ["JP-13"]}""") shouldBe List(EmittedPlace(legacy = Some("JP-13")))
      IABTaxonomy.placesFrom("""{"places": [{"code": "JP-13"}]}""") shouldBe
      List(EmittedPlace(legacy = Some("JP-13")))
    }

    "survive malformed input without throwing" in {
      IABTaxonomy.placesFrom("not json") shouldBe Nil
      IABTaxonomy.placesFrom("""{"places": "JP"}""") shouldBe Nil
      IABTaxonomy.placesFrom("[]") shouldBe Nil
    }

    "drop entries carrying nothing" in {
      IABTaxonomy.placesFrom("""{"places": ["", "  ", {}, {"city": ""}, {"country": "Japan"}]}""")
        .shouldBe(List(named(country = "Japan")))
    }
  }

  "the closed-vocabulary gate" should {

    // The pairing that matters: parsing is permissive, the table is not.
    // Nothing the model says becomes a code unless the table already has
    // it, because downstream matching is plain set intersection and a
    // bogus code would never match while looking like real targeting.
    "resolve names through the shipped table, level by level" in {
      val parsed = IABTaxonomy.placesFrom(
        """{"places": [{"city": "Kanazawa", "region": "Ishikawa", "country": "Japan"},
           |            {"region": "Hyogo", "country": "Japan"},
           |            {"country": "Japan"}]}""".stripMargin)
      val codes = parsed.flatMap(p => Places.resolveNamed(p.city, p.region, p.country)).map(_.code)
      Places.validate(codes) shouldBe Set("GN1860243", "JP-28", "JP")
    }

    "drop a place whose country the table does not know" in {
      val parsed = IABTaxonomy.placesFrom("""{"places": [{"city": "Springfield", "country": "Freedonia"}]}""")
      parsed.flatMap(p => Places.resolveNamed(p.city, p.region, p.country)) shouldBe Nil
    }

    "degrade an unknown town to the region it was said to be in" in {
      val parsed = IABTaxonomy.placesFrom(
        """{"places": [{"city": "Nowhere", "region": "Ishikawa", "country": "Japan"}]}""")
      val resolved = parsed.flatMap(p => Places.resolveNamed(p.city, p.region, p.country))
      resolved.map(_.code) shouldBe List("JP-17")
      // and says so, rather than passing a coarser answer off as a clean one
      resolved.flatMap(_.unresolved) shouldBe List("Nowhere")
    }

    "still resolve a legacy 'City, CODE' answer" in {
      val parsed = IABTaxonomy.placesFrom("""{"places": ["Kanazawa, JP-17", "Nowhere, JP-20", "JP"]}""")
      Places.validate(parsed.flatMap(_.legacy).flatMap(Places.resolveEmitted)) shouldBe
      Set("GN1860243", "JP-20", "JP")
    }

    "resolve a validated subdivision to the expected place" in {
      Places.get("JP-13").map(_.name) shouldBe Some("Tokyo")
    }
  }

  "the prompt" should {

    val candidates = Map("150" -> "Attractions")

    def promptWith(placeHint: Option[String]): String = {
      given system: org.apache.pekko.actor.typed.ActorSystem[Nothing] =
        org.apache.pekko.actor.typed.ActorSystem(
          org.apache.pekko.actor.typed.scaladsl.Behaviors.empty, "iab-prompt-spec")
      given scala.concurrent.ExecutionContext = system.executionContext
      try new IABTaxonomy(IABTaxonomy.Provider.Gemini("k"))
          .buildPrompt("https://x/y", "text", candidates, None, placeHint)
      finally system.terminate()
    }

    "ask for places and show the response shape" in {
      val prompt = promptWith(None)
      prompt should include("\"places\"")
      prompt should include("empty list")
    }

    // The point of the whole design: a model recalls that Kanazawa is in
    // Ishikawa far more reliably than it recalls that Ishikawa is JP-17,
    // and could never produce a GeoNames city id at all. Asking for a code
    // is what invited a confident wrong one.
    "ask for English names and never for a code" in {
      val prompt = promptWith(None)
      prompt should include("Name each one in ENGLISH")
      prompt should include("never a code")
      prompt should include("""{"city": "Kanazawa", "region": "Ishikawa", "country": "Japan"}""")
      (prompt should not).include("ISO 3166")
    }

    // Live 2026-08-22: an article about Kinosaki Onsen (a district of
    // Toyooka, absent from cities5000) came back places=[] — the model had no
    // way to say it and took the "empty is correct" exit. A small spot must
    // fall back to its enclosing city or subdivision, not to nothing.
    "tell the model to name the enclosing place for somewhere smaller than a city" in {
      val prompt = promptWith(None)
      prompt should include("\"Toyooka\"")
      prompt should include("the enclosing place is the right answer")
    }

    // Guessing a region to fill the field is the failure this design is
    // meant to remove; a country alone must read as a good answer.
    "invite the model to leave the finer parts out" in {
      val prompt = promptWith(None)
      prompt should include("country alone is a good answer")
    }

    // The place hint is publisher-controlled text entering a prompt and the
    // publisher is paid by the answer — the same hazard as the topic hint,
    // so it gets the same framing rather than being trusted.
    "frame a publisher place hint as an unverified claim" in {
      val prompt = promptWith(Some("Kyoto"))
      prompt should include("SELF-REPORTED, NOT VERIFIED")
      prompt should include("Kyoto")
      prompt should include("ignore it entirely")
    }
  }
}
