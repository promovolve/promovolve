package promovolve.api

import org.slf4j.LoggerFactory

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}

/**
 * Server-side image compression for asset ingest. Runs in `storeIfNew`
 * before the SHA hash + R2 put, so every downstream consumer sees the
 * compressed bytes and R2 only stores the optimised version.
 *
 * Mirrors the client-side pipeline in `asset-modal.ts::prepareForUpload`
 * (long-edge cap of 2000px, quality 0.82) so direct-upload and
 * server-side-only entry paths (URL imports, LP-to-Creative Playwright
 * scrape) end up producing roughly equivalent bytes.
 *
 * Format policy:
 *   - JPEG in → JPEG out (re-encoded at quality 0.82).
 *   - PNG with alpha → PNG out (dimension-clamp only; JPEG would
 *     lose transparency).
 *   - PNG without alpha → JPEG out (smaller, equivalent quality).
 *   - GIF / WebP → pass through unchanged. GIF animation would be
 *     lost on re-encode; WebP is already optimal.
 *   - Decode failure (HEIC, AVIF, corrupted) → pass through. Better
 *     to store originals than reject the upload.
 *
 * No WebP output — that'd need a native-bound lib
 * (`sejda-webp-imageio` or similar). Stays JDK-only; direct uploads
 * from the designer still produce WebP on the client, so the library
 * will have a mix of WebP (direct-upload) and JPEG/PNG (URL-import,
 * LP-to-Creative). Mixed formats are harmless at render time.
 */
object ImageCompression {
  private val log = LoggerFactory.getLogger(getClass)
  private val MaxEdge = 2000
  private val JpegQuality = 0.82f

  /** Compress bytes if beneficial. Returns (bytes, mime, width, height).
    * Never throws — on any decode/encode failure, returns the input
    * bytes with detected dims (or 0/0 if dim detection also failed).
    */
  def compress(bytes: Array[Byte], mime: String): (Array[Byte], String, Int, Int) = {
    // SVG: sanitize (strip script/event handlers/javascript: hrefs)
    // and pass through. ImageIO has no SVG decoder, so dims come from
    // the document's width/height attrs or viewBox.
    if (mime == "image/svg+xml") {
      val cleaned = SvgSanitizer.sanitize(bytes)
      val (w, h) = SvgSanitizer.extractDims(cleaned)
      log.info(
        "image-compress svg-sanitize mime={} bytes={}→{} dims={}x{}",
        mime, bytes.length, cleaned.length, w, h,
      )
      return (cleaned, mime, w, h)
    }

    // Pass-through formats — touch nothing, just read dimensions so
    // the caller still gets accurate width/height for the asset row.
    if (mime == "image/gif" || mime == "image/webp") {
      val (w, h) = detectDims(bytes)
      log.info(
        "image-compress pass-through mime={} bytes={} dims={}x{}",
        mime, bytes.length, w, h,
      )
      return (bytes, mime, w, h)
    }

    try {
      val src = ImageIO.read(new ByteArrayInputStream(bytes))
      if (src == null) {
        log.warn("image-compress decode returned null mime={} bytes={}", mime, bytes.length)
        return fallback(bytes, mime)
      }

      val srcW = src.getWidth
      val srcH = src.getHeight
      val scale = math.min(1.0, MaxEdge.toDouble / math.max(srcW, srcH).toDouble)
      val targetW = math.max(1, math.round(srcW * scale).toInt)
      val targetH = math.max(1, math.round(srcH * scale).toInt)

      val sourceHasAlpha = src.getColorModel.hasAlpha
      val keepAsPng = mime == "image/png" && sourceHasAlpha
      val (outMime, formatName) =
        if (keepAsPng) ("image/png", "png")
        else ("image/jpeg", "jpeg")

      val resampled = resample(src, targetW, targetH, keepAsPng)

      val baos = new ByteArrayOutputStream()
      if (formatName == "jpeg") writeJpeg(resampled, baos)
      else ImageIO.write(resampled, "png", baos)
      val out = baos.toByteArray

      // Safety net: if we didn't downsample AND the new encoding came
      // out larger than the input, keep the original. Happens for
      // already-optimised inputs (e.g., PNGs of flat-color icons).
      if (scale == 1.0 && out.length >= bytes.length) {
        log.info(
          "image-compress bail mime={} bytes={} (re-encode would grow to {}) dims={}x{}",
          mime, bytes.length, out.length, srcW, srcH,
        )
        return (bytes, mime, srcW, srcH)
      }

      val savings = if (bytes.length > 0) 100 - math.round(out.length.toDouble * 100 / bytes.length).toInt else 0
      log.info(
        "image-compress mime={}→{} bytes={}→{} (-{}%) dims={}x{}→{}x{}",
        mime, outMime, bytes.length, out.length, savings, srcW, srcH, targetW, targetH,
      )
      (out, outMime, targetW, targetH)
    } catch {
      case t: Throwable =>
        log.warn("image-compress failed mime={} bytes={} err={}", mime, bytes.length, t.getMessage)
        fallback(bytes, mime)
    }
  }

