package promovolve.publisher.assets

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.connectors.s3.{S3Attributes, S3Settings}
import org.apache.pekko.stream.connectors.s3.scaladsl.S3
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import scala.concurrent.{ExecutionContext, Future}

/** Storage for image bytes. */
trait ImageStorage {
  /** Store image bytes, returns the storage key. */
  def store(hash: String, bytes: Array[Byte], mimeType: String): Future[String]

  /** Fetch image bytes by hash. */
  def fetch(hash: String): Future[Option[Array[Byte]]]

  /** Check if image exists. */
  def exists(hash: String): Future[Boolean]

  /** Generate a short-lived presigned URL the browser can PUT to
    * directly. The bucket key is derived from the hash + mimeType, so a
    * later `register` call only needs the metadata, not the bytes.
    * Returns (url, s3Key). The URL expires after `ttlSeconds`.
    *
    * Default impl returns a "not supported" failure — only R2 has the
    * SigV4 wiring; in-memory and local-disk modes return a Future
    * failure so callers fall back to the byte-shipping upload path.
    */
  def presignPutUrl(hash: String, mimeType: String, ttlSeconds: Int): Future[(String, String)] =
    Future.failed(new UnsupportedOperationException(
      "presigned uploads only supported on R2 storage"
    ))

  /** Store a self-hosted web font woff2 at the stable, human key
    * `fonts/<slug>-<variant>.woff2`. `variant` is `"latin"` for the latin
    * subset (the slug is the canonical identity for a latin face, so this
    * dedups across creatives), or a per-text content key for CJK faces
    * (Noto Sans/Serif JP) provisioned via the `text=` subset — the latin
    * block has no kana/kanji, so CJK is keyed by the glyphs it covers
    * (see GoogleFontCatalog.subsetKey). Idempotent. Default impl is a no-op
    * failure for non-R2 backends (dev/in-memory); the font provisioner
    * treats failures as "couldn't self-host" → the creative falls back to a
    * system font. */
  def storeFont(slug: String, bytes: Array[Byte], variant: String = "latin"): Future[Unit] =
    Future.failed(new UnsupportedOperationException(
      "font storage only supported on R2 storage"
    ))

  /** Whether a font (slug + variant) already exists in storage — lets the
    * provisioner dedup so a face shared across creatives is fetched once.
    * Default false (non-R2 backends never self-host). */
  def fontExists(slug: String, variant: String = "latin"): Future[Boolean] =
    Future.successful(false)
}

