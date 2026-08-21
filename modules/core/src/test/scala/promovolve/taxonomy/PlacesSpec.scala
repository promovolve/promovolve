package promovolve.taxonomy

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PlacesSpec extends AnyWordSpec with Matchers with OptionValues {

  // Kamakura is the worked example in docs/design/GEOGRAPHIC_CONTEXT.md.
  private val Kamakura = "GN1860672"

  "the tables" should {

    "load all three levels" in {
      Places.size should be > 70000
      Places.get("JP").value.kind shouldBe PlaceKind.Country
      Places.get("JP-13").value.kind shouldBe PlaceKind.Subdivision
      Places.get(Kamakura).value.kind shouldBe PlaceKind.City
    }

    // The build links cities to ISO subdivisions by NAME because GeoNames
    // numbers admin1 its own way: GeoNames JP.13 is Hyogo, ISO JP-13 is
    // Tokyo. Getting this backwards would have resolved every LLM-emitted
    // "JP-13" to the wrong prefecture, silently — so pin the ISO reading.
    "carry ISO 3166-2 subdivision codes, not GeoNames admin1 codes" in {
      Places.get("JP-13").value.name shouldBe "Tokyo"
      Places.get("JP-14").value.name shouldBe "Kanagawa"
      Places.get("JP-26").value.name shouldBe "Kyoto"
      Places.get("JP-28").value.name shouldBe "Hyogo"
    }
  }

  "ancestors" should {

    "walk city to subdivision to country, nearest first" in {
      Places.ancestors(Kamakura).map(_.code) shouldBe List("JP-14", "JP")
    }

    "stop at the country" in {
      Places.ancestors("JP-13").map(_.code) shouldBe List("JP")
      Places.ancestors("JP") shouldBe Nil
    }

    "return nothing for an unknown code" in {
      Places.ancestors("XX-99") shouldBe Nil
      Places.ancestors("") shouldBe Nil
    }
  }

  "expandWithHops" should {

    "keep the minimum hop distance for each place" in {
      Places.expandWithHops(List(Kamakura)) shouldBe Map(Kamakura -> 0, "JP-14" -> 1, "JP" -> 2)
    }

    "prefer a directly named place over the same place reached as an ancestor" in {
      val expanded = Places.expandWithHops(List(Kamakura, "JP"))
      expanded("JP") shouldBe 0
      expanded("JP-14") shouldBe 1
    }

    "drop unknown codes rather than inventing nodes" in {
      Places.expandWithHops(List("XX-99")) shouldBe empty
      Places.expandWithHops(List(Kamakura, "XX-99")).keySet should contain noneOf ("XX-99", "XX")
    }

    "be empty for empty input" in {
      Places.expandWithHops(Nil) shouldBe empty
    }
  }

  "validate" should {

    // The closed-vocabulary gate: an LLM answer or a client-supplied
    // targeting set must never introduce a code the tables do not know.
    "keep known codes and drop everything else" in {
      Places.validate(List("JP", "JP-13", Kamakura, "XX-99", "Tokyo", "")) shouldBe
      Set("JP", "JP-13", Kamakura)
    }
  }

  "localized names" should {

    "resolve Japanese for all three levels" in {
      Places.nameIn("ja", "JP").value shouldBe "日本"
      Places.nameIn("ja", "JP-13").value shouldBe "東京"
      Places.nameIn("ja", Kamakura).value shouldBe "鎌倉"
    }

    "accept a full language tag" in {
      Places.nameIn("ja-JP", "JP-13").value shouldBe "東京"
    }

    "fall back to the English name, then the code" in {
      Places.displayName("en", "JP-13") shouldBe "Tokyo"
      Places.displayName("ja", "XX-99") shouldBe "XX-99"
    }
  }

  "search" should {

    "find a city by its English name" in {
      Places.search("kamakura").map(_.code) should contain(Kamakura)
    }

    "find a place by its localized name" in {
      Places.search("鎌倉").map(_.code) should contain(Kamakura)
      Places.search("東京").map(_.code) should contain("JP-13")
    }

    "ignore case and diacritics" in {
      Places.search("HYOGO").map(_.code) should contain("JP-28")
      Places.search("kyoto").map(_.code) should contain("JP-26")
    }

    "rank broader places above cities on an equal prefix match" in {
      val hits = Places.search("japan").map(_.code)
      hits.headOption.value shouldBe "JP"
    }

    "return nothing for a blank query" in {
      Places.search("") shouldBe Nil
      Places.search("   ") shouldBe Nil
    }

    "respect the limit" in {
      Places.search("san", limit = 5) should have size 5
    }
  }
}
