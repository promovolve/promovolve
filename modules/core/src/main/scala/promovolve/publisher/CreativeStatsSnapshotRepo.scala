package promovolve.publisher

import slick.jdbc.PostgresProfile.api.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }

/** Snapshot of per-creative stats for a site at a point in time. */
final case class CreativeStatsSnapshot(
    siteId: String,
    creativeId: String,
    impressions: Int,
    clicks: Int,
    snapshotAt: Instant
)

/** Storage for creative stats snapshots (for dashboard). */
trait CreativeStatsSnapshotRepo {
  def save(snapshots: Seq[CreativeStatsSnapshot]): Future[Unit]
  def getLatest(siteId: String): Future[Seq[CreativeStatsSnapshot]]
  def getHistory(siteId: String, creativeId: String, from: Instant, to: Instant): Future[Seq[CreativeStatsSnapshot]]

  /**
   * Load recent per-creative stats from tracking_events for Thompson Sampling state restoration.
   * Returns Map[creativeId, Map[minuteEpoch, (impressions, clicks, folds)]].
   * Folds default to 0 for legacy backends that don't track dog-ear engagements.
   */
  def loadRecentStats(siteId: String, windowMinutes: Int = 60): Future[Map[String, Map[Long, (Int, Int, Int)]]] =
    Future.successful(Map.empty)
}

/** No-op implementation that discards all data. */
object NoOpCreativeStatsSnapshotRepo extends CreativeStatsSnapshotRepo {
  override def save(snapshots: Seq[CreativeStatsSnapshot]): Future[Unit] = Future.successful(())
  override def getLatest(siteId: String): Future[Seq[CreativeStatsSnapshot]] = Future.successful(Seq.empty)
  override def getHistory(siteId: String, creativeId: String, from: Instant, to: Instant)
      : Future[Seq[CreativeStatsSnapshot]] = Future.successful(Seq.empty)
}

/** In-memory implementation for testing. */
final class InMemoryCreativeStatsSnapshotRepo extends CreativeStatsSnapshotRepo {
  private val table = scala.collection.concurrent.TrieMap.empty[(String, String, Instant), CreativeStatsSnapshot]

  override def save(snapshots: Seq[CreativeStatsSnapshot]): Future[Unit] = {
    snapshots.foreach { s =>
      table.put((s.siteId, s.creativeId, s.snapshotAt), s)
    }
    Future.successful(())
  }

  override def getLatest(siteId: String): Future[Seq[CreativeStatsSnapshot]] = {
    val siteSnapshots = table.values.filter(_.siteId == siteId).toSeq
    val latestTime = siteSnapshots.map(_.snapshotAt).maxOption
    Future.successful(
      latestTime.map(t => siteSnapshots.filter(_.snapshotAt == t)).getOrElse(Seq.empty)
    )
  }

  override def getHistory(
      siteId: String,
      creativeId: String,
      from: Instant,
      to: Instant
  ): Future[Seq[CreativeStatsSnapshot]] = {
    val results = table.values.filter { s =>
      s.siteId == siteId &&
      s.creativeId == creativeId &&
      !s.snapshotAt.isBefore(from) &&
      !s.snapshotAt.isAfter(to)
    }.toSeq.sortBy(_.snapshotAt)
    Future.successful(results)
  }
}

