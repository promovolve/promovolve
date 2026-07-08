package promovolve.api

/** Invariants enforced on creative create/update. */
object CreativeGuards {

  /** A creative's landing page is its fixed identity anchor: copy,
    * images, and layout are editable, but the LP is not. The whole
    * creative is generated as a continuation of that LP, and the LP is
    * the unit cold-start exploration is (or will be) capped against —
    * so a learned, well-performing creative must never be re-pointed at
    * a new destination.
    *
    * Returns true when persisting `requested` over an existing creative
    * would change its landing URL, i.e. the write must be rejected.
    * `existing` is `None` for a brand-new creative (no prior row to
    * compare against), which is never a violation. */
  def landingUrlChanged(existing: Option[String], requested: String): Boolean =
    existing.exists(_ != requested)
}
