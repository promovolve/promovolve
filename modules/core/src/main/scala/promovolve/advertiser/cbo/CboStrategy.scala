package promovolve.advertiser.cbo

/**
 * Per-campaign budget strategy (Campaign Budget Optimization, GH #38).
 * Plain strings: they are persisted in Jackson state and cross the wire.
 */
object CboStrategy {

  /** The campaign keeps its own daily wall. */
  val Fixed: String = "fixed"

  /** The advertiser's allocator owns the campaign's daily wall. */
  val Auto: String = "auto"

  def isValid(s: String): Boolean = s == Fixed || s == Auto
}

/** Per-advertiser budget mode: whether the allocator runs at all. */
object CboBudgetMode {

  /** Every campaign keeps its own wall; the account budget is a hard cap only. */
  val Manual: String = "manual"

  /** The allocator re-splits the account budget across `auto` campaigns. */
  val Optimized: String = "optimized"

  def isValid(s: String): Boolean = s == Manual || s == Optimized
}
