package promovolve.publisher

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Snapshot of a sweep cycle's per-candidate measurement, for persistence
 * alongside the argmax pick. Kept as a plain Scala value so the
 * `core` module doesn't need to depend on any API-side JSON encoders;
 * the `api` module's writer serializes it however it likes.
 */
final case class FloorDecisionCandidate(
    floor: Double,
    revenue: Double,
    impressions: Long
)

/**
 * What `SiteEntity` calls when it finishes a sweep cycle and picks an
 * argmax. The writer is implemented in the `api` module
 * (`FloorDecisionJournal`); we keep only the interface here so core
 * stays decoupled from the persistence layer.
 */
trait FloorDecisionWriter {
  def writeDecision(
      siteId: String,
      ts: Instant,
      argmaxFloor: Double,
      prevArgmax: Option[Double],
      cycleRevenue: Double,
      cycleImps: Long,
      candidates: Vector[FloorDecisionCandidate],
      // None = the site-wide sweep; Some(cat) = a per-category sweep
      // (per-category floors). Defaulted so existing callers are unaffected.
      category: Option[String] = None
  ): Unit
}

/**
 * Global hook for the optional floor-decision writer. Set once by the
 * api module at HttpBootstrap; read by every SiteEntity at argmax-pick
 * time. The `Option` lets the system run with no writer configured
 * (early boot, tests, decision-persistence disabled) — sites will just
 * skip the persisted write and continue working normally.
 *
 * The "static singleton" shape is deliberate: passing this through the
 * sharding/entity-factory plumbing would touch ClusterBootstrap and the
 * SiteEntity signature for marginal benefit. The writer is set exactly
 * once at startup and read at runtime; there's no concurrency hazard.
 */
object FloorDecisionRecorder {
  private val ref = new AtomicReference[Option[FloorDecisionWriter]](None)
  def set(w: FloorDecisionWriter): Unit = ref.set(Some(w))
  def clear(): Unit = ref.set(None)
  def current: Option[FloorDecisionWriter] = ref.get()
}
