package promovolve.publisher.assets

/**
 * Resolves a creative's `font-family` stack to the R2 object stem we
 * self-host it under, and decides how to subset it.
 *
 * There is no curated allow-list: Google Fonts only serves free-to-embed
 * (OFL/Apache) families, so we simply *try* to fetch whatever family a
 * creative references (see [[GoogleFontProvisioner]]). If Google serves it,
 * we self-host it; a licensed / non-Google family's css2 fetch just fails and
 * the creative falls back to the snapped system font in its CSS stack. Only
 * generic CSS families and common system faces (sans-serif, Georgia, …) are
 * skipped outright via [[isGeneric]].
 *
 * The R2 stem is derived from the family name (lowercased, spaces→hyphens) so
 * the server and the banner ([[platform/banner-component/src/font-catalog.ts]])
 * compute the SAME slug without sharing a list. Full key:
 * `fonts/<slug>-<weight>-<variant>.woff2`, where variant is `latin` (the latin
 * subset block, deduped across creatives) or — when the creative has CJK text
 * ([[hasCjk]]) — [[subsetKey]] of the creative's text (a per-creative `text=`
 * subset, since CJK is too large to ship whole). Served from the CDN origin.
 */
object GoogleFontCatalog {

  /**
   * Display-cased first family from a stack — quotes stripped. May still
   * carry a weight/style suffix (e.g. "Montserrat Thin"); use [[normalize]]
   * to split off the weight.
   */
  def familyName(stack: String): String =
    stack.split(",").headOption.getOrElse("").trim
      .stripPrefix("\"").stripSuffix("\"")
      .stripPrefix("'").stripSuffix("'")
      .trim

  // Trailing tokens we peel off a family name to recover the BASE family +
  // a numeric weight. LPs often author the named instance ("Montserrat
  // Thin") as the family, so the base ("montserrat") never matches Google's
  // css2 family param. Multi-word families (Open Sans, DM Serif Display,
  // Roboto Slab) are safe because none of their tail words are weight tokens.
  private val weightWord: Map[String, Int] = Map(
    "thin" -> 100, "hairline" -> 100,
    "extralight" -> 200, "ultralight" -> 200,
    "light" -> 300,
    "regular" -> 400, "normal" -> 400, "book" -> 400,
    "medium" -> 500,
    "semibold" -> 600, "demibold" -> 600,
    "bold" -> 700,
    "extrabold" -> 800, "ultrabold" -> 800,
    "black" -> 900, "heavy" -> 900
  )
  // Modifiers that combine with the next word ("extra light" -> 200).
  private val weightModifier = Set("extra", "ultra", "semi", "demi")
  // Style/format descriptors that carry no weight but appear as a trailing
  // token on the family name. "variable"/"vf" cover variable-font builds
  // (e.g. @fontsource "Montserrat Variable") that LPs reference directly.
  private val styleWord = Set("italic", "oblique", "variable", "vf")
  private def peelable(t: String): Boolean =
    weightWord.contains(t) || weightModifier(t) || styleWord(t)

  /**
   * Split a family-stack's first family into a base lookup-key, the base
   * family in its ORIGINAL casing (for Google's css2 `family=` param,
   * which is case-sensitive — "DM Sans", not "dm sans"), and an optional
   * weight parsed from a trailing descriptor.
   *   "Montserrat Thin"       -> ("montserrat", "Montserrat", Some(100))
   *   "Montserrat Extra Bold" -> ("montserrat", "Montserrat", Some(800))
   *   "Open Sans"             -> ("open sans",  "Open Sans",  None)
   *   "Montserrat"            -> ("montserrat", "Montserrat", None)
   */
  def normalize(stack: String): (String, String, Option[Int]) = {
    val display = familyName(stack).split("\\s+").filter(_.nonEmpty).toVector
    val lower = display.map(_.toLowerCase)
    var n = display.length
    while (n > 1 && peelable(lower(n - 1))) n -= 1
    val peeled = lower.drop(n) // descriptor tokens, lowercased, in order
    val phrase = peeled.filterNot(styleWord).mkString // "extra"+"light" -> "extralight"
    val weight = weightWord.get(phrase).orElse(peeled.flatMap(weightWord.get).lastOption)
    (lower.take(n).mkString(" "), display.take(n).mkString(" "), weight)
  }

