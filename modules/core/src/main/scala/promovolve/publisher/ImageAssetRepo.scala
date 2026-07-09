package promovolve.publisher

import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.*

/** Image asset metadata stored once per unique image (keyed by hash). */
final case class ImageAsset(
    hash: String, // SHA-256, primary key
    s3Key: String, // "assets/{hash}.{ext}"
    mime: String,
    width: Int,
    height: Int,
    uploadedAt: Instant
)

/** Storage for image assets (one per unique image). */
trait ImageAssetRepo {
  def put(asset: ImageAsset): Future[Unit]
  def get(hash: String): Future[Option[ImageAsset]]
}

/** PostgreSQL-backed implementation using Slick. */
final class SlickImageAssetRepo(db: slick.jdbc.JdbcBackend#Database)(using ec: ExecutionContext)
    extends ImageAssetRepo {

  import promovolve.SlickMappers.given

  private val assets = TableQuery[ImageAssetTable]

  def ensureSchema(): Unit = {
    val createTableSql = sql"""
      CREATE TABLE IF NOT EXISTS image_asset (
        hash VARCHAR(64) PRIMARY KEY,
        s3_key VARCHAR(512) NOT NULL,
        mime VARCHAR(64) NOT NULL,
        width INT NOT NULL,
        height INT NOT NULL,
        uploaded_at TIMESTAMP NOT NULL
      )
    """.asUpdate

    Await.result(db.run(createTableSql), 10.seconds)
  }

  override def put(asset: ImageAsset): Future[Unit] = {
    val upsert = assets.insertOrUpdate(asset)
    db.run(upsert).map(_ => ())
  }

  override def get(hash: String): Future[Option[ImageAsset]] = {
    val query = assets.filter(_.hash === hash).result.headOption
    db.run(query)
  }

  private class ImageAssetTable(tag: Tag) extends Table[ImageAsset](tag, "image_asset") {
    def hash = column[String]("hash", O.PrimaryKey)
    def s3Key = column[String]("s3_key")
    def mime = column[String]("mime")
    def width = column[Int]("width")
    def height = column[Int]("height")
    def uploadedAt = column[Instant]("uploaded_at")

    def * = (hash, s3Key, mime, width, height, uploadedAt).mapTo[ImageAsset]
  }
}
