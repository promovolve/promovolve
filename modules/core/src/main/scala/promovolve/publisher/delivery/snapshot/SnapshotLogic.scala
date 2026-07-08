package promovolve.publisher.delivery.snapshot

import promovolve.CreativeId
import promovolve.publisher.CreativeStatsSnapshot
import promovolve.publisher.delivery.Protocol.CreativeStats

import java.time.Instant

/** Pure helper functions for stats snapshot operations.
  *
  * Contains stateless logic extracted from AdServer for testability
  * and reuse. All functions are pure - no side effects.
  */
object SnapshotLogic {

  /** Convert internal creative stats to snapshot format.
    *
    * Creates CreativeStatsSnapshot instances ready for persistence
    * from the in-memory stats map.
    *
    * @param siteId        Publisher/site identifier
    * @param creativeStats Map of creative ID to stats
    * @param snapshotAt    Snapshot timestamp
    * @return Sequence of snapshots ready to save
    */
  def toSnapshots(
      siteId: String,
      creativeStats: Map[CreativeId, CreativeStats],
      snapshotAt: Instant = Instant.now()
  ): Seq[CreativeStatsSnapshot] =
    creativeStats.map { case (cid, stats) =>
      CreativeStatsSnapshot(
        siteId      = siteId,
        creativeId  = cid.value,
        impressions = stats.impressions,
        clicks      = stats.clicks,
        snapshotAt  = snapshotAt
      )
    }.toSeq

  /** Check if stats are worth snapshotting.
    *
    * @param creativeStats Current stats
    * @return true if there are any stats to snapshot
    */
  def hasStatsToSnapshot(creativeStats: Map[CreativeId, CreativeStats]): Boolean =
    creativeStats.nonEmpty

  /** Merge existing stats with loaded snapshots.
    *
    * Used when recovering from persistence. Creates synthetic bucket entries
    * from snapshot totals, then merges with current sliding window data.
    *
    * Note: CreativeStats uses a sliding window bucket structure. Loaded
    * snapshot totals are placed in a synthetic bucket at the snapshot time.
    *
    * @param current     Current in-memory stats
    * @param loaded      Stats loaded from snapshot
    * @param snapshotAt  When the snapshot was taken (for bucket placement)
    * @return Merged stats map
    */
  def mergeWithSnapshot(
      current: Map[CreativeId, CreativeStats],
      loaded: Seq[CreativeStatsSnapshot],
      snapshotAt: Instant
  ): Map[CreativeId, CreativeStats] = {
    val minute = snapshotAt.getEpochSecond / 60

    val loadedMap = loaded.map { snap =>
      // Create a synthetic bucket from snapshot totals.
      // CreativeStats.buckets carries (impressions, clicks, folds) since the
      // dog-ear refactor; the snapshot schema only tracks impressions+clicks,
      // so folds defaults to 0 on restore.
      val buckets = Map(minute -> (snap.impressions, snap.clicks, 0))
      CreativeId(snap.creativeId) -> CreativeStats(buckets = buckets)
    }.toMap

    (current.keySet ++ loadedMap.keySet).map { cid =>
      val currentStats = current.getOrElse(cid, CreativeStats())
      val loadedStats = loadedMap.getOrElse(cid, CreativeStats())
      // Merge buckets from both sources
      val mergedBuckets = currentStats.buckets ++ loadedStats.buckets
      cid -> CreativeStats(buckets = mergedBuckets)
    }.toMap
  }

  /** Convert snapshot data back to CreativeStats.
    *
    * Creates a CreativeStats with a single synthetic bucket containing
    * the snapshot totals. Used when recovering from persistence.
    *
    * @param snapshot   The snapshot to convert
    * @param atMinute   Minute bucket to place the stats (defaults to snapshot time)
    * @return CreativeStats with the snapshot data
    */
  def fromSnapshot(snapshot: CreativeStatsSnapshot, atMinute: Option[Long] = None): CreativeStats = {
    val minute = atMinute.getOrElse(snapshot.snapshotAt.getEpochSecond / 60)
    // (impressions, clicks, folds) — folds=0 since the snapshot schema
    // pre-dates dog-ear tracking and only stores impressions+clicks.
    CreativeStats(buckets = Map(minute -> (snapshot.impressions, snapshot.clicks, 0)))
  }

  /** Calculate click-through rate (CTR) for a creative.
    *
    * @param stats Creative stats
    * @return CTR as a value between 0.0 and 1.0
    */
  def calculateCtr(stats: CreativeStats): Double =
    if (stats.impressions > 0) stats.clicks.toDouble / stats.impressions.toDouble
    else 0.0

  /** Get top performing creatives by CTR.
    *
    * @param creativeStats All creative stats
    * @param minImpressions Minimum impressions required for consideration
    * @param topN           Number of top performers to return
    * @return Sorted sequence of (creativeId, ctr) pairs
    */
  def topPerformers(
      creativeStats: Map[CreativeId, CreativeStats],
      minImpressions: Int = 10,
      topN: Int = 10
  ): Seq[(CreativeId, Double)] =
    creativeStats
      .filter(_._2.impressions >= minImpressions)
      .map { case (cid, stats) => (cid, calculateCtr(stats)) }
      .toSeq
      .sortBy(-_._2)
      .take(topN)

  /** Aggregate stats across all creatives.
    *
    * @param creativeStats All creative stats
    * @return Tuple of (totalImpressions, totalClicks, overallCtr)
    */
  def aggregateStats(creativeStats: Map[CreativeId, CreativeStats]): (Int, Int, Double) = {
    val totalImpressions = creativeStats.values.map(_.impressions).sum
    val totalClicks = creativeStats.values.map(_.clicks).sum
    val overallCtr = if (totalImpressions > 0) totalClicks.toDouble / totalImpressions else 0.0
    (totalImpressions, totalClicks, overallCtr)
  }
}