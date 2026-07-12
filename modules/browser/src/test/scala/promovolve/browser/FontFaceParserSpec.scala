package promovolve.browser

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FontFaceParserSpec extends AnyWordSpec with Matchers {

  private val base = "https://cdn.example.com/css/site.css"

  "FontFaceParser" should {

    "parse a quoted family, fixed weight, and woff2 src by format hint" in {
      val css =
        """@font-face {
          |  font-family: "Proxima Nova";
          |  font-weight: 700;
          |  src: url(/fonts/proxima-bold.woff2) format("woff2"),
          |       url(/fonts/proxima-bold.woff) format("woff");
          |}""".stripMargin
      val faces = FontFaceParser.parse(css, base)
      faces shouldBe Vector(FontFaceParser.ParsedFace(
        "Proxima Nova", 700, 700, "https://cdn.example.com/fonts/proxima-bold.woff2"
      ))
    }

    "pick a .woff2 extension when no format hint is present" in {
      val css =
        """@font-face { font-family: Karla; src: url('../f/karla.woff2?v=3'); }"""
      val faces = FontFaceParser.parse(css, base)
      faces.map(_.src) shouldBe Vector("https://cdn.example.com/f/karla.woff2?v=3")
      faces.head.weightMin shouldBe 400 // absent weight defaults to 400
    }

    "carry a variable-font weight range" in {
      val css =
        """@font-face {
          |  font-family: 'Inter Variable';
          |  font-weight: 100 900;
          |  src: url(https://static.example.com/inter-var.woff2) format('woff2');
          |}""".stripMargin
      val face = FontFaceParser.parse(css, base).head
      (face.weightMin, face.weightMax) shouldBe (100, 900)
    }

    "map weight keywords" in {
      val css =
        """@font-face { font-family: A; font-weight: bold; src: url(a.woff2); }
          |@font-face { font-family: B; font-weight: normal; src: url(b.woff2); }""".stripMargin
      FontFaceParser.parse(css, base).map(f => (f.family, f.weightMin)) shouldBe
      Vector(("A", 700), ("B", 400))
    }

    "skip italic faces" in {
      val css =
        """@font-face { font-family: C; font-style: italic; src: url(c-it.woff2); }
          |@font-face { font-family: C; font-style: normal; src: url(c.woff2); }""".stripMargin
      FontFaceParser.parse(css, base).map(_.src) shouldBe
      Vector("https://cdn.example.com/css/c.woff2")
    }

    "skip faces with no woff2 source" in {
      val css =
        """@font-face { font-family: D; src: url(d.ttf) format("truetype"); }"""
      FontFaceParser.parse(css, base) shouldBe empty
    }

    "skip data: and blob: sources" in {
      val css =
        """@font-face { font-family: E; src: url(data:font/woff2;base64,AAAA) format("woff2"); }"""
      FontFaceParser.parse(css, base) shouldBe empty
    }

    "resolve protocol-relative and absolute srcs" in {
      val css =
        """@font-face { font-family: F; src: url(//fonts.example.net/f.woff2); }"""
      FontFaceParser.parse(css, base).map(_.src) shouldBe
      Vector("https://fonts.example.net/f.woff2")
    }

    "handle several blocks and unquoted families" in {
      val css =
        """/* header */
          |@font-face{font-family:Alpha;font-weight:300;src:url("alpha-300.woff2")format("woff2");}
          |.cls { color: red; }
          |@font-face{font-family:'Beta Sans';src:url(beta.woff2);}""".stripMargin
      FontFaceParser.parse(css, base).map(f => (f.family, f.weightMin)) shouldBe
      Vector(("Alpha", 300), ("Beta Sans", 400))
    }
  }
}
