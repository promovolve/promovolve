package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.publisher.delivery.{ TrafficShapeSnapshot, TrafficShapeTracker }

import java.time.Instant

/**
 * The weekday/weekend split must survive the Postgres round-trip — the
 * Slick row used to drop both arrays, silently collapsing weekend
 * learning on every restart (found 2026-07-13). The SQL column add is
 * exercised at boot (ensureSchema ALTER TABLE IF NOT EXISTS); this
 * covers the pure row ↔ snapshot mapping either side of it.
 */
class TrafficShapePersistenceSpec extends AnyWordSpec with Matchers {

  private val weekday = Array.tabulate(24)(h => if (h >= 9 && h <= 17) 2.0 else 0.5)
  private val weekend = Array.tabulate(24)(h => if (h >= 11 && h <= 22) 1.8 else 0.4)

  private val snapshot = TrafficShapeSnapshot(
    bucketCount = 24,
    alpha = 0.1,
    volumes = weekday,
    emaBucketRequests = 42.5,
    updatedAt = Instant.parse("2026-07-13T04:02:00Z"),
    weekdayVolumes = Some(weekday),
    weekendVolumes = Some(weekend)
  )

  "TrafficShapeRowMapping" should {

    "round-trip BOTH shape arrays through the row" in {
      val row = TrafficShapeRowMapping.toRow("site-a", snapshot)
      row.weekdayVolumes.isDefined shouldBe true
      row.weekendVolumes.isDefined shouldBe true

      val back = TrafficShapeRowMapping.fromRow(row)
      back.weekdayVolumes.get.toSeq shouldBe weekday.toSeq
      back.weekendVolumes.get.toSeq shouldBe weekend.toSeq
      back.volumes.toSeq shouldBe weekday.toSeq
      back.emaBucketRequests shouldBe 42.5
      back.updatedAt shouldBe snapshot.updatedAt
    }

    "legacy row (NULL weekday/weekend columns) restores via the single-array fallback" in {
      val legacy = TrafficShapeRowMapping
        .toRow("site-a", snapshot)
        .copy(weekdayVolumes = None, weekendVolumes = None)
      val back = TrafficShapeRowMapping.fromRow(legacy)
      back.weekdayVolumes shouldBe None
      back.weekendVolumes shouldBe None

      // fromSnapshot then seeds BOTH shapes from `volumes` — the exact
      // pre-fix behavior, so old rows keep working.
      val tracker = TrafficShapeTracker.fromSnapshot(back)
      tracker.weekdayVolumes.toSeq shouldBe weekday.toSeq
      tracker.weekendVolumes.toSeq shouldBe weekday.toSeq
    }

    "restored tracker does NOT re-enter bootstrap intra-day learning" in {
      // A restored shape is known: only the daily rollover blend may
      // change it. Pre-fix, useLegacyMode survived restore, so every
      // restart day re-applied the per-bucket EMA on top of the learned
      // shape (up to 23 x alpha=0.1 pulls vs the intended 0.2/day).
      val back = TrafficShapeRowMapping.fromRow(TrafficShapeRowMapping.toRow("site-a", snapshot))
      val tracker = TrafficShapeTracker.fromSnapshot(back)
      val before = tracker.weekdayVolumes.toSeq

      // Simulate a busy boot day crossing many bucket boundaries.
      for (bucket <- 0 until 24) {
        for (_ <- 0 until 500) tracker.recordRequest(bucket * 3600 + 1800)
      }
      tracker.flush()
      tracker.weekdayVolumes.toSeq shouldBe before

      // A FRESH tracker under the same traffic DOES learn intra-day.
      val fresh = new TrafficShapeTracker(bucketCount = 24)
      val freshBefore = fresh.weekdayVolumes.toSeq
      for (bucket <- 0 until 24) {
        val n = if (bucket == 12) 2000 else 100
        for (_ <- 0 until n) fresh.recordRequest(bucket * 3600 + 1800)
      }
      fresh.flush()
      fresh.weekdayVolumes.toSeq should not be freshBefore
    }

    "restored tracker keeps the two shapes distinct end to end" in {
      val back = TrafficShapeRowMapping.fromRow(TrafficShapeRowMapping.toRow("site-a", snapshot))
      val tracker = TrafficShapeTracker.fromSnapshot(back, interpolateVolumes = true)
      tracker.weekdayVolumes.toSeq shouldBe weekday.toSeq
      tracker.weekendVolumes.toSeq shouldBe weekend.toSeq
      tracker.interpolateVolumes shouldBe true
    }
  }
}
