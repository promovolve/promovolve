package promovolve.api

trait EventLog {
  def logImpression(e: TrackEvent): Unit
  def logClick(e: TrackEvent): Unit
  def logCTAClick(e: TrackEvent): Unit

  /**
   * Record a fold event. The endpoint has already verified the FoldToken,
   * so `e.campaignId`/`e.advertiserId` come from the signed payload.
   * Folds are an engagement signal (Facebook-likes model), not billed —
   * the implementation just writes the journal entry. Idempotency
   * uses `e.requestId`.
   */
  def logFold(e: TrackEvent): Unit

  /** Record an unfold event. Telemetry only — no billing impact. */
  def logUnfold(e: TrackEvent): Unit
}

final case class TrackEvent(
    pub: String,
    url: String,
    slot: String,
    cid: String,
    version: Long,
    bucket: Long,
    ts: Long,
    ip: String,
    ua: String,
    // Optional: passed from serve response to avoid ServeView lookup race condition
    campaignId: Option[String] = None,
    advertiserId: Option[String] = None,
    cpm: Option[Double] = None,
    category: Option[String] = None,
    // Budget reservation requestId from TryReserve - ensures idempotent spend tracking
    requestId: Option[String] = None,
    // Ad product category for content-product affinity learning
    adProductCategory: Option[String] = None,
    // All page content categories (comma-separated) for cross-category affinity learning
    pageCategories: Option[String] = None,
    // True when this impression was served because the slot was pinned by a
    // prior fold (dog-ear re-encounter). Telemetry only; not billed via CPM.
    dogeared: Boolean = false
)