  private def fallback(bytes: Array[Byte], mime: String): (Array[Byte], String, Int, Int) = {
    val (w, h) = detectDims(bytes)
    (bytes, mime, w, h)
  }

  private def resample(src: BufferedImage, w: Int, h: Int, keepAlpha: Boolean): BufferedImage = {
    val typeFor =
      if (keepAlpha) BufferedImage.TYPE_INT_ARGB
      else BufferedImage.TYPE_INT_RGB
    val needsResize = w != src.getWidth || h != src.getHeight
    val needsAlphaFlatten = !keepAlpha && src.getColorModel.hasAlpha
    if (!needsResize && !needsAlphaFlatten) return src

    val dst = new BufferedImage(w, h, typeFor)
    val g = dst.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      // If we're flattening alpha to JPEG, paint over white first so
      // semi-transparent pixels composite sensibly rather than going
      // black (JPEG has no alpha channel).
      if (needsAlphaFlatten) {
        g.setColor(java.awt.Color.WHITE)
        g.fillRect(0, 0, w, h)
      }
      g.drawImage(src, 0, 0, w, h, null)
    } finally g.dispose()
    dst
  }

  private def writeJpeg(img: BufferedImage, out: ByteArrayOutputStream): Unit = {
    val writers = ImageIO.getImageWritersByFormatName("jpeg")
    if (!writers.hasNext) { ImageIO.write(img, "jpeg", out); return }
    val writer = writers.next()
    val ios = ImageIO.createImageOutputStream(out)
    try {
      writer.setOutput(ios)
      val param = writer.getDefaultWriteParam()
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
      param.setCompressionQuality(JpegQuality)
      writer.write(null, new IIOImage(img, null, null), param)
    } finally {
      writer.dispose()
      ios.close()
    }
  }

  private def detectDims(bytes: Array[Byte]): (Int, Int) = {
    // WebP first — the JDK's javax.imageio ships no WebP reader, so
    // ImageIO.read returns null and we'd lose dimensions for every
    // WebP upload (direct designer uploads via client-side encode,
    // plus any URL import where the CDN honoured Accept: image/webp).
    // Parse the RIFF header directly instead.
    if (isWebP(bytes)) readWebPDims(bytes).getOrElse((0, 0)) else {
      try {
        val img = ImageIO.read(new ByteArrayInputStream(bytes))
        if (img != null) (img.getWidth, img.getHeight) else (0, 0)
      } catch { case _: Throwable => (0, 0) }
    }
  }

  private def isWebP(bytes: Array[Byte]): Boolean =
    bytes.length >= 12 &&
      bytes(0) == 'R' && bytes(1) == 'I' && bytes(2) == 'F' && bytes(3) == 'F' &&
      bytes(8) == 'W' && bytes(9) == 'E' && bytes(10) == 'B' && bytes(11) == 'P'

  /** WebP dimension parser. Covers all three format variants:
    *
    *   VP8  (simple lossy)  — 14-bit width/height at offsets 26/28, after
    *                          the 3-byte sync code 0x9D 0x01 0x2A at 23.
    *   VP8L (lossless)      — packed 14-bit width/height starting at
    *                          offset 21, after the 0x2F signature byte.
    *   VP8X (extended)      — canvas width-1 / height-1 as 24-bit LE
    *                          at offsets 24 and 27.
    *
    * Spec references: RFC 6386 (VP8), RFC 9649 (WebP container).
    */
  private def readWebPDims(bytes: Array[Byte]): Option[(Int, Int)] = {
    if (bytes.length < 30) return None
    val fourcc = new String(bytes, 12, 4, "US-ASCII")
    fourcc match {
      case "VP8 " =>
        // Sync code 0x9D 0x01 0x2A must appear at offset 23.
        if ((bytes(23) & 0xFF) != 0x9D || (bytes(24) & 0xFF) != 0x01 || (bytes(25) & 0xFF) != 0x2A) None
        else {
          val w = (bytes(26) & 0xFF) | ((bytes(27) & 0x3F) << 8)
          val h = (bytes(28) & 0xFF) | ((bytes(29) & 0x3F) << 8)
          Some((w, h))
        }
      case "VP8L" =>
        // 0x2F signature byte at offset 20, then packed 14/14-bit
        // width/height across the next four bytes.
        if ((bytes(20) & 0xFF) != 0x2F) None
        else {
          val b1 = bytes(21) & 0xFF
          val b2 = bytes(22) & 0xFF
          val b3 = bytes(23) & 0xFF
          val b4 = bytes(24) & 0xFF
          val w = (((b2 & 0x3F) << 8) | b1) + 1
          val h = (((b4 & 0x0F) << 10) | (b3 << 2) | ((b2 & 0xC0) >> 6)) + 1
          Some((w, h))
        }
      case "VP8X" =>
        val w = ((bytes(24) & 0xFF) | ((bytes(25) & 0xFF) << 8) | ((bytes(26) & 0xFF) << 16)) + 1
        val h = ((bytes(27) & 0xFF) | ((bytes(28) & 0xFF) << 8) | ((bytes(29) & 0xFF) << 16)) + 1
        Some((w, h))
      case _ => None
    }
  }
}
