package promovolve.publisher

import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * Persisted traffic shape for a site.
 *
 * One row per site - updated periodically as the pattern is learned.
 * Used to restore learned traffic patterns on AdServer restart.
 */
final case class TrafficShapeSnapshotRow(
    siteId: String,
    bucketCount: Int,
    alpha: Double,
    volumes: String, // JSON array: "[0.3, 0.2, ...]" — current shape (legacy restore path)
    emaBucketRequests: Double,
    // Weekday/weekend shapes learn independently (rolloverDay blends into
    // one or the other by day type) — persisting only `volumes` silently
    // collapsed the split on every restart (weekend learning could never
    // accumulate; found 2026-07-13). Nullable so pre-existing rows load.
    weekdayVolumes: Option[String],
    weekendVolumes: Option[String],
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

/**
 * Pure row ↔ snapshot mapping, extracted so serialization fidelity —
 * including the legacy-row fallback where weekday/weekend columns are
 * NULL — is unit-testable without a database.
 */
private[publisher] object TrafficShapeRowMapping {
  import promovolve.publisher.delivery.TrafficShapeSnapshot

  def toJson(volumes: Array[Double]): String = volumes.mkString("[", ",", "]")

  def parseVolumes(json: String): Array[Double] =
    json.stripPrefix("[").stripSuffix("]").split(",").map(_.trim.toDouble)

  def toRow(siteId: String, snapshot: TrafficShapeSnapshot): TrafficShapeSnapshotRow =
    TrafficShapeSnapshotRow(
      siteId = siteId,
      bucketCount = snapshot.bucketCount,
      alpha = snapshot.alpha,
      volumes = toJson(snapshot.volumes),
      emaBucketRequests = snapshot.emaBucketRequests,
      weekdayVolumes = snapshot.weekdayVolumes.map(toJson),
      weekendVolumes = snapshot.weekendVolumes.map(toJson),
      updatedAt = snapshot.updatedAt
    )

  def fromRow(row: TrafficShapeSnapshotRow): TrafficShapeSnapshot =
    TrafficShapeSnapshot(
      bucketCount = row.bucketCount,
      alpha = row.alpha,
      volumes = parseVolumes(row.volumes),
      emaBucketRequests = row.emaBucketRequests,
      updatedAt = row.updatedAt,
      // Absent on legacy rows — fromSnapshot then falls back to seeding
      // both shapes from `volumes`, exactly the pre-fix behavior.
      weekdayVolumes = row.weekdayVolumes.map(parseVolumes),
      weekendVolumes = row.weekendVolumes.map(parseVolumes)
    )
}

/** PostgreSQL-backed implementation using Slick. */
final class SlickTrafficShapeSnapshotRepo(db: Database)(using ec: ExecutionContext)
    extends TrafficShapeSnapshotRepo {

  import promovolve.publisher.delivery.TrafficShapeSnapshot

  private val shapes = TableQuery[TrafficShapeTable]

  def ensureSchema(): Unit = {
    val createAction = shapes.schema.createIfNotExists
    Await.result(db.run(createAction), 10.seconds)
    // Pre-existing tables (created before the weekday/weekend split was
    // persisted) need the columns added — createIfNotExists won't.
    val alter = DBIO.seq(
      sqlu"""ALTER TABLE traffic_shape_snapshot ADD COLUMN IF NOT EXISTS weekday_volumes TEXT""",
      sqlu"""ALTER TABLE traffic_shape_snapshot ADD COLUMN IF NOT EXISTS weekend_volumes TEXT"""
    )
    Await.result(db.run(alter), 10.seconds)
  }

  override def upsert(siteId: String, snapshot: TrafficShapeSnapshot): Future[Unit] = {
    // Use insertOrUpdate for upsert behavior
    val action = shapes.insertOrUpdate(TrafficShapeRowMapping.toRow(siteId, snapshot))
    db.run(action).map(_ => ())
  }

  override def get(siteId: String): Future[Option[TrafficShapeSnapshot]] = {
    val query = shapes.filter(_.siteId === siteId).result.headOption
    db.run(query).map(_.map(TrafficShapeRowMapping.fromRow))
  }

  override def delete(siteId: String): Future[Unit] = {
    val action = shapes.filter(_.siteId === siteId).delete
    db.run(action).map(_ => ())
  }

  private class TrafficShapeTable(tag: Tag)
      extends Table[TrafficShapeSnapshotRow](tag, "traffic_shape_snapshot") {
    def * = (siteId, bucketCount, alpha, volumes, emaBucketRequests, weekdayVolumes, weekendVolumes, updatedAt)
      .mapTo[TrafficShapeSnapshotRow]

    def siteId = column[String]("site_id", O.PrimaryKey)

    def bucketCount = column[Int]("bucket_count")

    def alpha = column[Double]("alpha")

    def volumes = column[String]("volumes") // JSON array

    def emaBucketRequests = column[Double]("ema_bucket_requests")

    def weekdayVolumes = column[Option[String]]("weekday_volumes") // JSON array, null on legacy rows

    def weekendVolumes = column[Option[String]]("weekend_volumes") // JSON array, null on legacy rows

    def updatedAt = column[Instant]("updated_at")
  }
}
