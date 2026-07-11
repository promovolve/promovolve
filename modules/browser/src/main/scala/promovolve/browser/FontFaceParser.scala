package promovolve.browser

/**
 * Extracts `@font-face` declarations from raw CSS text — the mapping from a
 * font FAMILY to the URL of the file that backs it. The LP analyzer captures
 * stylesheet bodies off the wire (page.onResponse), so this runs server-side
 * where cross-origin stylesheets are just text (the in-page `cssRules` API
 * throws SecurityError on them).
 *
 * Deliberately regex-based, not a CSS parser: `@font-face` blocks are flat
 * (no nesting) and the three descriptors we need — `font-family`,
 * `font-weight`, `src` — are simple `name: value;` pairs. Anything that
 * doesn't match is skipped; a miss only means that face can't be offered for
 * re-hosting.
 *
 * Only woff2 sources are kept: the self-hosted key scheme is
 * `fonts/<slug>-<weight>-<variant>.woff2` and the banner requests
 * `format("woff2")`, so other formats would need transcoding we don't do.
 */
object FontFaceParser {

  /**
   * One parsed `@font-face`: the family it declares, its weight span
   * (`min == max` for a fixed weight; a variable font declares a range like
   * `font-weight: 100 900`), and the ABSOLUTE woff2 URL it loads from
   * (resolved against the stylesheet's own URL).
   */
  final case class ParsedFace(family: String, weightMin: Int, weightMax: Int, src: String)

  // A whole @font-face block. CSS strings/urls can't contain an unescaped
  // `}`, so a non-greedy body match is safe for flat blocks.
  private val FaceBlock = """(?is)@font-face\s*\{(.*?)\}""".r
  private val FamilyRe = """(?i)font-family\s*:\s*(?:"([^"]+)"|'([^']+)'|([^;{}]+))""".r
  private val WeightRe = """(?i)font-weight\s*:\s*([^;{}]+)""".r
  private val StyleRe = """(?i)font-style\s*:\s*(italic|oblique)""".r
  // Every url(...) in the src descriptor, with its optional format(...) hint.
  private val SrcUrlRe =
    """(?i)url\(\s*(?:"([^"]+)"|'([^']+)'|([^)'"\s]+))\s*\)(?:\s*format\(\s*['"]?([a-z0-9-]+)['"]?\s*\))?""".r

  /**
   * Parse every usable `@font-face` out of `css`. `baseUrl` is the URL the
   * CSS itself was served from (or the page URL for inline `<style>` text) —
   * font `src` URLs are resolved against it. Italic faces are dropped (the
   * catalog key scheme has no style axis; serving an italic file as the
   * upright face would render every headline slanted).
   */
  def parse(css: String, baseUrl: String): Vector[ParsedFace] =
    FaceBlock.findAllMatchIn(css).flatMap { m =>
      val body = m.group(1)
      if (StyleRe.findFirstIn(body).isDefined) None
      else
        for {
          family <- FamilyRe.findFirstMatchIn(body).map(fm =>
            Option(fm.group(1)).orElse(Option(fm.group(2))).getOrElse(fm.group(3)).trim
          ).filter(_.nonEmpty)
          (wMin, wMax) = parseWeight(WeightRe.findFirstMatchIn(body).map(_.group(1).trim))
          src <- woff2Src(body).flatMap(resolveUrl(baseUrl, _))
        } yield ParsedFace(family, wMin, wMax, src)
    }.toVector

  /**
   * `font-weight` descriptor → (min, max). A single value gives a degenerate
   * range; `100 900` (variable font) gives the span; keywords map to their
   * numeric values; absent/unparsable defaults to 400.
   */
  private def parseWeight(raw: Option[String]): (Int, Int) = {
    def one(t: String): Option[Int] = t.toLowerCase match {
      case "normal" => Some(400)
      case "bold"   => Some(700)
      case n        => n.toIntOption.filter(w => w >= 1 && w <= 1000)
    }
    raw.map(_.split("\\s+").toVector.flatMap(one)) match {
      case Some(Vector(w))      => (w, w)
      case Some(Vector(lo, hi)) => (lo min hi, lo max hi)
      case _                    => (400, 400)
    }
  }

  /**
   * The first `src` URL that is woff2 — by `format("woff2")` hint, or by a
   * `.woff2` path extension when the hint is absent. None when the face only
   * offers formats we can't serve.
   */
  private def woff2Src(body: String): Option[String] =
    SrcUrlRe.findAllMatchIn(body).collectFirst {
      case m if isWoff2(url(m), Option(m.group(4))) => url(m)
    }

  private def url(m: scala.util.matching.Regex.Match): String =
    Option(m.group(1)).orElse(Option(m.group(2))).getOrElse(m.group(3)).trim

  private def isWoff2(u: String, format: Option[String]): Boolean =
    format.map(_.equalsIgnoreCase("woff2")).getOrElse(
      u.takeWhile(c => c != '?' && c != '#').toLowerCase.endsWith(".woff2")
    )

  /** Resolve `ref` against `base`; None for data:/blob: or unresolvable refs. */
  private def resolveUrl(base: String, ref: String): Option[String] = {
    val lower = ref.toLowerCase
    if (lower.startsWith("data:") || lower.startsWith("blob:")) None
    else scala.util.Try(new java.net.URI(base).resolve(ref).toString).toOption
      .filter(u => u.startsWith("http://") || u.startsWith("https://"))
  }
}
