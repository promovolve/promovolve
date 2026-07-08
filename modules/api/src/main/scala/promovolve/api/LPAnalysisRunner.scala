package promovolve.api

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.pattern.after
import org.slf4j.LoggerFactory
import promovolve.browser.{LPAnalysisResult, LPAnalyzer, LPCapturedImage, LPWorker}
import promovolve.publisher.{ImageAsset, ImageAssetRepo}
import promovolve.publisher.assets.ImageStorage

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*

/** Builds the default [[LPWorker.RunAnalysis]]: run [[LPAnalyzer]] (Playwright)
  * on the crawler tier, upload the captured bot-protected-origin bytes + the
  * hero screenshot to R2 RIGHT HERE, rewrite each section `src` to its CDN URL,
  * and complete with a finished `AnalyzeLPDone`. Net effect: the raw bytes
  * never leave the pod that captured them — only URLs cross the wire back to
  * the api node. This is the relocation of EndpointRoutes' `storeImageAsset` +
  * `persistCapturedImages` onto the crawler tier, so Chromium + the byte
  * handling leave the bid/serve JVMs. Constructed on crawler-role nodes (which
  * carry R2 + JDBC creds) and passed to [[LPWorker.initSharding]]. */
object LPAnalysisRunner {
  private val log = LoggerFactory.getLogger("promovolve.api.LPAnalysisRunner")

  def apply(
      lpAnalyzer: LPAnalyzer,
      imageStorage: ImageStorage,
      // Optional: when present, dedup + register an `image_asset` row (faithful
      // to the api-tier path). When absent (the crawler tier doesn't stand up a
      // DB), we just store to R2 — which is content-addressed by the hash, so
      // the upload is idempotent; we only skip the registry dedup optimization.
      imageAssetRepo: Option[ImageAssetRepo],
      cdnBaseUrl: String,
  )(using system: ActorSystem[?]): LPWorker.RunAnalysis = {
    given ec: ExecutionContext = system.executionContext

    // compress → content-hash → store(R2) [→ dedup/register if a repo is given];
    // returns the s3Key.
    def storeAsset(rawBytes: Array[Byte], rawMime: String): Future[String] = {
      val (bytes, mime, w, h) = ImageCompression.compress(rawBytes, rawMime)
      val hash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).map("%02x".format(_)).mkString
      imageAssetRepo match {
        case Some(repo) =>
          repo.get(hash).flatMap {
            case Some(a) => Future.successful(a.s3Key)
            case None =>
              imageStorage.store(hash, bytes, mime).flatMap { s3Key =>
                repo.put(ImageAsset(hash, s3Key, mime, w, h, Instant.now())).map(_ => s3Key)
              }
          }
        case None =>
          imageStorage.store(hash, bytes, mime) // R2 is content-addressed → idempotent
      }
    }

    // Store the bytes LPAnalyzer captured (from the context that beat the bot
    // manager) and rewrite each REFERENCED section src to its CDN URL. Best-
    // effort: a store failure leaves that one src on the original URL.
    def persist(result: LPAnalysisResult, captured: Map[String, LPCapturedImage]): Future[LPAnalysisResult] = {
      if (captured.isEmpty) Future.successful(result)
      else {
        val referenced: Set[String] = result.sections.flatMap(_.images.map(_.src)).toSet
        val toStore = captured.filter { case (url, _) => referenced.contains(url) }.toVector
        if (toStore.isEmpty) Future.successful(result)
        else
          Future.traverse(toStore) { case (url, img) =>
            storeAsset(img.bytes, img.mime)
              .map(s3Key => Some(url -> s"$cdnBaseUrl/$s3Key"))
              .recover { case e =>
                log.warn("LP image store failed url={} err={}", url, e.getMessage); None
              }
          }.map { pairs =>
            val urlToCdn = pairs.flatten.toMap
            log.info("LP analysis stored {}/{} referenced images for {}", Integer.valueOf(urlToCdn.size), Integer.valueOf(toStore.size), result.url)
            if (urlToCdn.isEmpty) result
            else result.copy(sections = result.sections.map { sec =>
              sec.copy(images = sec.images.map(im => urlToCdn.get(im.src).fold(im)(cdn => im.copy(src = cdn))))
            })
          }
      }
    }

    (req: LPWorker.AnalyzeLP) => {
      // The hero screenshot uploads to R2 asynchronously (and streams its URL
      // via progressTo). It must go in the final reply, but it can land AFTER
      // analysis completes — or never fire for some pages. So `shot` completes
      // when the upload finishes (Some) or fails (None), and below we wait for
      // it with a short timeout: a fast page no longer returns a None
      // screenshot just because the upload lost the race, and a page that never
      // screenshots doesn't hang.
      val shot = Promise[Option[String]]()
      val onScreenshot: Array[Byte] => Unit = png =>
        storeAsset(png, "image/png").onComplete {
          case scala.util.Success(s3Key) =>
            val url = s"$cdnBaseUrl/$s3Key"
            req.progressTo ! LPWorker.AnalyzeLPProgress(req.url, url) // tell is thread-safe in a Future cb
            shot.trySuccess(Some(url))
          case scala.util.Failure(e) =>
            log.warn("LP screenshot store failed url={} err={}", req.url, e.getMessage)
            shot.trySuccess(None)
        }
      lpAnalyzer.analyze(req.url, req.strategy, onScreenshot)
        .flatMap { case (result, captured) => persist(result, captured) }
        .flatMap { finished =>
          val shotUrl = Future.firstCompletedOf(Seq(
            shot.future,
            after(3.seconds, system.toClassic.scheduler)(Future.successful(Option.empty[String])),
          ))
          shotUrl.map(u => LPWorker.AnalyzeLPDone(req.url, finished, u, None))
        }
    }
  }
}
