package promovolve.api

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout
import promovolve.browser.{ BannerWorker, LPAnalyzer }
import promovolve.publisher.assets.{ ImageStorage, WebpEncoder }

import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.*

object BannerRenderer {
  final case class Image(hash: String, key: String, bytes: Array[Byte], mime: String, brokenImages: Int)
  type Render = (String, Int, Int) => Future[Image]

  def local(analyzer: LPAnalyzer, storage: ImageStorage)(using ExecutionContext): Render =
    (pages, width, height) =>
      analyzer.renderBanner(pages, width, height).flatMap { case (png, broken) =>
        val (bytes, mime) = WebpEncoder.encode(png).map(_ -> "image/webp").getOrElse(png -> "image/png")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString
        storage.store(hash, bytes, mime).map(key => Image(hash, key, bytes, mime, broken))
      }

  def remote(sharding: ClusterSharding, storage: ImageStorage, requestTimeout: FiniteDuration = 90.seconds)(
      using system: ActorSystem[?]): Render = {
    given ExecutionContext = system.executionContext
    given Timeout = Timeout(requestTimeout)
    (pages, width, height) =>
      // Oversized messages are dropped by Artery rather than producing an application error.
      if (pages.getBytes(StandardCharsets.UTF_8).length > 128 * 1024)
        Future.failed(new IllegalArgumentException("Banner document exceeds the browser worker message limit"))
      else sharding.entityRefFor(BannerWorker.TypeKey, "0")
        .ask[BannerWorker.Result](replyTo => BannerWorker.Render(pages, width, height, replyTo))
        .flatMap { result =>
          result.error match {
            case Some(error) => Future.failed(new IllegalStateException(error))
            case None        => storage.fetch(result.hash).flatMap {
                case Some(bytes) =>
                  Future.successful(Image(result.hash, result.key, bytes, result.mime, result.brokenImages))
                case None => Future.failed(new IllegalStateException("Browser screenshot is missing from storage"))
              }
          }
        }
  }
}
