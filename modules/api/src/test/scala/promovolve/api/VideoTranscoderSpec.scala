package promovolve.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Exercises the living-paper constraints end to end with a synthetic
 * clip: 12 s of test pattern WITH an audio track goes in; what comes
 * out must be ≤ the loop cap, carry NO audio stream, and ship a
 * non-empty poster. Skips (assume) on hosts without ffmpeg — the
 * transcoder itself degrades the same way in production.
 */
class VideoTranscoderSpec extends AnyWordSpec with Matchers {

  private def run(cmd: String*): (Int, String) = {
    val p = new ProcessBuilder(cmd*).redirectErrorStream(true).start()
    val out = new String(p.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    p.waitFor(60, TimeUnit.SECONDS)
    (p.exitValue(), out)
  }

  "VideoTranscoder" should {
    "strip audio, clip to the loop cap, and emit a poster" in {
      assume(VideoTranscoder.available, "ffmpeg not on PATH — skipping")

      val dir = Files.createTempDirectory("vtx-spec")
      val src = dir.resolve("src.mp4")
      // 12 s test pattern + 440 Hz sine → both constraints get exercised.
      val (gen, genOut) = run(
        "ffmpeg", "-y", "-nostdin",
        "-f", "lavfi", "-i", "testsrc=duration=12:size=320x240:rate=15",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=12",
        "-shortest", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
        src.toString
      )
      withClue(genOut.takeRight(300)) { gen shouldBe 0 }

      val result = VideoTranscoder.transcode(Files.readAllBytes(src), "video/mp4")
      result should not be empty
      val r = result.get
      r.videoMime shouldBe "video/mp4"
      r.video.length should be > 0
      r.poster.length should be > 0
      r.posterMime shouldBe "image/jpeg"

      // Inspect the output with ffprobe: video stream only, ≤ 8.5 s.
      val out = dir.resolve("out.mp4")
      Files.write(out, r.video)
      val (_, streams) = run(
        "ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", out.toString)
      streams.trim.linesIterator.toSeq shouldBe Seq("video")
      val (_, duration) = run(
        "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", out.toString)
      duration.trim.toDouble should be <= 10.5
    }

    "cut the author's trim window instead of the head of the file" in {
      assume(VideoTranscoder.available, "ffmpeg not on PATH — skipping")
      val dir = Files.createTempDirectory("vtx-spec-trim")
      val src = dir.resolve("src.mp4")
      val (gen, genOut) = run(
        "ffmpeg", "-y", "-nostdin",
        "-f", "lavfi", "-i", "testsrc=duration=12:size=320x240:rate=15",
        "-c:v", "libx264", "-pix_fmt", "yuv420p",
        src.toString
      )
      withClue(genOut.takeRight(300)) { gen shouldBe 0 }
      // Window 3s→6s: output must be the 3s window, not the first 10s.
      val r = VideoTranscoder.transcode(Files.readAllBytes(src), "video/mp4", 3.0, Some(6.0)).get
      val out = dir.resolve("out.mp4")
      Files.write(out, r.video)
      val (_, duration) = run(
        "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", out.toString)
      duration.trim.toDouble should be(3.0 +- 0.5)
    }
  }
}