  /** Lowercased base lookup-key (weight suffix stripped). */
  def familyKey(stack: String): String = normalize(stack)._1

  // Generic CSS families + common system/licensed faces. These are never on
  // Google Fonts (or are the snapped fallback bucket itself), so we skip them
  // rather than waste a css2 fetch that would 404. Mirror of the banner's set.
  private val genericFamilies = Set(
    "sans-serif", "serif", "monospace", "cursive", "fantasy",
    "system-ui", "ui-sans-serif", "ui-serif", "ui-monospace", "ui-rounded",
    "georgia", "times", "times new roman", "arial", "helvetica", "helvetica neue",
    "courier", "courier new", "verdana", "tahoma", "trebuchet ms", "segoe ui"
  )

  /** True for a generic/system family we never try to self-host. */
  def isGeneric(familyKey: String): Boolean = genericFamilies(familyKey)

  private def slugify(familyKey: String): String = familyKey.replace(" ", "-")

  /**
   * R2 slug stem for a family stack (lowercased, spaces→hyphens), or None for
   * a generic/system family. Derived — no list.
   */
  def slugFor(stack: String): Option[String] = {
    val key = familyKey(stack)
    if (key.isEmpty || isGeneric(key)) None else Some(slugify(key))
  }

  /**
   * Resolve a stack (+ optional CSS font-weight) to (per-weight R2 slug stem,
   * numeric weight, base family name for css2). Weight precedence: descriptor
   * in the family name > explicit CSS weight > 400. None only for generic /
   * system families. Full R2 key = `fonts/<stem>-<variant>.woff2`.
   */
  def resolve(stack: String, cssWeight: Option[Int] = None): Option[(String, Int, String)] = {
    val (key, display, nameWeight) = normalize(stack)
    if (key.isEmpty || isGeneric(key)) None
    else {
      val w = nameWeight.orElse(cssWeight).filter(validWeight).getOrElse(400)
      Some((s"${slugify(key)}-$w", w, display))
    }
  }

  /**
   * Whether text contains CJK (Japanese/Korean/Chinese) code points — the
   * signal that a font must be `text=`-subset rather than served whole. A
   * property of the TEXT, so the server and banner agree without a font list.
   * Mirror of the banner's hasCjk.
   */
  def hasCjk(text: String): Boolean =
    text.codePoints().anyMatch { cp =>
      (cp >= 0x3040 && cp <= 0x30FF) || // hiragana + katakana
      (cp >= 0x3400 && cp <= 0x9FFF) || // CJK ideographs (kanji / hanzi, incl. ext A)
      (cp >= 0xF900 && cp <= 0xFAFF) || // CJK compatibility ideographs
      (cp >= 0xFF66 && cp <= 0xFF9F) || // halfwidth katakana
      (cp >= 0xAC00 && cp <= 0xD7A3) || // hangul syllables (Korean)
      (cp >= 0x1100 && cp <= 0x11FF) // hangul jamo
    }

  /**
   * Stable content key for a `text=` subset — identical to the banner's
   * `subsetKey` (banner/font-catalog.ts). Canonicalizes the text to its sorted
   * set of unique Unicode code points, then FNV-1a (32-bit) over the UTF-8
   * bytes → 8 lowercase hex. The server stores the subset woff2 under
   * `fonts/<stem>-<subsetKey>.woff2` and the banner derives the same URL; a
   * mismatch merely 404s → the system (Mincho/serif) fallback.
   */
  def subsetKey(text: String): String = {
    val cps = text.codePoints().toArray.distinct.sorted
    val canonical = new String(cps, 0, cps.length)
    val bytes = canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    var h = 0x811C9DC5L
    bytes.foreach { b =>
      h = (h ^ (b & 0xFFL)) & 0xFFFFFFFFL
      h = (h * 0x01000193L) & 0xFFFFFFFFL
    }
    f"$h%08x"
  }

  private def validWeight(w: Int): Boolean = w >= 100 && w <= 900 && w % 100 == 0
}
