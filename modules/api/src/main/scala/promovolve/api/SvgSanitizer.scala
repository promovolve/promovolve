package promovolve.api

import promovolve.publisher.{ ImageAsset, ImageAssetRepo }
import promovolve.publisher.assets.ImageStorage

import java.time.Instant
import scala.concurrent.{ ExecutionContext, Future }

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
  def sanitize(bytes: Array[Byte]): Array[Byte] =
    try {
      val text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
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
    } catch { case _: Throwable => bytes }

  /**
   * Best-effort dimension extraction from an SVG document. Reads
   * `width`/`height` attrs first, falls back to `viewBox`. Returns
   * (0, 0) if neither is present or parses fails — caller stores the
   * asset row with zero dims, which is fine: the renderer scales SVG
   * to its container regardless.
   */
  def extractDims(bytes: Array[Byte]): (Int, Int) =
    try {
      val text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
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
    } catch { case _: Throwable => (0, 0) }

  /**
   * Register-time step for a presigned SVG upload. The PUT landed at
   * `s3Key` as an opaque download (ImageStorage.putContentType), so
   * nothing has been servable yet. Sanitize it, rewrite the same key as
   * image/svg+xml, then record the image_asset row. Not best-effort: if
   * the object is missing, fail and record nothing.
   */
  def registerUpload(
      storage: ImageStorage,
      imgRepo: ImageAssetRepo,
      hash: String,
      s3Key: String,
      fallbackDims: (Int, Int)
  )(using ExecutionContext): Future[(String, String, Int, Int)] =
    storage.fetchObject(s3Key).flatMap {
      case Some(raw) =>
        val cleaned = sanitize(raw)
        val (w, h) = extractDims(cleaned) match {
          case (w, h) if w > 0 && h > 0 => (w, h)
          case _                        => fallbackDims
        }
        for {
          key <- storage.store(hash, cleaned, ImageStorage.SvgMime)
          _ <- imgRepo.put(ImageAsset(hash, key, ImageStorage.SvgMime, w, h, Instant.now()))
        } yield (key, ImageStorage.SvgMime, w, h)
      case None =>
        Future.failed(new RuntimeException(s"uploaded object not found at $s3Key — did the PUT succeed?"))
    }
}
