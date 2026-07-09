package promovolve.api

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.`User-Agent`
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory
import promovolve.publisher.assets.{ GoogleFontCatalog, ImageStorage }

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Fetches an allow-listed Google font's latin woff2 ONCE and stores it
 * in R2 so the banner can self-host it (never calling Google at the
 * visitor's runtime). Driven at creative-publish time.
 *
 * Best-effort throughout: any failure (family not in catalog, network
 * error, parse miss, storage backend without font support) resolves to
 * a no-op — the creative then falls back to the system font baked into
 * its CSS stack. Publishing never blocks on or fails because of fonts.
 */
final class GoogleFontProvisioner(imageStorage: ImageStorage)(using system: ActorSystem[?]) {
  private given ExecutionContext = system.executionContext
  private val log = LoggerFactory.getLogger("promovolve.api.GoogleFontProvisioner")

  import org.apache.pekko.actor.typed.scaladsl.adapter.*
  private val http = Http(system.toClassic)

  // Google serves woff2 only to browsers it recognises via UA sniffing;
  // a modern Chrome UA gets the compact woff2 with unicode-range blocks.
  private val DesktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

  /**
   * Ensure the woff2 for `familyStack`'s primary family is in R2.
   *
   * For latin families this fetches the latin subset block once (deduped by
   * slug+weight across creatives). For CJK families (Noto Sans/Serif JP) the
   * latin block has no kana/kanji, so it instead fetches a `text=` subset
   * covering exactly `subsetText` (the creative's content) and stores it
   * under a per-text content key — `subsetText` is therefore REQUIRED for
   * CJK; without it we skip (a latin-only CJK woff2 would be useless).
   *
   * Returns Future[Unit] that always succeeds (errors are logged).
   */
  def ensure(
      familyStack: String,
      cssWeight: Option[Int] = None,
      subsetText: Option[String] = None
  ): Future[Unit] = {
    GoogleFontCatalog.resolve(familyStack, cssWeight) match {
      case None                         => Future.unit // generic/system family → system fallback
      case Some((slug, weight, family)) =>
        // The creative is CJK if its text has CJK code points; then every font
        // is fetched as a `text=` subset (CJK is too big to ship whole), keyed
        // by subsetKey. Latin creatives use the deduped latin block.
        val text = subsetText.map(_.trim).filter(_.nonEmpty)
        val cjk = text.exists(GoogleFontCatalog.hasCjk)
        val variant = if (cjk) GoogleFontCatalog.subsetKey(text.get) else "latin"
        imageStorage.fontExists(slug, variant).flatMap {
          case true  => Future.unit // already self-hosted (this slug+variant) — dedup
          case false => fetchAndStore(family, weight, slug, variant, if (cjk) text else None)
        }.recover { case e =>
          // Most failures here are "not a Google font" (licensed / non-OFL) →
          // the creative just keeps its system fallback. Info, not warn.
          log.info("font not self-hosted: {} w{} ({}/{}): {}", familyStack, weight, slug, variant, e.getMessage)
        }
    }
  }

  private def fetchAndStore(
      family: String, weight: Int, slug: String, variant: String, subsetText: Option[String]
  ): Future[Unit] = {
    // `:wght@N` pins the exact weight so the named instance (e.g. Montserrat
    // Thin = 100) renders true-to-LP, not the default 400. `&text=` (CJK only)
    // asks Google for a subset woff2 covering just those code points.
    //
    // Single-weight families (e.g. Prata: 400 only) make css2 REJECT a
    // pinned nonexistent weight with HTTP 400 — even though the LP itself
    // renders such families at other weights via browser bold-synthesis.
    // On that rejection, retry UNPINNED (Google serves the family's
    // available weight) and store the bytes under the REQUESTED slug so
    // the banner's derived `fonts/<slug>-<w>-<variant>.woff2` URL still
    // resolves — the closest available face beats the system fallback.
    def cssUrlFor(pinned: Boolean): String = {
      val fam = urlEncode(family)
      val base =
        if (pinned) s"https://fonts.googleapis.com/css2?family=$fam:wght@$weight&display=swap"
        else s"https://fonts.googleapis.com/css2?family=$fam&display=swap"
      subsetText.fold(base)(t => s"$base&text=${urlEncode(t)}")
    }
    def attempt(pinned: Boolean): Future[Unit] = {
      val cssReq = HttpRequest(uri = cssUrlFor(pinned), headers = List(`User-Agent`(DesktopUA)))
      http.singleRequest(cssReq).flatMap { resp =>
        if (!resp.status.isSuccess) {
          resp.entity.discardBytes()
          if (pinned && resp.status.intValue == 400) {
            log.info("css2 rejected {} w{} (weight not offered) — retrying unpinned", family, weight)
            attempt(pinned = false)
          } else
            Future.failed(new RuntimeException(s"css2 HTTP ${resp.status}"))
        } else {
          Unmarshal(resp.entity).to[String].flatMap { css =>
            // text= returns a single @font-face (no unicode-range split); the
            // fallback (first font url) picks its subset-delivery src correctly.
            GoogleFontProvisioner.firstFontUrl(css) match {
              case None           => Future.failed(new RuntimeException("no font url in css2 response"))
              case Some(woff2Url) =>
                http.singleRequest(HttpRequest(uri = woff2Url, headers = List(`User-Agent`(DesktopUA))))
                  .flatMap { fr =>
                    if (!fr.status.isSuccess) {
                      fr.entity.discardBytes()
                      Future.failed(new RuntimeException(s"woff2 HTTP ${fr.status}"))
                    } else {
                      Unmarshal(fr.entity).to[Array[Byte]].flatMap { bytes =>
                        imageStorage.storeFont(slug, bytes, variant).map { _ =>
                          log.info("self-hosted font {} w{} -> fonts/{}-{}.woff2 ({} bytes)", family, weight, slug,
                            variant, bytes.length)
                        }
                      }
                    }
                  }
            }
          }
        }
      }
    }
    attempt(pinned = true)
  }

  private def urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

object GoogleFontProvisioner {

  /**
   * Pull the font-file URL out of a css2 stylesheet's `src: url(...)`.
   *
   * Two response shapes:
   *   - Full family (no `text=`): Google splits @font-face blocks by
   *     unicode-range, each `url(.../xxx.woff2)`; we prefer the `/* latin */`
   *     block (the common Western glyph set).
   *   - `text=` SUBSET (always used for CJK): a single @font-face whose src is
   *     the subset-delivery form `url(https://fonts.gstatic.com/l/font?kit=...
   *     &v=vN)` — NO `.woff2` suffix. The old `\.woff2`-anchored regex missed
   *     this entirely, so NO CJK subset font was ever provisioned (→ 404).
   *
   * So we match any gstatic font URL inside `url(...)`, not just `*.woff2`.
   * (css2 `url(...)` entries are only ever font sources, so this stays safe.)
   */
  def firstFontUrl(css: String): Option[String] = {
    val UrlRe = """url\((https://fonts\.gstatic\.com/[^)]+)\)""".r
    // Prefer the block explicitly commented `latin` (not latin-ext/etc).
    val latinIdx = css.indexOf("/* latin */")
    val searchFrom = if (latinIdx >= 0) css.substring(latinIdx) else css
    UrlRe.findFirstMatchIn(searchFrom).map(_.group(1))
      .orElse(UrlRe.findFirstMatchIn(css).map(_.group(1)))
  }
}
