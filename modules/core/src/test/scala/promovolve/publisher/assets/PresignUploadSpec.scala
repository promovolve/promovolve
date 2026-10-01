package promovolve.publisher.assets

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.http.{ SdkHttpMethod, SdkHttpRequest }
import software.amazon.awssdk.http.auth.aws.signer.{ AwsV4FamilyHttpSigner, AwsV4HttpSigner }
import software.amazon.awssdk.http.auth.spi.signer.{ HttpSigner, SignRequest }
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity

import java.net.URI
import java.time.{ Clock, Duration, LocalDateTime, ZoneOffset }
import java.time.format.DateTimeFormatter

/**
 * Presigned uploads sign the Content-Type (an unsigned one let an uploader
 * PUT text/html under an image key). Our SigV4 is hand-rolled, so check it
 * against the AWS SDK's own signer: a mismatch here means R2 would reject
 * every browser upload.
 */
class PresignUploadSpec extends AnyWordSpec with Matchers with ScalaFutures with BeforeAndAfterAll {

  private val testKit = ActorTestKit()
  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val storage =
    new R2ImageStorage("acct123", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "bucket")(
      using testKit.system)

  private def query(url: String): Map[String, String] =
    URI.create(url).getRawQuery.split("&").map { kv =>
      val Array(k, v) = kv.split("=", 2)
      k -> java.net.URLDecoder.decode(v, "UTF-8")
    }.toMap

  /** Re-sign the same PUT with the AWS SDK at the same instant. */
  private def sdkSignature(url: String, contentType: String): String = {
    val q = query(url)
    val at = LocalDateTime.parse(q("X-Amz-Date"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
      .toInstant(ZoneOffset.UTC)
    val u = URI.create(url)
    val request = SdkHttpRequest.builder()
      .method(SdkHttpMethod.PUT)
      .uri(URI.create(s"https://${u.getHost}${u.getRawPath}"))
      .putHeader("Content-Type", contentType)
      .build()
    val signed = AwsV4HttpSigner.create().sign(
      SignRequest.builder(AwsCredentialsIdentity.create("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
        .request(request)
        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, "s3")
        .putProperty(AwsV4HttpSigner.REGION_NAME, "auto")
        .putProperty(AwsV4FamilyHttpSigner.AUTH_LOCATION, AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING)
        .putProperty(AwsV4FamilyHttpSigner.EXPIRATION_DURATION, Duration.ofSeconds(q("X-Amz-Expires").toLong))
        .putProperty(AwsV4FamilyHttpSigner.PAYLOAD_SIGNING_ENABLED, java.lang.Boolean.FALSE)
        .putProperty(AwsV4FamilyHttpSigner.DOUBLE_URL_ENCODE, java.lang.Boolean.FALSE)
        .putProperty(AwsV4FamilyHttpSigner.NORMALIZE_PATH, java.lang.Boolean.FALSE)
        .putProperty(HttpSigner.SIGNING_CLOCK, Clock.fixed(at, ZoneOffset.UTC))
        .build()
    )
    signed.request().firstMatchingRawQueryParameter("X-Amz-Signature").get()
  }

  "presignPutUrl" should {
    "sign the Content-Type, matching the AWS SDK signature" in {
      val (url, key) = storage.presignPutUrl("abc123", "image/png", 600).futureValue
      key shouldBe "assets/abc123.png"
      query(url)("X-Amz-SignedHeaders") shouldBe "content-type;host"
      query(url)("X-Amz-Signature") shouldBe sdkSignature(url, "image/png")
    }

    "sign SVG as an opaque download under an .svg key" in {
      val (url, key) = storage.presignPutUrl("abc123", ImageStorage.SvgMime, 600).futureValue
      key shouldBe "assets/abc123.svg"
      query(url)("X-Amz-Signature") shouldBe sdkSignature(url, "application/octet-stream")
      query(url)("X-Amz-Signature") should not be sdkSignature(url, ImageStorage.SvgMime)
    }
  }

  "upload rules" should {
    "admit only images and video" in {
      ImageStorage.uploadable("image/png") shouldBe true
      ImageStorage.uploadable("video/quicktime") shouldBe true
      ImageStorage.uploadable("text/html") shouldBe false
      ImageStorage.uploadable("application/octet-stream") shouldBe false
    }
    "put everything as its own type except SVG" in {
      ImageStorage.putContentType("image/webp") shouldBe "image/webp"
      ImageStorage.putContentType(ImageStorage.SvgMime) shouldBe "application/octet-stream"
    }
  }
}
