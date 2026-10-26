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

  // The door the first cut left "cheap to open": a classifier-named city,
  // resolved IN SCOPE. Kanazawa is the live case — a Kanazawa article
  // classified as JP-17 matched no campaign targeting Kanazawa, because the
  // targeting side never expands and the content side could not name the
  // city.
  "resolveCity" should {
    val Kanazawa = "GN1860243"

    "resolve a city inside its subdivision" in {
      Places.resolveCity("Kanazawa", "JP-17").value.code shouldBe Kanazawa
      Places.resolveCity("Kamakura", "JP-14").value.code shouldBe Kamakura
    }

    "resolve inside the country when the name is unique there" in {
      Places.resolveCity("Kamakura", "JP").value.code shouldBe Kamakura
    }

    "refuse to guess between same-named cities in one country" in {
      // Springfield, US: a dozen of them, none dominant.
      Places.resolveCity("Springfield", "US") shouldBe None
    }

    "resolve the dominant one when the largest clearly outweighs the rest" in {
      // Portland, US: Oregon's 650k against Maine's 67k and four townships.
      Places.resolveCity("Portland", "US").value.code shouldBe "GN5746545"
    }

    "pick the most populous inside a subdivision" in {
      Places.resolveCity("Springfield", "US-IL").value.code shouldBe "GN4250542"
    }

    "ignore case, diacritics and accept localized names" in {
      Places.resolveCity("KANAZAWA", "JP-17").value.code shouldBe Kanazawa
      Places.resolveCity("金沢", "JP-17").value.code shouldBe Kanazawa
    }

    // The tables themselves disagree (鎌倉 but 京都市), so both directions
    // must work: a suffix the table lacks is stripped, one it carries is added.
    "tolerate a Japanese municipality suffix either way" in {
      Places.resolveCity("金沢市", "JP-17").value.code shouldBe Kanazawa
      Places.resolveCity("鎌倉市", "JP").value.code shouldBe Kamakura
      Places.resolveCity("京都", "JP").value.code shouldBe "GN1857910"
    }

    "refuse a city outside the stated scope" in {
      Places.resolveCity("Kanazawa", "JP-13") shouldBe None
      Places.resolveCity("Kanazawa", "US") shouldBe None
    }

    "refuse an unknown scope, a city as scope, or a blank name" in {
      Places.resolveCity("Kanazawa", "XX-99") shouldBe None
      Places.resolveCity("Kanazawa", Kamakura) shouldBe None
      Places.resolveCity("   ", "JP-17") shouldBe None
    }
  }

  // Names, not codes. Asking a model for "JP-17" asked it to recall
  // arbitrary numbering it has no principled grip on, and a wrong-but-
  // valid code is indistinguishable from a right one once parsed. Asking
  // for "Ishikawa" asks it for geography, and the table does the rest.
  // The build links cities to ISO subdivisions by admin1 NAME — and for
  // Taiwan that can never work: GeoNames carries only 4 admin1s there, with
  // "Taiwan" covering Tainan. The large-city name fallback (build-places.mjs,
  // tiered + unique-gated + population-floored) closes it. Found live
  // 2026-08-27: targeting TW-TNN could never match the Tainan page.
  "the Taiwan city links" should {
    "chain Tainan city through its ISO subdivision" in {
      Places.get("GN1668355").value.parent shouldBe Some("TW-TNN")
      Places.ancestors("GN1668355").map(_.code) shouldBe List("TW-TNN", "TW")
      Places.targetingMatches(Set("TW-TNN"), Set("GN1668355")) shouldBe true
    }
    // The guard the fallback must never break: a city named after a
    // DIFFERENT subdivision than the one it sits in keeps its mapped
    // admin1 — Madrid's Salamanca district stays in Madrid.
    "never let the name fallback override a mapped admin1" in {
      Places.get("GN6544491").value.parent shouldBe Some("ES-M")
    }
  }

  "resolveCountry" should {

    "resolve the catalogue's own name" in {
      Places.resolveCountry("Japan").value.code shouldBe "JP"
      Places.resolveCountry(" germany ").value.code shouldBe "DE"
    }

    // iso-codes ships the formal name; nobody writes it. These come from
    // the hand-maintained aliases_en.tsv.
    "resolve the everyday English name" in {
      Places.resolveCountry("South Korea").value.code shouldBe "KR"
      Places.resolveCountry("Taiwan").value.code shouldBe "TW"
      Places.resolveCountry("USA").value.code shouldBe "US"
      Places.resolveCountry("Vietnam").value.code shouldBe "VN"
    }

    // Derived from the catalogue's own spelling by Places.nameVariants —
    // no alias row needed for either form.
    "resolve a comma-inverted formal name" in {
      Places.resolveCountry("Republic of Korea").value.code shouldBe "KR"
      Places.resolveCountry("British Virgin Islands").value.code shouldBe "VG"
    }

    // "Congo" is CG's real name AND a derived head of CD's. Primary wins,
    // so the derived collision resolves to nothing instead of to the
    // wrong country.
    "let a real name outrank a derived one, and refuse an ambiguous derivation" in {
      Places.resolveCountry("Congo").value.code shouldBe "CG"
      Places.resolveCountry("Virgin Islands") shouldBe None
    }

    "refuse a name the table does not carry" in {
      Places.resolveCountry("Freedonia") shouldBe None
      Places.resolveCountry("  ") shouldBe None
    }
  }

  "resolveSubdivision" should {

    "resolve a region inside its country" in {
      Places.resolveSubdivision("Ishikawa", "JP").value.code shouldBe "JP-17"
      Places.resolveSubdivision("California", "US").value.code shouldBe "US-CA"
    }

    // A model writes the administrative word; the table omits it.
    "tolerate an English administrative word" in {
      Places.resolveSubdivision("Ishikawa Prefecture", "JP").value.code shouldBe "JP-17"
      Places.resolveSubdivision("石川県", "JP").value.code shouldBe "JP-17"
    }

    // ISO carries the local spelling with the alternate in brackets;
    // nameVariants makes both reachable.
    "resolve a bracketed alternate spelling" in {
      Places.resolveSubdivision("Cataluña", "ES").value.code shouldBe "ES-CT"
      Places.resolveSubdivision("Catalunya", "ES").value.code shouldBe "ES-CT"
    }

    // Georgia is a country AND a US state. Kind and scope separate them
    // with no special case.
    "keep a country and a same-named subdivision apart" in {
      Places.resolveSubdivision("Georgia", "US").value.code shouldBe "US-GA"
      Places.resolveCountry("Georgia").value.code shouldBe "GE"
    }

    "refuse a region outside the stated country, or a scope that is not a country" in {
      Places.resolveSubdivision("Ishikawa", "US") shouldBe None
      Places.resolveSubdivision("Ishikawa", "JP-17") shouldBe None
      Places.resolveSubdivision("Ishikawa", "XX") shouldBe None
    }
  }

  "resolveNamed" should {
    val Kanazawa = "GN1860243"

    "resolve city, region and country to the city" in {
      Places.resolveNamed(Some("Kanazawa"), Some("Ishikawa"), Some("Japan")).value.code shouldBe Kanazawa
    }

    "resolve as far as the model named" in {
      Places.resolveNamed(None, Some("Ishikawa"), Some("Japan")).value.code shouldBe "JP-17"
      Places.resolveNamed(None, None, Some("Japan")).value.code shouldBe "JP"
    }

    // Scope is what makes an ambiguous name mean something — the same
    // rule resolveCity has always applied, now at every level.
    "use the region to disambiguate a city name" in {
      Places.resolveNamed(Some("Springfield"), Some("Illinois"), Some("United States")).value.code shouldBe
      "GN4250542"
      Places.resolveNamed(Some("Springfield"), None, Some("United States")).value.code shouldBe "US"
    }

    // Coarser, never wrong.
    "degrade level by level, and say what it could not place" in {
      val town = Places.resolveNamed(Some("Nowhere"), Some("Ishikawa"), Some("Japan")).value
      town.code shouldBe "JP-17"
      town.unresolved shouldBe List("Nowhere")

      val region = Places.resolveNamed(None, Some("Nowhere"), Some("Japan")).value
      region.code shouldBe "JP"
      region.unresolved shouldBe List("Nowhere")
    }

    // The build links cities to ISO subdivisions by name and leaves the
    // link empty when it cannot match confidently, so a correct city can
    // sit directly under its country. Trying only the region would reject
    // a right answer for a gap in our own table.
    "fall back to the country when the region does not contain the city" in {
      Places.resolveNamed(Some("Kanazawa"), Some("Tokyo"), Some("Japan")).value.code shouldBe Kanazawa
    }

    // THE hole the code-shaped answer could never report: a wrong region
    // and a right one both parse. A city that sits in a different region
    // of its own is a contradiction the table can see.
    "report a region the city itself contradicts" in {
      val out = Places.resolveNamed(Some("Kanazawa"), Some("Tokyo"), Some("Japan")).value
      out.unresolved should have size 1
      out.unresolved.head should include("Kanazawa is in Ishikawa")
    }

    // With no country there is no scope to stand on, and the rest cannot
    // be trusted either — the same refusal "Kanazawa, XX-99" gets.
    "refuse without a resolvable country" in {
      Places.resolveNamed(Some("Kanazawa"), Some("Ishikawa"), None) shouldBe None
      Places.resolveNamed(Some("Kanazawa"), Some("Ishikawa"), Some("Freedonia")) shouldBe None
      Places.resolveNamed(Some("Kanazawa"), Some("Ishikawa"), Some("  ")) shouldBe None
    }

    // ISO lists Macau, Hong Kong and Taiwan both as countries and as
    // Chinese subdivisions. Cities and advertisers' targets hang off the
    // country, so a model that files them under China must still land
    // there (GH #274).
    "place Macau, Hong Kong and Taiwan as their own countries when filed under China" in {
      val Macau = "GN1821274"
      Places.resolveNamed(Some("Macau"), None, Some("China")).value shouldBe Places.ResolvedPlace(Macau, Nil)
      Places.resolveNamed(None, Some("Macau"), Some("China")).value.code shouldBe "MO"
      Places.resolveNamed(None, Some("Macao SAR"), Some("China")).value.code shouldBe "MO"
      Places.resolveNamed(Some("Hong Kong"), None, Some("China")).value.code shouldBe "GN1819729"
      Places.resolveNamed(Some("Taipei"), Some("Taiwan"), Some("China")).value.code shouldBe "GN1668341"
    }

    "read Macau, the everyday spelling, as the country Macao" in {
      Places.resolveNamed(None, None, Some("Macau")).value.code shouldBe "MO"
      Places.resolveNamed(Some("Macau"), None, Some("Macau")).value.code shouldBe "GN1821274"
    }

    // Before: the region resolved to US-PR, which no city hangs off, and
    // the city fell back to the only San Juan inside the US — in Texas.
    "place a US territory as its own country" in {
      Places.resolveNamed(Some("San Juan"), Some("Puerto Rico"), Some("United States")).value.code shouldBe
      "GN4568127"
    }

    // The list is explicit because matching codes is not the same place:
    // US-IN is Indiana, IN is India.
    "never turn a region into a country that merely shares its code" in {
      Places.resolveNamed(None, Some("Indiana"), Some("United States")).value.code shouldBe "US-IN"
      Places.resolveNamed(Some("Jordan"), None, Some("United States")).value.code should not be "JO"
    }

    "treat blank parts as absent" in {
      Places.resolveNamed(Some(""), Some("   "), Some("Japan")).value shouldBe
      Places.ResolvedPlace("JP", Nil)
    }
  }

  "nameVariants" should {

    "derive the comma-inverted and bare forms of a qualified name" in {
      (Places.nameVariants("Korea, Republic of") should contain).allOf("Korea", "Republic of Korea")
    }

    "derive the bracketed alternate" in {
      (Places.nameVariants("Catalunya [Cataluña]") should contain).allOf("Cataluña", "Catalunya")
    }

    "derive nothing from a plain name" in {
      Places.nameVariants("Ishikawa") shouldBe empty
    }
  }

  "resolveEmitted" should {

    "pass a plain code through untouched" in {
      Places.resolveEmitted("JP-17") shouldBe Some("JP-17")
      Places.resolveEmitted(" JP ") shouldBe Some("JP")
    }

    "resolve a 'City, CODE' pair to the city" in {
      Places.resolveEmitted("Kanazawa, JP-17") shouldBe Some("GN1860243")
      Places.resolveEmitted("kanazawa,jp-17") shouldBe Some("GN1860243")
    }

    // Coarser, never wrong: a name the table does not know inside a scope
    // it does keeps the scope.
    "degrade an unresolvable city to its stated scope" in {
      Places.resolveEmitted("Nowhere, JP-17") shouldBe Some("JP-17")
      Places.resolveEmitted("Springfield, US") shouldBe Some("US")
    }

    "drop a pair whose scope is unknown, and blank input" in {
      Places.resolveEmitted("Kanazawa, XX-99") shouldBe None
      Places.resolveEmitted("") shouldBe None
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

    "find a country by its everyday Japanese name, not only the catalogue's formal one" in {
      // iso-codes says 米国 / 英国; GeoNames' alternate names supply the
      // names people actually type.
      Places.search("アメリカ").headOption.map(_.code) shouldBe Some("US")
      Places.search("イギリス").map(_.code) should contain("GB")
      Places.search("米国").map(_.code) should contain("US")
    }

    "find foreign cities by their Japanese names" in {
      Places.search("ロサンゼルス").map(_.code) should contain("GN5368361")
      Places.search("ロンドン").map(_.code) should contain("GN2643743")
      Places.search("ニューヨーク").map(_.code) should contain("GN5128581")
    }

    "keep kana voicing marks apart: パリ is Paris, not Bali" in {
      val paris = Places.search("パリ").map(_.code)
      paris should contain("GN2988507")
      paris should not contain "ID-BA"
    }

    "tolerate a Japanese administrative suffix on the query" in {
      Places.search("石川県").map(_.code) should contain("JP-17")
      Places.search("豊岡市").map(_.code) should contain("GN1849831")
      Places.search("鎌倉市").map(_.code) should contain(Kamakura)
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
