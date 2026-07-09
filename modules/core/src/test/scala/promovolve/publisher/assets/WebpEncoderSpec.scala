package promovolve.publisher.assets

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Validates that scrimage's bundled cwebp loads on this host and
 * produces smaller output than the source PNG for a representative
 * banner-sized image.
 */
class WebpEncoderSpec extends AnyWordSpec with Matchers {

  // Build a 300×250 PNG with a smooth gradient + a couple of colored
  // shapes — closer to a real banner than a flat fill (flat fills
  // compress trivially in both PNG and WebP, hiding the spread).
  private def syntheticBannerPng(w: Int = 300, h: Int = 250): Array[Byte] = {
    val img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    try {
      for (y <- 0 until h; x <- 0 until w) {
        val r = (x * 255 / w).max(0).min(255)
        val gv = (y * 255 / h).max(0).min(255)
        val b = ((x + y) * 255 / (w + h)).max(0).min(255)
        img.setRGB(x, y, new Color(r, gv, b).getRGB)
      }
      g.setColor(new Color(255, 255, 255))
      g.fillRect(20, 20, 80, 60)
      g.setColor(new Color(40, 80, 200))
      g.fillRect(180, 140, 100, 80)
    } finally g.dispose()
    val bos = new ByteArrayOutputStream()
    ImageIO.write(img, "png", bos)
    bos.toByteArray
  }

  "WebpEncoder.encode" should {

    "produce non-empty WebP bytes from a PNG" in {
      val png = syntheticBannerPng()
      val out = WebpEncoder.encode(png)
      out shouldBe defined
      out.get.length should be > 0
    }

    "produce WebP smaller than source PNG for typical banner content" in {
      val png = syntheticBannerPng()
      val out = WebpEncoder.encode(png).getOrElse(fail("encode returned None"))
      // PNG output of a gradient is fairly heavy; WebP @ q=80 should
      // win by a wide margin. Use a 30% threshold for a defensive
      // assertion that won't flap on minor encoder version changes.
      out.length.toDouble should be < (png.length * 0.7)
    }

    "start with the WebP magic header (RIFF...WEBP)" in {
      val png = syntheticBannerPng()
      val out = WebpEncoder.encode(png).getOrElse(fail("encode returned None"))
      new String(out.take(4), "ASCII") shouldBe "RIFF"
      new String(out.slice(8, 12), "ASCII") shouldBe "WEBP"
    }
  }
}