/** Cloudflare R2 storage using Pekko Connectors S3 (S3-compatible, zero egress fees). */
final class R2ImageStorage(
    accountId: String,
    accessKeyId: String,
    secretAccessKey: String,
    bucket: String
)(using system: ActorSystem[?]) extends ImageStorage {

  private given ExecutionContext = system.executionContext

  // Configure S3 settings for R2
  private val s3Settings: S3Settings = S3Settings()
    .withEndpointUrl(s"https://$accountId.r2.cloudflarestorage.com")
    .withAccessStyle(org.apache.pekko.stream.connectors.s3.AccessStyle.PathAccessStyle)  // R2 requires path-style
    .withCredentialsProvider(
      StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKeyId, secretAccessKey)
      )
    )
    .withS3RegionProvider(new AwsRegionProvider {
      override def getRegion: Region = Region.of("auto")
    })

  private val s3Attributes = S3Attributes.settings(s3Settings)

  def store(hash: String, bytes: Array[Byte], mimeType: String): Future[String] = {
    val ext = mimeToExt(mimeType)
    val s3Key = s"assets/$hash.$ext"
    val contentType = org.apache.pekko.http.scaladsl.model.ContentType.parse(mimeType).toOption
      .getOrElse(org.apache.pekko.http.scaladsl.model.ContentTypes.`application/octet-stream`)

    val source = Source.single(ByteString(bytes))
    val sink = S3.multipartUpload(bucket, s3Key, contentType)
      .withAttributes(s3Attributes)

    source.runWith(sink).map(_ => s3Key)
  }

  def fetch(hash: String): Future[Option[Array[Byte]]] = {
    val extensions = Seq("png", "jpg", "gif", "webp", "bin")

    def tryFetch(remaining: List[String]): Future[Option[Array[Byte]]] = remaining match {
      case Nil => Future.successful(None)
      case ext :: rest =>
        val s3Key = s"assets/$hash.$ext"
        S3.getObject(bucket, s3Key)
          .withAttributes(s3Attributes)
          .runWith(Sink.fold(ByteString.empty)(_ ++ _))
          .map(bs => Some(bs.toArray))
          .recoverWith { case _ => tryFetch(rest) }
    }

    tryFetch(extensions.toList)
  }

  def exists(hash: String): Future[Boolean] =
    fetch(hash).map(_.isDefined)

  // ── Self-hosted web fonts ──────────────────────────────────────────
  // Fonts use a stable human key (fonts/<slug>-<variant>.woff2) rather than
  // the hash-based assets/ path, so the banner can derive the URL from the
  // family name + variant alone — no per-creative persistence needed. The
  // variant is "latin" for latin subsets (deduped across creatives) or a
  // per-text content key for CJK faces (see GoogleFontCatalog.subsetKey).

  private def fontKey(slug: String, variant: String): String = s"fonts/$slug-$variant.woff2"

  override def storeFont(slug: String, bytes: Array[Byte], variant: String): Future[Unit] = {
    val contentType = org.apache.pekko.http.scaladsl.model.ContentType.parse("font/woff2").toOption
      .getOrElse(org.apache.pekko.http.scaladsl.model.ContentTypes.`application/octet-stream`)
    val source = Source.single(ByteString(bytes))
    val sink = S3.multipartUpload(bucket, fontKey(slug, variant), contentType).withAttributes(s3Attributes)
    source.runWith(sink).map(_ => ())
  }

  override def fontExists(slug: String, variant: String): Future[Boolean] =
    // Sink.headOption pulls only the first chunk then cancels upstream,
    // so this is a cheap existence probe (not a full download). A missing
    // object makes the stream fail → recover to false.
    S3.getObject(bucket, fontKey(slug, variant))
      .withAttributes(s3Attributes)
      .runWith(Sink.headOption)
      .map(_.isDefined)
      .recover { case _ => false }

  private def mimeToExt(mimeType: String): String = mimeType match {
    case "image/png"  => "png"
    case "image/jpeg" => "jpg"
    case "image/gif"  => "gif"
    case "image/webp" => "webp"
    case "video/mp4"  => "mp4"
    case "video/webm" => "webm"
    case _            => "bin"
  }

  /** Hand-rolled S3 SigV4 query-string signing for presigned PUT URLs.
    * Pekko-connectors-s3 1.2.0 doesn't expose a presigner; pulling AWS
    * SDK v2 just for this would add ~5 MB of transitive deps. The algo
    * is small and stable enough to inline here.
    *
    * Reference: AWS SigV4 query-string algorithm
    * https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html
    */
  override def presignPutUrl(
      hash: String,
      mimeType: String,
      ttlSeconds: Int,
  ): Future[(String, String)] = Future {
    val ext = mimeToExt(mimeType)
    val s3Key = s"assets/$hash.$ext"
    val region = "auto"
    val service = "s3"
    val host = s"$accountId.r2.cloudflarestorage.com"
    val now = java.time.Instant.now()
    val amzDate = now.atZone(java.time.ZoneOffset.UTC)
      .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
    val shortDate = amzDate.substring(0, 8)
    val credentialScope = s"$shortDate/$region/$service/aws4_request"
    val credential = s"$accessKeyId/$credentialScope"

    // Path-style: /{bucket}/{key}. Each segment URL-encoded except '/'.
    val path = s"/$bucket/${rfc3986Encode(s3Key, encodeSlash = false)}"

    // Query parameters in lexical order — required by SigV4.
    val queryPairs: Seq[(String, String)] = Seq(
      "X-Amz-Algorithm"     -> "AWS4-HMAC-SHA256",
      "X-Amz-Credential"    -> credential,
      "X-Amz-Date"          -> amzDate,
      "X-Amz-Expires"       -> ttlSeconds.toString,
      "X-Amz-SignedHeaders" -> "host",
    ).sortBy(_._1)
    val canonicalQuery = queryPairs
      .map { case (k, v) => s"${rfc3986Encode(k)}=${rfc3986Encode(v)}" }
      .mkString("&")

    // Only host is signed for query-string presigning.
    val canonicalHeaders = s"host:$host\n"
    val signedHeaders = "host"

    // Payload hash for presigned PUT is the literal "UNSIGNED-PAYLOAD"
    // — caller's actual body isn't part of the signature.
    val canonicalRequest = Seq(
      "PUT",
      path,
      canonicalQuery,
      canonicalHeaders,
      signedHeaders,
      "UNSIGNED-PAYLOAD",
    ).mkString("\n")

    val stringToSign = Seq(
      "AWS4-HMAC-SHA256",
      amzDate,
      credentialScope,
      sha256Hex(canonicalRequest),
    ).mkString("\n")

    // Derive the signing key per SigV4 spec.
    val kDate    = hmacSha256(s"AWS4$secretAccessKey".getBytes("UTF-8"), shortDate)
    val kRegion  = hmacSha256(kDate, region)
    val kService = hmacSha256(kRegion, service)
    val kSigning = hmacSha256(kService, "aws4_request")
    val signature = hmacSha256(kSigning, stringToSign).map("%02x".format(_)).mkString

    val url = s"https://$host$path?$canonicalQuery&X-Amz-Signature=$signature"
    (url, s3Key)
  }(using system.executionContext)

  private def hmacSha256(key: Array[Byte], data: String): Array[Byte] = {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data.getBytes("UTF-8"))
  }

  private def sha256Hex(s: String): String = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  /** RFC 3986 percent-encoding for SigV4. Java's URLEncoder uses
    * application/x-www-form-urlencoded which encodes `~` and uses `+`
    * for space — both wrong for SigV4. Encode here: only A-Z, a-z, 0-9,
    * '-', '_', '.', '~' stay literal; everything else (including '/'
    * unless `encodeSlash=false`) becomes `%XX`.
    */
  private def rfc3986Encode(s: String, encodeSlash: Boolean = true): String = {
    val sb = new StringBuilder
    for (b <- s.getBytes("UTF-8")) {
      val c = (b & 0xff).toChar
      val safe =
        (c >= 'A' && c <= 'Z') ||
        (c >= 'a' && c <= 'z') ||
        (c >= '0' && c <= '9') ||
        c == '-' || c == '_' || c == '.' || c == '~' ||
        (!encodeSlash && c == '/')
      if (safe) sb.append(c)
      else sb.append("%%%02X".format(b & 0xff))
    }
    sb.toString
  }
}

object R2ImageStorage {
  /** Create from environment variables:
    * - R2_ACCOUNT_ID
    * - R2_ACCESS_KEY_ID
    * - R2_SECRET_ACCESS_KEY
    * - R2_BUCKET
    */
  def fromEnv()(using system: ActorSystem[?]): Option[R2ImageStorage] = {
    for {
      accountId <- sys.env.get("R2_ACCOUNT_ID")
      accessKeyId <- sys.env.get("R2_ACCESS_KEY_ID")
      secretAccessKey <- sys.env.get("R2_SECRET_ACCESS_KEY")
      bucket <- sys.env.get("R2_BUCKET")
    } yield new R2ImageStorage(accountId, accessKeyId, secretAccessKey, bucket)
  }
}