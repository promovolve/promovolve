package promovolve.publisher.assets

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.webp.WebpWriter
import org.slf4j.LoggerFactory

import scala.util.{ Failure, Success, Try }

/**
 * Re-encode PNG/JPEG bytes as WebP using scrimage's bundled cwebp.
 *
 * Used by CreativeProcessor after Playwright renders the collapsed
 * banner — Playwright only outputs PNG/JPEG, but WebP cuts ~25–35%
 * off the byte size at visually-identical quality (q=80). Smaller
 * banner = faster paint = better viewability = better CTR.
 *
 * `encode` is fail-soft: if the native cwebp binary won't load
 * (unsupported arch, sandbox blocks subprocess, etc.) we return
 * `None` rather than throwing, so callers can fall back to the
 * original bytes without breaking the creative pipeline.
 */
object WebpEncoder {
  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Quality 80: scrimage's documented sweet spot for visually-lossless
   * output on typical screen content. Drop to 75 if banner bytes are
   * still too heavy; raise to 90 only if observed quality issues.
   */
  private val writer: WebpWriter = WebpWriter.DEFAULT.withQ(80)

  /**
   * Encode arbitrary raster bytes (anything scrimage can read — PNG,
   * JPEG, GIF, BMP) to WebP. Returns `None` on encode failure.
   */
  def encode(rasterBytes: Array[Byte]): Option[Array[Byte]] =
    Try(ImmutableImage.loader().fromBytes(rasterBytes).bytes(writer)) match {
      case Success(out) if out != null && out.nonEmpty => Some(out)
      case Success(_)                                  =>
        log.warn("WebpEncoder: encoder returned empty output")
        None
      case Failure(e) =>
        log.warn("WebpEncoder: encode failed: {}: {}", e.getClass.getSimpleName, e.getMessage)
        None
    }
}
