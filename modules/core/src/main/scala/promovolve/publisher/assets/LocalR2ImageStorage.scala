package promovolve.publisher.assets

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*

import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }

/**
 * Image storage backed by the small HTTP facade in dev/r2-local.
 *
 * Wrangler exposes an R2 binding to Worker code rather than an S3-compatible
 * endpoint. The facade translates ordinary HTTP requests into binding calls,
 * which keeps local objects in Wrangler's persistent local R2 simulation.
 */
final class LocalR2ImageStorage(endpoint: String)(using system: ActorSystem[?]) extends ImageStorage {

  private given ExecutionContext = system.executionContext
  private val base = endpoint.stripSuffix("/")
  private val assetExtensions = List("png", "jpg", "gif", "webp", "mp4", "webm", "bin")

  override def store(hash: String, bytes: Array[Byte], mimeType: String): Future[String] = {
    val key = s"assets/$hash.${mimeToExt(mimeType)}"
    put(key, bytes, mimeType).map(_ => key)
  }

  override def fetch(hash: String): Future[Option[Array[Byte]]] = {
    def next(extensions: List[String]): Future[Option[Array[Byte]]] = extensions match {
      case Nil         => Future.successful(None)
      case ext :: rest => fetchObject(s"assets/$hash.$ext").flatMap {
          case found @ Some(_) => Future.successful(found)
          case None            => next(rest)
        }
    }

    next(assetExtensions)
  }

  override def fetchObject(key: String): Future[Option[Array[Byte]]] =
    Http().singleRequest(HttpRequest(uri = objectUri(key))).flatMap { response =>
      if (response.status == StatusCodes.NotFound) {
        response.discardEntityBytes()
        Future.successful(None)
      } else if (response.status.isSuccess()) {
        response.entity.toStrict(30.seconds).map(entity => Some(entity.data.toArray))
      } else {
        failResponse("fetch", key, response)
      }
    }

  override def exists(hash: String): Future[Boolean] = {
    def next(extensions: List[String]): Future[Boolean] = extensions match {
      case Nil         => Future.successful(false)
      case ext :: rest => objectExists(s"assets/$hash.$ext").flatMap {
          case true  => Future.successful(true)
          case false => next(rest)
        }
    }

    next(assetExtensions)
  }

  override def deleteObject(key: String): Future[Unit] =
    Http().singleRequest(HttpRequest(method = HttpMethods.DELETE, uri = objectUri(key))).flatMap { response =>
      if (response.status.isSuccess() || response.status == StatusCodes.NotFound) {
        response.discardEntityBytes()
        Future.unit
      } else failResponse("delete", key, response)
    }

  override def presignPutUrl(hash: String, mimeType: String, ttlSeconds: Int): Future[(String, String)] = {
    val key = s"assets/$hash.${mimeToExt(mimeType)}"
    Future.successful(objectUri(key).toString -> key)
  }

  override def storeFont(slug: String, bytes: Array[Byte], variant: String): Future[Unit] =
    put(fontKey(slug, variant), bytes, "font/woff2")

  override def fontExists(slug: String, variant: String): Future[Boolean] =
    objectExists(fontKey(slug, variant))

  override def storeOriginalFont(hash: String, bytes: Array[Byte]): Future[Unit] =
    put(originalFontKey(hash), bytes, "font/woff2")

  override def fetchOriginalFont(hash: String): Future[Option[Array[Byte]]] =
    fetchObject(originalFontKey(hash))

  private def put(key: String, bytes: Array[Byte], mimeType: String): Future[Unit] = {
    val contentType = ContentType.parse(mimeType).toOption.getOrElse(ContentTypes.`application/octet-stream`)
    val request = HttpRequest(
      method = HttpMethods.PUT,
      uri = objectUri(key),
      entity = HttpEntity(contentType, bytes)
    )
    Http().singleRequest(request).flatMap { response =>
      if (response.status.isSuccess()) {
        response.discardEntityBytes()
        Future.unit
      } else failResponse("store", key, response)
    }
  }

  private def objectExists(key: String): Future[Boolean] =
    Http().singleRequest(HttpRequest(method = HttpMethods.HEAD, uri = objectUri(key))).flatMap { response =>
      if (response.status == StatusCodes.NotFound) {
        response.discardEntityBytes()
        Future.successful(false)
      } else if (response.status.isSuccess()) {
        response.discardEntityBytes()
        Future.successful(true)
      } else failResponse("check existence", key, response)
    }

  private def failResponse(operation: String, key: String, response: HttpResponse): Future[Nothing] =
    response.entity.toStrict(10.seconds).flatMap { entity =>
      Future.failed(new IllegalStateException(
        s"Local R2 $operation failed for $key: HTTP ${response.status.intValue()} ${entity.data.utf8String}"
      ))
    }

  private def objectUri(key: String): Uri =
    Uri(s"$base/${key.split('/').map(encodeSegment).mkString("/")}")

  private def encodeSegment(segment: String): String =
    java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")

  private def fontKey(slug: String, variant: String): String = s"fonts/$slug-$variant.woff2"
  private def originalFontKey(hash: String): String = s"fonts/orig/$hash.woff2"

  private def mimeToExt(mimeType: String): String = mimeType match {
    case "image/png"  => "png"
    case "image/jpeg" => "jpg"
    case "image/gif"  => "gif"
    case "image/webp" => "webp"
    case "video/mp4"  => "mp4"
    case "video/webm" => "webm"
    case _            => "bin"
  }
}

object LocalR2ImageStorage {
  def fromEnv()(using system: ActorSystem[?]): Option[LocalR2ImageStorage] =
    sys.env.get("R2_LOCAL_URL").filter(_.nonEmpty).map(new LocalR2ImageStorage(_))
}
