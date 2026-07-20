package promovolve.api

import java.nio.file.{ Files, Path }
import java.util.concurrent.TimeUnit

/**
 * Normalizes advertiser video backgrounds into "living paper" — video
 * subordinated to the magazine metaphor, with the constraints baked into
 * the FILE rather than left to renderer politeness:
 *
 *   - audio track STRIPPED (a muted attribute is a promise; no track is a fact)
 *   - clipped to a short loop (LoopSeconds)
 *   - downscaled to what a magazine page needs (MaxWidth, even height)
 *   - re-encoded H.264 yuv420p + faststart (universal, streams from byte 0)
 *   - a poster frame extracted for the image fallback (VideoBg.poster)
 *
 * Uploads go browser-direct to R2 (presigned PUT), so the server first
 * touches the bytes at PUBLISH time — CreativeProcessor runs this while
 * rewriting pages, the same slot where LP images get rehosted. A raw
 * 200 MB hero upload must never be what visitors' phones download.
 *
 * Best-effort by design: no ffmpeg on the host (dev laptops, slim CI) or
 * any failure → None, and the caller keeps the original bytes — exactly
 * how the tolerant image pass treats an unfetchable URL.
 */
object VideoTranscoder {

  final case class Result(
      video: Array[Byte],
      videoMime: String,
      poster: Array[Byte],
      posterMime: String
  )

  // 15s = the industry's shortest standard video spot, so an advertiser's
  // existing 15s cut drops in as living paper without re-editing
  // (silenced + downscaled, ~1.8 MB at 720p/CRF-27).
  private val LoopSeconds = 15
  private val MaxWidth = 1280
  private val TimeoutSeconds = 120L

  private val log = org.slf4j.LoggerFactory.getLogger("promovolve.api.VideoTranscoder")

  /** One probe per JVM: is an ffmpeg binary on PATH? */
  lazy val available: Boolean =
    try {
      val p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
      p.getInputStream.readAllBytes()
      p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch {
      case _: Exception => false
    }

  /**
   * Synchronous and CPU/IO heavy — call from a blocking dispatcher
   * (CreativeProcessor already lives on blocking-io-dispatcher).
   *
   * `startSec`/`endSec` carry the author's trim window (videoBg
   * inSec/outSec): the output BEGINS at startSec and runs to endSec,
   * capped at LoopSeconds — the delivered file IS the trimmed loop, so
   * the caller must clear the trim fields afterwards (the renderer
   * would otherwise seek into a file that starts at zero).
   */
  def transcode(
      bytes: Array[Byte],
      mime: String,
      startSec: Double = 0.0,
      endSec: Option[Double] = None
  ): Option[Result] =
    if (!available) {
      log.warn("ffmpeg not available — video background kept as uploaded ({} bytes, {})", bytes.length, mime)
      None
    } else {
      val dir = Files.createTempDirectory("vtx")
      val ext = if (mime.contains("webm")) "webm" else "mp4"
      val in = dir.resolve(s"in.$ext")
      val out = dir.resolve("out.mp4")
      val poster = dir.resolve("poster.jpg")
      try {
        Files.write(in, bytes)
        val start = math.max(0.0, startSec)
        val window = endSec.filter(_ > start).map(_ - start).getOrElse(LoopSeconds.toDouble)
        val durationSec = math.min(window, LoopSeconds.toDouble)
        // && short-circuits: the poster pass only runs off a good encode.
        // -ss BEFORE -i: input seeking, frame-accurate under re-encode.
        val ok = run(
          dir,
          "ffmpeg", "-y", "-nostdin",
          "-ss", f"$start%.3f",
          "-i", in.toString,
          "-t", f"$durationSec%.3f",
          "-an", // strip audio at the file level
          // Never upscale; -2 keeps the height even for yuv420p.
          "-vf", s"scale=min($MaxWidth\\,iw):-2",
          "-c:v", "libx264",
          "-preset", "veryfast",
          "-crf", "27",
          "-pix_fmt", "yuv420p",
          "-movflags", "+faststart",
          out.toString
        ) && run(
          dir,
          "ffmpeg", "-y", "-nostdin",
          "-i", out.toString,
          "-frames:v", "1",
          "-q:v", "3",
          poster.toString
        )
        if (!ok) None
        else {
          val videoBytes = Files.readAllBytes(out)
          val posterBytes = Files.readAllBytes(poster)
          if (videoBytes.isEmpty || posterBytes.isEmpty) None
          else {
            log.info(
              "video transcoded: {} bytes ({}) → {} bytes mp4 + {} bytes poster",
              Integer.valueOf(bytes.length), mime, Integer.valueOf(videoBytes.length),
              Integer.valueOf(posterBytes.length)
            )
            Some(Result(videoBytes, "video/mp4", posterBytes, "image/jpeg"))
          }
        }
      } catch {
        case e: Exception =>
          log.warn("video transcode failed: {}", e.toString)
          None
      } finally {
        // Best-effort temp cleanup; a leaked temp dir is a warning, not a failure.
        try {
          Files.deleteIfExists(out)
          Files.deleteIfExists(poster)
          Files.deleteIfExists(in)
          Files.deleteIfExists(dir)
        } catch { case _: Exception => () }
      }
    }

  private def run(cwd: Path, cmd: String*): Boolean = {
    val p = new ProcessBuilder(cmd*).directory(cwd.toFile).redirectErrorStream(true).start()
    // Drain output so ffmpeg can't block on a full pipe; keep the tail
    // for the failure log.
    val outBytes = p.getInputStream.readAllBytes()
    val finished = p.waitFor(TimeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
      p.destroyForcibly()
      log.warn("ffmpeg timed out after {}s", TimeoutSeconds)
      false
    } else if (p.exitValue() != 0) {
      val tail = new String(outBytes, java.nio.charset.StandardCharsets.UTF_8).takeRight(400)
      log.warn("ffmpeg exit {}: …{}", p.exitValue(), tail)
      false
    } else true
  }
}
