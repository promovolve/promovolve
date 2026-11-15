package promovolve.creative

import org.scalatest.funsuite.AnyFunSuite

class LPExtractorPlainTextSpec extends AnyFunSuite {
  test("decodes the entities the model escapes its copy with") {
    assert(LPExtractor.plainText("Hot Springs &amp; Fuji") == "Hot Springs & Fuji")
    assert(LPExtractor.plainText("&quot;Hi&quot; &#39;x&#39; &lt;b&gt;") == "\"Hi\" 'x' <b>")
    assert(LPExtractor.plainText("&amp;lt;") == "&lt;")
    assert(LPExtractor.plainText("白みりん & 流山") == "白みりん & 流山")
  }
  test("the poster brief is one page and never offered as an arc") {
    assert(CreativeArc.byId("poster").roles.size == 1)
    assert(!CreativeArc.all.exists(_.id == "poster"))
  }
}
