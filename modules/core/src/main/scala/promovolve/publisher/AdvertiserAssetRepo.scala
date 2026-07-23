package promovolve.publisher

import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * Per-advertiser ownership record pointing at a deduped image in image_asset.
 * One advertiser can own many assets; one image_asset hash can be owned by
 * many advertisers (dedupe + shared storage).
 */
final case class AdvertiserAsset(
    id: String, // UUID
    advertiserId: String,
    imageHash: String, // FK → image_asset.hash
    filename: String,
    createdAt: Instant
)

trait AdvertiserAssetRepo {
  def put(a: AdvertiserAsset): Future[Unit]
  def list(advertiserId: String): Future[Vector[AdvertiserAsset]]

  /**
   * One page of an advertiser's assets, newest first. `after` is the
   * (createdAt, id) of the last row of the previous page — rows strictly
   * older than it are returned (keyset pagination, stable under inserts).
   * `limit` caps the rows scanned/returned.
   */
  def listPage(
      advertiserId: String,
      limit: Int,
      after: Option[(Instant, String)]
  ): Future[Vector[AdvertiserAsset]]
  def get(id: String): Future[Option[AdvertiserAsset]]
  def delete(id: String, advertiserId: String): Future[Int]

  /** True if this advertiser already owns an asset for the given image hash. */
  def existsForHash(advertiserId: String, imageHash: String): Future[Option[AdvertiserAsset]]
}

final class SlickAdvertiserAssetRepo(db: slick.jdbc.JdbcBackend#Database)(using ec: ExecutionContext)
    extends AdvertiserAssetRepo {

  import promovolve.SlickMappers.given

  private val assets = TableQuery[AdvertiserAssetTable]

  def ensureSchema(): Unit = {
    val createSql = sql"""
      CREATE TABLE IF NOT EXISTS advertiser_asset (
        id VARCHAR(40) PRIMARY KEY,
        advertiser_id VARCHAR(80) NOT NULL,
        image_hash VARCHAR(64) NOT NULL,
        filename VARCHAR(256) NOT NULL,
        created_at TIMESTAMP NOT NULL
      )
    """.asUpdate

    val idx1 = sql"""
      CREATE INDEX IF NOT EXISTS idx_advertiser_asset_advertiser
        ON advertiser_asset (advertiser_id, created_at DESC)
    """.asUpdate

    val idx2 = sql"""
      CREATE UNIQUE INDEX IF NOT EXISTS uq_advertiser_asset_hash
        ON advertiser_asset (advertiser_id, image_hash)
    """.asUpdate

    Await.result(db.run(createSql), 10.seconds)
    Await.result(db.run(idx1), 10.seconds)
    Await.result(db.run(idx2), 10.seconds)
  }

  override def put(a: AdvertiserAsset): Future[Unit] =
    db.run(assets.insertOrUpdate(a)).map(_ => ())

  override def list(advertiserId: String): Future[Vector[AdvertiserAsset]] =
    db.run(
      assets.filter(_.advertiserId === advertiserId)
        .sortBy(_.createdAt.desc)
        .result
    ).map(_.toVector)

  override def listPage(
      advertiserId: String,
      limit: Int,
      after: Option[(Instant, String)]
  ): Future[Vector[AdvertiserAsset]] = {
    val base = assets.filter(_.advertiserId === advertiserId)
    // Keyset predicate: strictly older than the cursor row. The id tiebreak
    // makes the (createdAt DESC, id DESC) order total, so no row is skipped
    // or repeated when several share a createdAt.
    val filtered = after match {
      case Some((ts, cid)) =>
        base.filter(a => a.createdAt < ts || (a.createdAt === ts && a.id < cid))
      case None => base
    }
    db.run(
      filtered.sortBy(a => (a.createdAt.desc, a.id.desc)).take(limit).result
    ).map(_.toVector)
  }

  override def get(id: String): Future[Option[AdvertiserAsset]] =
    db.run(assets.filter(_.id === id).result.headOption)

  override def delete(id: String, advertiserId: String): Future[Int] =
    db.run(assets.filter(a => a.id === id && a.advertiserId === advertiserId).delete)

  override def existsForHash(advertiserId: String, imageHash: String): Future[Option[AdvertiserAsset]] =
    db.run(
      assets.filter(a => a.advertiserId === advertiserId && a.imageHash === imageHash)
        .result.headOption
    )

  private class AdvertiserAssetTable(tag: Tag) extends Table[AdvertiserAsset](tag, "advertiser_asset") {
    def id = column[String]("id", O.PrimaryKey)
    def advertiserId = column[String]("advertiser_id")
    def imageHash = column[String]("image_hash")
    def filename = column[String]("filename")
    def createdAt = column[Instant]("created_at")

    def * = (id, advertiserId, imageHash, filename, createdAt).mapTo[AdvertiserAsset]
  }
}

object AdvertiserAsset {
  def newId(): String = UUID.randomUUID().toString
}
