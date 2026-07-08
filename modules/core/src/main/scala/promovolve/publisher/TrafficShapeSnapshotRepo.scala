package promovolve.publisher

import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/** Persisted traffic shape for a site.
  *
  * One row per site - updated periodically as the pattern is learned.
  * Used to restore learned traffic patterns on AdServer restart.
  */
final case class TrafficShapeSnapshotRow(
    siteId: String,
    bucketCount: Int,
    alpha: Double,
    volumes: String,           // JSON array: "[0.3, 0.2, ...]"
    emaBucketRequests: Double,
    updatedAt: Instant
)

/** Storage for traffic shape snapshots (for pacing continuity across restarts). */
trait TrafficShapeSnapshotRepo {
  /** Save or update the traffic shape for a site. */
  def upsert(siteId: String, snapshot: promovolve.publisher.delivery.TrafficShapeSnapshot): Future[Unit]

  /** Get the latest traffic shape for a site. */
  def get(siteId: String): Future[Option[promovolve.publisher.delivery.TrafficShapeSnapshot]]

  /** Delete the traffic shape for a site (for testing). */
  def delete(siteId: String): Future[Unit]
}

/** No-op implementation that discards all data. */
object NoOpTrafficShapeSnapshotRepo extends TrafficShapeSnapshotRepo {
  override def upsert(siteId: String, snapshot: promovolve.publisher.delivery.TrafficShapeSnapshot): Future[Unit] =
    Future.successful(())
  override def get(siteId: String): Future[Option[promovolve.publisher.delivery.TrafficShapeSnapshot]] =
    Future.successful(None)
  override def delete(siteId: String): Future[Unit] =
    Future.successful(())
}

/** In-memory implementation for testing. */
final class InMemoryTrafficShapeSnapshotRepo extends TrafficShapeSnapshotRepo {
  import promovolve.publisher.delivery.TrafficShapeSnapshot

  private val table = scala.collection.concurrent.TrieMap.empty[String, TrafficShapeSnapshot]

  override def upsert(siteId: String, snapshot: TrafficShapeSnapshot): Future[Unit] = {
    table.put(siteId, snapshot)
    Future.successful(())
  }

  override def get(siteId: String): Future[Option[TrafficShapeSnapshot]] =
    Future.successful(table.get(siteId))

  override def delete(siteId: String): Future[Unit] = {
    table.remove(siteId)
    Future.successful(())
  }
}

/** PostgreSQL-backed implementation using Slick. */
final class SlickTrafficShapeSnapshotRepo(db: Database)(using ec: ExecutionContext)
    extends TrafficShapeSnapshotRepo {

  import promovolve.publisher.delivery.TrafficShapeSnapshot

  private val shapes = TableQuery[TrafficShapeTable]

  def ensureSchema(): Unit = {
    val createAction = shapes.schema.createIfNotExists
    Await.result(db.run(createAction), 10.seconds)
  }

  override def upsert(siteId: String, snapshot: TrafficShapeSnapshot): Future[Unit] = {
    val volumesJson = snapshot.volumes.mkString("[", ",", "]")
    val row = TrafficShapeSnapshotRow(
      siteId            = siteId,
      bucketCount       = snapshot.bucketCount,
      alpha             = snapshot.alpha,
      volumes           = volumesJson,
      emaBucketRequests = snapshot.emaBucketRequests,
      updatedAt         = snapshot.updatedAt
    )

    // Use insertOrUpdate for upsert behavior
    val action = shapes.insertOrUpdate(row)
    db.run(action).map(_ => ())
  }

  override def get(siteId: String): Future[Option[TrafficShapeSnapshot]] = {
    val query = shapes.filter(_.siteId === siteId).result.headOption
    db.run(query).map(_.map(rowToSnapshot))
  }

  private def rowToSnapshot(row: TrafficShapeSnapshotRow): TrafficShapeSnapshot = {
    // Parse JSON array "[0.3, 0.2, ...]" to Array[Double]
    val volumes = row.volumes
      .stripPrefix("[")
      .stripSuffix("]")
      .split(",")
      .map(_.trim.toDouble)

    TrafficShapeSnapshot(
      bucketCount       = row.bucketCount,
      alpha             = row.alpha,
      volumes           = volumes,
      emaBucketRequests = row.emaBucketRequests,
      updatedAt         = row.updatedAt
    )
  }

  override def delete(siteId: String): Future[Unit] = {
    val action = shapes.filter(_.siteId === siteId).delete
    db.run(action).map(_ => ())
  }

  private class TrafficShapeTable(tag: Tag)
      extends Table[TrafficShapeSnapshotRow](tag, "traffic_shape_snapshot") {
    def * = (siteId, bucketCount, alpha, volumes, emaBucketRequests, updatedAt)
      .mapTo[TrafficShapeSnapshotRow]

    def siteId            = column[String]("site_id", O.PrimaryKey)

    def bucketCount       = column[Int]("bucket_count")

    def alpha             = column[Double]("alpha")

    def volumes           = column[String]("volumes")  // JSON array

    def emaBucketRequests = column[Double]("ema_bucket_requests")

    def updatedAt         = column[Instant]("updated_at")
  }
}