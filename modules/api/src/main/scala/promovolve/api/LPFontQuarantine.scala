package promovolve.api

import org.slf4j.LoggerFactory
import promovolve.browser.{ LPAnalysisResult, LPCaptured, LPOriginalFont }
import promovolve.publisher.assets.{ GoogleFontCatalog, ImageStorage }

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Parks the LP's OWN font files in quarantine (fonts/orig/<hash>.woff2) and
 * describes them on the result as (family, weight, hash) offers. NOTHING
 * serves from the quarantine key: the offer only goes live if the advertiser
 * confirms license rights at publish, when CreativeProcessor copies the
 * bytes to the live catalog key.
 *
 * Shared by BOTH analyze dispatch paths — the crawler-tier LPWorker runner
 * (LPAnalysisRunner) and the in-process fallback (EndpointRoutes, the
 * default while crawler.lp-workers.enabled=false) — so the offer exists
 * regardless of which path analyzed the page.
 *
 * Scoped to families the page's brand kit actually uses (result.fonts) so
 * icon fonts etc. are never offered. A variable font (weight range) yields
 * one offer per used weight, all sharing the file's hash. woff2 only.
 */
object LPFontQuarantine {
  private val log = LoggerFactory.getLogger("promovolve.api.LPFontQuarantine")

  // "wOF2" magic — only woff2 files are offered for re-hosting (the catalog
  // key scheme and the banner's format("woff2") hint are woff2-only).
  private def isWoff2(bytes: Array[Byte]): Boolean =
    bytes.length > 4 && bytes(0) == 'w'.toByte && bytes(1) == 'O'.toByte &&
    bytes(2) == 'F'.toByte && bytes(3) == '2'.toByte

  def apply(
      result: LPAnalysisResult,
      captured: LPCaptured,
      imageStorage: ImageStorage
  )(using ExecutionContext): Future[LPAnalysisResult] =
    // Belt: fonts are an OFFER, never a dependency — any failure here
    // (sync throw or failed future) must not fail the analysis itself.
    scala.util.Try(quarantine(result, captured, imageStorage)).fold(
      e => { log.warn("LP font quarantine failed for {}: {}", result.url, e.toString); Future.successful(result) },
      f =>
        f.recover { case e =>
          log.warn("LP font quarantine failed for {}: {}", result.url, e.toString); result
        }
    )

  private def quarantine(
      result: LPAnalysisResult,
      captured: LPCaptured,
      imageStorage: ImageStorage
  )(using ExecutionContext): Future[LPAnalysisResult] = {
    // familyKey -> weights the LP uses that family at (from the folded
    // brand-kit names, e.g. "Montserrat Thin" -> montserrat @ 100).
    val usedWeights: Map[String, Vector[Int]] =
      result.fonts.map(GoogleFontCatalog.normalize).groupMap(_._1) { case (_, _, w) => w.getOrElse(400) }
        .map { case (k, ws) => k -> ws.distinct }
    val offers: Vector[(String, Int, Array[Byte])] = captured.fontFaces.flatMap { face =>
      val key = GoogleFontCatalog.familyKey(face.family)
      for {
        weightsInUse <- usedWeights.get(key).toVector
        font <- captured.fonts.get(face.src).toVector
        if isWoff2(font.bytes)
        weight <- {
          val inSpan = weightsInUse.filter(w => w >= face.weightMin && w <= face.weightMax)
          if (face.weightMin == face.weightMax) Vector(face.weightMin).filter(weightsInUse.contains)
          else inSpan
        }
      } yield (face.family, weight, font.bytes)
    }.distinctBy { case (fam, w, _) => (GoogleFontCatalog.familyKey(fam), w) }
    if (offers.isEmpty) {
      // Nothing matched — say WHY at a glance: which families/weights the
      // brand kit wants, which faces the CSS declared, and which URLs
      // actually delivered bytes. One line per analysis, only when there
      // was font material at all; this is how face↔bytes mismatches
      // (redirects, subset URLs, ttf-only sites) get diagnosed from logs.
      if (captured.fontFaces.nonEmpty || captured.fonts.nonEmpty)
        log.info(
          "LP font quarantine: no offers for {} — used={} faces=[{}] capturedUrls=[{}]",
          result.url,
          usedWeights.map { case (k, ws) => s"$k@${ws.mkString("/")}" }.mkString(","),
          captured.fontFaces.take(12).map(f =>
            s"${f.family}@${f.weightMin}-${f.weightMax}:${f.src.takeRight(48)}").mkString(" "),
          captured.fonts.keys.take(8).map(_.takeRight(48)).mkString(" ")
        )
      Future.successful(result)
    } else
      Future.traverse(offers) { case (family, weight, bytes) =>
        val hash = java.security.MessageDigest.getInstance("SHA-256")
          .digest(bytes).map("%02x".format(_)).mkString
        imageStorage.storeOriginalFont(hash, bytes)
          .map(_ => Some(LPOriginalFont(family, weight, hash)))
          .recover { case e =>
            log.info("LP font quarantine store failed family={} err={}", family, e.getMessage); None
          }
      }.map { stored =>
        val originals = stored.flatten
        if (originals.nonEmpty)
          log.info("LP analysis quarantined {} original font faces for {}: {}",
            Integer.valueOf(originals.size), result.url,
            originals.map(f => s"${f.family}@${f.weight}").mkString(","))
        result.copy(originalFonts = originals)
      }
  }
}