/** PostgreSQL-backed implementation using Slick. */
final class SlickCreativeStatsSnapshotRepo(db: Database)(using ec: ExecutionContext)
    extends CreativeStatsSnapshotRepo {

  private val snapshots = TableQuery[StatsSnapshotTable]

  def ensureSchema(): Unit = {
    // Use raw SQL to avoid Slick's createIfNotExists trying to add duplicate primary key
    val createTableSql = sqlu"""
      CREATE TABLE IF NOT EXISTS creative_stats_snapshot (
        site_id VARCHAR(255) NOT NULL,
        creative_id VARCHAR(255) NOT NULL,
        impressions INTEGER NOT NULL,
        clicks INTEGER NOT NULL,
        snapshot_at TIMESTAMP NOT NULL,
        PRIMARY KEY (site_id, creative_id, snapshot_at)
      )
    """
    Await.result(db.run(createTableSql), 10.seconds)
  }

  override def save(records: Seq[CreativeStatsSnapshot]): Future[Unit] = {
    val action = snapshots ++= records
    db.run(action).map(_ => ())
  }

  override def getLatest(siteId: String): Future[Seq[CreativeStatsSnapshot]] = {
    // Get the latest snapshot time for this site
    val latestTimeQuery = snapshots
      .filter(_.siteId === siteId)
      .map(_.snapshotAt)
      .max

    val query = for {
      latestOpt <- latestTimeQuery.result
      results <- latestOpt match {
        case Some(latest) =>
          snapshots.filter(s => s.siteId === siteId && s.snapshotAt === latest).result
        case None =>
          DBIO.successful(Seq.empty)
      }
    } yield results

    db.run(query)
  }

  override def getHistory(
      siteId: String,
      creativeId: String,
      from: Instant,
      to: Instant
  ): Future[Seq[CreativeStatsSnapshot]] = {
    val query = snapshots
      .filter(s =>
        s.siteId === siteId &&
        s.creativeId === creativeId &&
        s.snapshotAt >= from &&
        s.snapshotAt <= to
      )
      .sortBy(_.snapshotAt)
      .result

    db.run(query)
  }

  private class StatsSnapshotTable(tag: Tag)
      extends Table[CreativeStatsSnapshot](tag, "creative_stats_snapshot") {
    def pk = primaryKey("pk_stats_snapshot", (siteId, creativeId, snapshotAt))

    def siteId = column[String]("site_id")

    def creativeId = column[String]("creative_id")

    def snapshotAt = column[Instant]("snapshot_at")

    def * = (siteId, creativeId, impressions, clicks, snapshotAt).mapTo[CreativeStatsSnapshot]

    def impressions = column[Int]("impressions")

    def clicks = column[Int]("clicks")
  }
}

/**
 * TimescaleDB-backed implementation that loads per-minute stats from tracking_events.
 *
 * Delegates save/getLatest/getHistory to SlickCreativeStatsSnapshotRepo for dashboard,
 * and adds loadRecentStats from the tracking_events hypertable for Thompson Sampling restoration.
 */
final class TimescaleCreativeStatsRepo(db: Database)(using ec: ExecutionContext)
    extends CreativeStatsSnapshotRepo {

  private val delegate = new SlickCreativeStatsSnapshotRepo(db)

  def ensureSchema(): Unit = delegate.ensureSchema()

  override def save(snapshots: Seq[CreativeStatsSnapshot]): Future[Unit] =
    delegate.save(snapshots)

  override def getLatest(siteId: String): Future[Seq[CreativeStatsSnapshot]] =
    delegate.getLatest(siteId)

  override def getHistory(siteId: String, creativeId: String, from: Instant, to: Instant)
      : Future[Seq[CreativeStatsSnapshot]] =
    delegate.getHistory(siteId, creativeId, from, to)

  override def loadRecentStats(siteId: String, windowMinutes: Int): Future[Map[String, Map[Long, (Int, Int, Int)]]] = {
    // `tracking_events` doesn't yet record fold events as a distinct
    // event_type, so we project 0 folds. When fold tracking lands in
    // the projection schema, swap the literal for the matching
    // COUNT(*) FILTER and the rest of the pipeline picks it up.
    val query = sql"""
      SELECT creative_id,
             (EXTRACT(EPOCH FROM event_time)::bigint / 60) AS minute_key,
             COUNT(*) FILTER (WHERE event_type = 'impression') AS impressions,
             COUNT(*) FILTER (WHERE event_type = 'click') AS clicks,
             0 AS folds
      FROM tracking_events
      WHERE site_id = $siteId
        AND event_time > NOW() - make_interval(mins => $windowMinutes)
      GROUP BY creative_id, minute_key
    """.as[(String, Long, Int, Int, Int)]

    db.run(query).map { rows =>
      rows.groupBy(_._1).map { case (creativeId, entries) =>
        creativeId -> entries.map { case (_, minute, imps, clicks, folds) => minute -> (imps, clicks, folds) }.toMap
      }
    }
  }
}
