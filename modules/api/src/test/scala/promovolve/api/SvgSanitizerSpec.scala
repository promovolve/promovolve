package promovolve.api

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SvgSanitizerSpec extends AnyWordSpec with Matchers {

  private def sanitizeStr(s: String): String =
    new String(SvgSanitizer.sanitize(s.getBytes("UTF-8")), "UTF-8")

  "SvgSanitizer.sanitize" should {

    "strip <script> blocks" in {
      val out = sanitizeStr("""<svg><script>alert(1)</script><circle r="5"/></svg>""")
      (out should not).include("script")
      (out should not).include("alert")
      out should include("<circle")
    }

    "strip self-closing <script> tags" in {
      val out = sanitizeStr("""<svg><script src="x"/><rect/></svg>""")
      (out should not).include("script")
      out should include("<rect")
    }

    "strip onload, onclick, onerror handlers" in {
      val out = sanitizeStr("""<svg onload="alert(1)"><circle onclick="bad()" onerror='x' r="5"/></svg>""")
      (out should not).include("onload")
      (out should not).include("onclick")
      (out should not).include("onerror")
      (out should not).include("alert")
      out should include("<circle")
    }

    "strip javascript: hrefs" in {
      val out = sanitizeStr("""<svg><a href="javascript:alert(1)"><text>x</text></a></svg>""")
      (out should not).include("javascript:")
    }

    "strip xlink:href javascript:" in {
      val out = sanitizeStr("""<svg><use xlink:href="javascript:alert(1)"/></svg>""")
      (out should not).include("javascript:")
    }

    "strip <foreignObject> blocks (HTML/JS injection vector)" in {
      val out = sanitizeStr(
        """<svg><foreignObject><body><script>x</script></body></foreignObject><rect/></svg>"""
      )
      (out should not).include("foreignObject")
      (out should not).include("script")
      out should include("<rect")
    }

    "strip <iframe>, <object>, <embed>" in {
      val out = sanitizeStr("""<svg><iframe src="x"></iframe><object data="y"></object><embed src="z"/></svg>""")
      (out should not).include("iframe")
      (out should not).include("object")
      (out should not).include("embed")
    }

    "leave benign SVG untouched in spirit (shapes preserved)" in {
      val src = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M5 5 L10 10"/></svg>"""
      val out = sanitizeStr(src)
      out should include("<path")
      out should include("viewBox")
      out should include("xmlns")
    }
  }

  "SvgSanitizer.extractDims" should {

    "read explicit width/height" in {
      SvgSanitizer.extractDims("""<svg width="100" height="50"><rect/></svg>""".getBytes) shouldBe ((100, 50))
    }

    "fall back to viewBox" in {
      SvgSanitizer.extractDims("""<svg viewBox="0 0 200 80"><rect/></svg>""".getBytes) shouldBe ((200, 80))
    }

    "return (0,0) when neither is present" in {
      SvgSanitizer.extractDims("""<svg><rect/></svg>""".getBytes) shouldBe ((0, 0))
    }

    "handle decimal values by truncating to int" in {
      SvgSanitizer.extractDims("""<svg width="100.5" height="50.7"/>""".getBytes) shouldBe ((100, 50))
    }
  }
}

class SvgRegisterUploadSpec extends AnyWordSpec with Matchers with org.scalatest.concurrent.ScalaFutures {
  import promovolve.publisher.{ ImageAsset, ImageAssetRepo }
  import promovolve.publisher.assets.ImageStorage
  import scala.concurrent.{ ExecutionContext, Future }
  import scala.collection.mutable

  private given ExecutionContext = ExecutionContext.global

  /** In-memory R2: object bytes + the Content-Type each was stored with. */
  private class FakeStorage extends ImageStorage {
    val objects = mutable.Map.empty[String, (Array[Byte], String)]
    def store(hash: String, bytes: Array[Byte], mimeType: String): Future[String] = {
      val key = s"assets/$hash.${ImageStorage.extFor(mimeType)}"
      objects(key) = (bytes, mimeType)
      Future.successful(key)
    }
    def fetch(hash: String): Future[Option[Array[Byte]]] = Future.successful(None)
    def exists(hash: String): Future[Boolean] = Future.successful(false)
    override def fetchObject(s3Key: String): Future[Option[Array[Byte]]] =
      Future.successful(objects.get(s3Key).map(_._1))
  }
  private class FakeRepo extends ImageAssetRepo {
    val rows = mutable.Map.empty[String, ImageAsset]
    def put(asset: ImageAsset): Future[Unit] = Future.successful(rows(asset.hash) = asset)
    def get(hash: String): Future[Option[ImageAsset]] = Future.successful(rows.get(hash))
  }

  "SvgSanitizer.registerUpload" should {
    "rewrite the uploaded object sanitized, as image/svg+xml, and record it" in {
      val storage = new FakeStorage
      val repo = new FakeRepo
      // What the browser's presigned PUT left behind: an opaque download.
      storage.objects("assets/h1.svg") =
        ("""<svg width="40" height="20"><script>alert(1)</script><rect onclick="x()"/></svg>""".getBytes("UTF-8"),
          "application/octet-stream")

      val (key, mime, w, h) = SvgSanitizer.registerUpload(storage, repo, "h1", "assets/h1.svg", (0, 0)).futureValue

      key shouldBe "assets/h1.svg"
      mime shouldBe "image/svg+xml"
      (w, h) shouldBe (40, 20)
      val (bytes, storedType) = storage.objects("assets/h1.svg")
      storedType shouldBe "image/svg+xml"
      val body = new String(bytes, "UTF-8")
      (body should not).include("script")
      (body should not).include("onclick")
      repo.rows("h1").mime shouldBe "image/svg+xml"
    }

    "record nothing when the PUT never landed" in {
      val repo = new FakeRepo
      SvgSanitizer.registerUpload(new FakeStorage, repo, "h2", "assets/h2.svg", (0, 0)).failed.futureValue
      repo.rows shouldBe empty
    }
  }
}
