package promovolve.api

/**
 * Strip executable content from uploaded SVG before storing on the
 * CDN. Required because banner-component renders ImageItem via
 * `<img src=...>` (which sandboxes SVG — no script execution), but
 * the CDN URL is shareable: a victim opening the asset URL directly
 * gets full SVG semantics, including any `<script>`, `on*` handlers,
 * or `javascript:` hrefs the uploader smuggled in.
 *
 * Regex-based scrubber rather than a real XML parser. Trade-off:
 *   + No XML parser dependency, no namespace handling overhead.
 *   + Fast — single pass per pattern, ~10us for typical icon SVG.
 *   - A determined attacker with HTML-entity-encoded payloads or
 *     CDATA tricks may slip past. Acceptable for the authenticated
 *     advertiser-only upload path; revisit if SVG ingest opens to a
 *     less-trusted source.
 */
object SvgSanitizer {

  private val ScriptBlock = "(?is)<\\s*script\\b[^>]*>.*?<\\s*/\\s*script\\s*>".r
  private val ScriptSelfClosing = "(?is)<\\s*script\\b[^/>]*/\\s*>".r
  private val ForeignObject = "(?is)<\\s*foreignObject\\b[^>]*>.*?<\\s*/\\s*foreignObject\\s*>".r
  private val IframeBlock = "(?is)<\\s*iframe\\b[^>]*>.*?<\\s*/\\s*iframe\\s*>".r
  private val ObjectBlock = "(?is)<\\s*object\\b[^>]*>.*?<\\s*/\\s*object\\s*>".r
  private val EmbedTag = "(?is)<\\s*embed\\b[^/>]*/?\\s*>".r
  private val OnHandlerAttr = "(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)".r
  private val JsHrefAttr = "(?is)\\s+(?:xlink:)?href\\s*=\\s*(\"\\s*javascript:[^\"]*\"|'\\s*javascript:[^']*')".r

  /**
   * Returns sanitized SVG bytes. Input is decoded as UTF-8; output is
   * re-encoded UTF-8. If decoding fails, returns input unchanged
   * (caller should still treat as opaque bytes).
   */
  def sanitize(bytes: Array[Byte]): Array[Byte] = {
    val text =
      try new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
      catch { case _: Throwable => return bytes }
    var out = text
    out = ScriptBlock.replaceAllIn(out, "")
    out = ScriptSelfClosing.replaceAllIn(out, "")
    out = ForeignObject.replaceAllIn(out, "")
    out = IframeBlock.replaceAllIn(out, "")
    out = ObjectBlock.replaceAllIn(out, "")
    out = EmbedTag.replaceAllIn(out, "")
    out = OnHandlerAttr.replaceAllIn(out, "")
    out = JsHrefAttr.replaceAllIn(out, "")
    out.getBytes(java.nio.charset.StandardCharsets.UTF_8)
  }

  /**
   * Best-effort dimension extraction from an SVG document. Reads
   * `width`/`height` attrs first, falls back to `viewBox`. Returns
   * (0, 0) if neither is present or parses fails — caller stores the
   * asset row with zero dims, which is fine: the renderer scales SVG
   * to its container regardless.
   */
  def extractDims(bytes: Array[Byte]): (Int, Int) = {
    val text =
      try new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
      catch { case _: Throwable => return (0, 0) }
    "(?is)<\\s*svg\\b[^>]*>".r.findFirstIn(text) match {
      case None         => (0, 0)
      case Some(svgTag) =>
        val widthAttr = "(?i)\\swidth\\s*=\\s*\"([\\d.]+)".r.findFirstMatchIn(svgTag).map(_.group(1))
        val heightAttr = "(?i)\\sheight\\s*=\\s*\"([\\d.]+)".r.findFirstMatchIn(svgTag).map(_.group(1))
        (widthAttr, heightAttr) match {
          case (Some(w), Some(h)) => (w.toDouble.toInt, h.toDouble.toInt)
          case _                  =>
            "(?i)\\sviewBox\\s*=\\s*\"\\s*([\\d.\\-]+)\\s+([\\d.\\-]+)\\s+([\\d.]+)\\s+([\\d.]+)".r
              .findFirstMatchIn(svgTag) match {
              case Some(m) => (m.group(3).toDouble.toInt, m.group(4).toDouble.toInt)
              case None    => (0, 0)
            }
        }
    }
  }
}
