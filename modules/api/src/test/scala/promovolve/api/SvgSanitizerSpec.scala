package promovolve.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SvgSanitizerSpec extends AnyWordSpec with Matchers {

  private def sanitizeStr(s: String): String =
    new String(SvgSanitizer.sanitize(s.getBytes("UTF-8")), "UTF-8")

  "SvgSanitizer.sanitize" should {

    "strip <script> blocks" in {
      val out = sanitizeStr("""<svg><script>alert(1)</script><circle r="5"/></svg>""")
      out should not include "script"
      out should not include "alert"
      out should include ("<circle")
    }

    "strip self-closing <script> tags" in {
      val out = sanitizeStr("""<svg><script src="x"/><rect/></svg>""")
      out should not include "script"
      out should include ("<rect")
    }

    "strip onload, onclick, onerror handlers" in {
      val out = sanitizeStr("""<svg onload="alert(1)"><circle onclick="bad()" onerror='x' r="5"/></svg>""")
      out should not include "onload"
      out should not include "onclick"
      out should not include "onerror"
      out should not include "alert"
      out should include ("<circle")
    }

    "strip javascript: hrefs" in {
      val out = sanitizeStr("""<svg><a href="javascript:alert(1)"><text>x</text></a></svg>""")
      out should not include "javascript:"
    }

    "strip xlink:href javascript:" in {
      val out = sanitizeStr("""<svg><use xlink:href="javascript:alert(1)"/></svg>""")
      out should not include "javascript:"
    }

    "strip <foreignObject> blocks (HTML/JS injection vector)" in {
      val out = sanitizeStr(
        """<svg><foreignObject><body><script>x</script></body></foreignObject><rect/></svg>"""
      )
      out should not include "foreignObject"
      out should not include "script"
      out should include ("<rect")
    }

    "strip <iframe>, <object>, <embed>" in {
      val out = sanitizeStr("""<svg><iframe src="x"></iframe><object data="y"></object><embed src="z"/></svg>""")
      out should not include "iframe"
      out should not include "object"
      out should not include "embed"
    }

    "leave benign SVG untouched in spirit (shapes preserved)" in {
      val src = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M5 5 L10 10"/></svg>"""
      val out = sanitizeStr(src)
      out should include ("<path")
      out should include ("viewBox")
      out should include ("xmlns")
    }
  }

  "SvgSanitizer.extractDims" should {

    "read explicit width/height" in {
      SvgSanitizer.extractDims("""<svg width="100" height="50"><rect/></svg>""".getBytes) shouldBe ((100, 50))
    }

    "fall back to viewBox" in {
      SvgSanitizer.extractDims("""<svg viewBox="0 0 200 80"><rect/></svg>""".getBytes) shouldBe ((200, 80))
    }

    "return (0,0) when neither is present" in {
      SvgSanitizer.extractDims("""<svg><rect/></svg>""".getBytes) shouldBe ((0, 0))
    }

    "handle decimal values by truncating to int" in {
      SvgSanitizer.extractDims("""<svg width="100.5" height="50.7"/>""".getBytes) shouldBe ((100, 50))
    }
  }
}