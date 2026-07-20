package promovolve.publisher

import promovolve.{ CborSerializable, SiteId }

/**
 * Direct floor-CPM optimizer: instead of learning a value function, sweep
 * candidate floors, hold each for one observation window, measure realized
 * revenue, and pick argmax. Then hold the winner for `exploitTicks` windows
 * before re-sweeping to track market drift.
 *
 * The sole publisher-side floor mechanism.
 *
 * Why direct optimization rather than RL: changing `SiteState.floorCpm`
 * already triggers a re-auction in `AuctioneerEntity` (see
 * `scheduleReauction` on `UpdateFloorCpm`), which re-collects fresh bids
 * from CampaignEntity at the new threshold. So each candidate floor
 * naturally gets its own real revenue measurement — no Q-function needed.
 */
final class FloorSweepOptimizer(
    siteId: SiteId,
    config: FloorSweepOptimizer.Config = FloorSweepOptimizer.Config()
) {

  import FloorSweepOptimizer.*

  private var _currentFloor: Double = 0.50
  private var _minFloor: Double = 0.10

  private var phase: String = Phase.Init
  private var cursor: Int = 0
  private var candidates: Vector[Double] = Vector.empty
  // floor -> (totalRevenue, totalImpressions) accumulated across sweep
  private var results: Map[Double, (Double, Long)] = Map.empty
  // Last completed cycle's results, preserved across Phase.Init so the
  // dashboard can show this-cycle-vs-last-cycle deltas. Empty until the
  // optimizer has finished its first full cycle.
  private var previousResults: Map[Double, (Double, Long)] = Map.empty
  private var previousBestFloor: Option[Double] = None
  private var bestFloor: Option[Double] = None
  private var exploitTicksRemaining: Int = 0
  // Ticks spent on the current candidate. We hold each candidate for
  // `config.ticksPerCandidate` ticks before advancing, so per-candidate
  // measurement averages over more impressions and the argmax stops
  // bouncing on single-tick noise. Reset on cursor advance.
  private var ticksThisCandidate: Int = 0

  private var windowRevenue: Double = 0.0
  private var windowImpressions: Long = 0L

  // Highest bid observed across auctions in the CURRENT measurement
  // cycle (resets at each Phase.Init). Used to cap the next cycle's
  // candidate-search upper bound — there's no point testing floors
  // above the highest bid we just saw, because they're mechanically
  // guaranteed to produce zero revenue.
  //
  // Per-cycle (rather than all-time) so a one-time high bidder that
  // later leaves the market doesn't permanently pin the sweep ceiling
  // at a stale value. AdServer reports the pre-floor max bid (see
  // CategoryBidResponse.maxRejectedCpm), so this stays accurate even
  // when the sweep is currently probing a candidate above the actual
  // bid ceiling.
  //
  // Persisted in the snapshot so the cap survives restarts.
  private var maxBidObserved: Double = 0.0

  // Lowest bid observed across auctions in the current measurement
  // cycle (resets at each Phase.Init). Spans both qualifying bids and
  // below-floor rejects — AuctioneerEntity reports
  // `min(qualifyingMin, minRejectedCpm)` so a campaign whose bid was
  // rejected at the current floor still pulls the next cycle's lower
  // bound down. Without that, once the floor settled high enough to
  // exclude the cheaper bidders, the lower bound would collapse to
  // the qualifying-min and the rejected campaigns would be
  // permanently excluded from future cycles.
  //
  // 0.0 = sentinel for "no bids observed at all this cycle".
  // Per-cycle for the same reason as maxBidObserved: avoid sticky
  // state from a single odd cycle.
  private var minBidObserved: Double = 0.0

  // Highest bid observed during the CURRENT candidate hold window
  // (resets on every cursor advance). The mid-sweep contraction uses
  // this — not the cycle-long `maxBidObserved` — as its ceiling,
  // because the cycle max is a monotone high-water mark: a bidder that
  // lowered its bid (or left) after being observed once early in the
  // cycle would otherwise keep the contraction blind for the rest of
  // the cycle, camping ticksPerCandidate on floors nobody can pay
  // (observed live 2026-07-20: a $12→$10 bid cut mid-cycle left the
  // sweep probing $10.29–$12.00 for hours of guaranteed no-fill).
  // 0.0 = no auctions this window; the contraction then falls back to
  // the cycle max, which is the best information available.
  private var windowMaxBid: Double = 0.0

  def currentFloorCpm: Double = _currentFloor

  /**
   * Diagnostic "exploration intensity" in [0, 1], recorded per tick in
   * the FloorObservation ring buffer (the RL-flavored name is historical).
   * Returns the REMAINING sweep fraction during the Sweep phase, 0.0
   * during Exploit, and 1.0 during Init (about to start a fresh sweep).
   */
  def epsilon: Double = phase match {
    case Phase.Init  => 1.0
    case Phase.Sweep =>
      if (candidates.isEmpty) 1.0
      else (candidates.size - cursor).toDouble / candidates.size.toDouble
    case Phase.Exploit => 0.0
    case _             => 0.0
  }

  def setInitialFloor(floor: Double): Unit = {
    _currentFloor = floor
  }

  def setMinFloor(min: Double): Unit = {
    _minFloor = min
    if (_currentFloor < min) _currentFloor = min
  }

  /**
   * Collapse the floor to the publisher minimum and wipe all sweep state.
   * Called when a category's demand drops to an EXPLICIT zero bidders (e.g.
   * the sole bidder's creative was flagged/rejected/revoked): the reserve
   * that was pinned to the departed bid is no longer justified, and the
   * ordinary sweep would only drain it over a full 92-tick cycle (~90 min
   * dev / ~23 h prod), keeping the floor elevated and locking out other
   * legitimate bidders in the meantime. Clears `maxBidObserved`/
   * `minBidObserved` too so a later re-entrant bidder starts a clean sweep
   * from `_minFloor` instead of resuming a stale high-anchored range.
   * Idempotent — safe to call every tick a category stays empty.
   */
  def resetToMinFloor(): Unit = {
    _currentFloor = _minFloor
    maxBidObserved = 0.0
    minBidObserved = 0.0
    windowMaxBid = 0.0
    results = Map.empty
    candidates = Vector.empty
    cursor = 0
    ticksThisCandidate = 0
    bestFloor = None
    windowRevenue = 0.0
    windowImpressions = 0L
    exploitTicksRemaining = 0
    phase = Phase.Init
  }

  /**
   * Auction-level telemetry. Sweep optimizer mostly uses post-serve
   * revenue as its signal, but it captures `maxObservedCpm` and
   * `minObservedCpm` from each auction to clamp the candidate-search
   * range — there's no value in testing floors outside `[min, max]`
   * of observed bids, because those candidates are either revenue-zero
   * (above max) or revenue-indistinguishable (below min).
   */
  def recordAuctionOutcome(outcome: AuctionOutcome): Unit = {
    recordObservedBid(outcome.maxObservedCpm)
    recordObservedMinBid(outcome.minObservedCpm)
  }

  def recordBudgetExhausted(): Unit = {
    // No-op. Sweep optimizer treats budget exhaustion as revenue the
    // current floor produced — it's already reflected in served revenue.
  }

  /** Accumulate served-impression revenue for the current window. */
  def recordServedImpression(revenueDollars: Double): Unit = {
    windowRevenue += revenueDollars
    windowImpressions += 1L
  }

  /**
   * Record the maximum bid observed in an auction (whether or not an
   * impression was served). Caller is AdServer, which sees every bid;
   * the optimizer uses this to cap its candidate-search range so it
   * doesn't waste measurement budget on floors above the max bid.
   * Idempotent and monotonically non-decreasing per call.
   */
  def recordObservedBid(bidCpm: Double): Unit = {
    if (bidCpm > maxBidObserved) maxBidObserved = bidCpm
    if (bidCpm > windowMaxBid) windowMaxBid = bidCpm
  }

  /**
   * Record the minimum bid observed in an auction. Symmetric counterpart
   * to `recordObservedBid`. Caller passes `min(qualifyingMin,
   * minRejectedCpm)` so below-floor rejects pull the next cycle's
   * lower bound down too. 0.0 is treated as sentinel "no bids" and
   * ignored.
   */
  def recordObservedMinBid(bidCpm: Double): Unit = {
    if (bidCpm > 0.0 && (minBidObserved == 0.0 || bidCpm < minBidObserved))
      minBidObserved = bidCpm
  }

  /** Diagnostic accessor — exposed for tests and dashboard. */
  def maxBidObservedCpm: Double = maxBidObserved

  /** Diagnostic accessor — exposed for tests and dashboard. */
  def minBidObservedCpm: Double = minBidObserved

  /**
   * Called by SiteEntity once per observation tick. Decides the next
   * floor and returns it (or None if no change). The sweep keys purely
   * off realized revenue — no time-of-day / traffic-shape context.
   */
  def observe(): Option[ObserveResult] = phase match {

    case Phase.Init =>
      // The just-completed cycle's data is in `results` + `bestFloor`
      // at this moment. Package it as a decision payload BEFORE wiping —
      // the caller (SiteEntity) writes it to the decision journal only
      // AFTER the state persist succeeds. Writing here (pre-persist)
      // would double-write the row when a crash lands between the write
      // and the persist: recovery restores phase=Init with results
      // intact and this branch runs again.
      val completedCycle =
        if (results.nonEmpty && bestFloor.isDefined) {
          val cands = results.toVector
            .sortBy(_._1)
            .map { case (f, (r, i)) => FloorDecisionCandidate(f, r, i) }
          Some(CycleDecision(
            argmaxFloor = bestFloor.get,
            prevArgmax = previousBestFloor,
            cycleRevenue = results.values.map(_._1).sum,
            cycleImps = results.values.map(_._2).sum,
            candidates = cands
          ))
        } else None
      // Carry the just-completed cycle's per-candidate results forward
      // as `previousResults` BEFORE wiping `results`. The dashboard uses
      // this to show "this cycle vs last cycle" deltas, which is the
      // most useful answer to the publisher's "did anything change?".
      // First-cycle case: previousResults stays empty until cycle 2.
      previousResults = results
      previousBestFloor = bestFloor
      // Clamp the candidate range to the bids actually observed in the
      // just-completed cycle. Floors above max-bid produce zero revenue
      // by construction; floors below min-bid produce identical revenue
      // to floor=min-bid (same bidder set, same competitively-pinned
      // clearing price) and strictly worse exploration revenue. Either
      // way, probing outside [min, max] wastes a candidate slot.
      //
      // Both bounds are unbiased by the current floor: AuctioneerEntity
      // folds below-floor rejects into the reported min/max, so a
      // campaign whose bid was rejected at the current floor still
      // pulls the next cycle's bounds outward to include itself. The
      // remaining blind spot is brand-new bidders that arrive after
      // Phase.Init — they only enter the cycle on the next Init.
      //
      // Cold-start / quiet-cycle: when no bids were observed at all,
      // we deliberately do NOT sweep upward. Probing a high floor
      // before any bidder has shown up is exactly the failure mode
      // that starves traffic at $10 while the only campaign bids $1.
      // Instead we collapse to a single candidate at `_minFloor` (the
      // publisher's hard floor — lowest CPM they'll accept). That
      // admits the widest bidder set and feeds the next cycle a real
      // `maxBidObserved` signal. When bids HAVE been observed, the
      // ceiling tracks the highest one outright — there's no value in
      // probing above what the market actually bid, and a static cap
      // would only suppress extraction from a genuinely-higher bidder.
      val effectiveMax =
        if (maxBidObserved > 0.0) maxBidObserved
        else _minFloor
      val effectiveMin =
        if (minBidObserved > 0.0) math.max(_minFloor, minBidObserved)
        else _minFloor
      // Single-price-market escape: when every observed bid is the same
      // value (min == max — e.g. two campaigns both bidding $5.00), the
      // range collapses to ONE candidate sitting exactly on the sole bid,
      // and each new cycle re-observes that same bid and re-boxes itself —
      // the sweep can never measure anything else (observed live
      // 2026-07-06: "probing candidate 1/1 at $5.00" forever). Widen one
      // step below the sole price so the cycle compares at-the-bid vs
      // under-the-bid and the argmax has a real choice.
      val (candMin, candMax) =
        if (effectiveMax > effectiveMin) (effectiveMin, effectiveMax)
        else if (effectiveMax > _minFloor)
          (math.max(_minFloor, round2(effectiveMax * 0.8)), effectiveMax)
        else (effectiveMin, effectiveMax)
      // Reset accumulators so this cycle's record* calls start fresh.
      // Without this, a one-time outlier would permanently bias the
      // sweep range.
      maxBidObserved = 0.0
      minBidObserved = 0.0
      windowMaxBid = 0.0
      candidates = buildCandidates(candMin, candMax, config.candidateCount)
      results = Map.empty
      cursor = 0
      ticksThisCandidate = 0
      bestFloor = None
      windowRevenue = 0.0
      windowImpressions = 0L
      val first = candidates(0)
      _currentFloor = first
      phase = Phase.Sweep
      Some(ObserveResult(first, completedCycle))

    case Phase.Sweep =>
      // Accumulate this tick's window into the current candidate's bucket,
      // then advance the cursor only after the candidate has held for
      // `ticksPerCandidate` ticks. This averages each candidate over
      // multiple ticks so single-tick noise doesn't pick the argmax.
      val sampled = candidates(cursor)
      val prior = results.getOrElse(sampled, (0.0, 0L))
      results = results.updated(sampled, (prior._1 + windowRevenue, prior._2 + windowImpressions))
      windowRevenue = 0.0
      windowImpressions = 0L
      ticksThisCandidate += 1

      if (ticksThisCandidate < math.max(1, config.ticksPerCandidate)) {
        // Still holding the current candidate — same floor, no message.
        None
      } else {
        ticksThisCandidate = 0
        cursor += 1
        // Mid-sweep contraction: once any bid has been observed (either
        // a qualifying bid or a below-floor reject), we know the market's
        // current ceiling. Fast-forward past any remaining candidates
        // above it — those produce zero revenue by construction, and
        // sitting on them just starves the only bidder for entire tick
        // windows. This matters when a cycle was built wide from a
        // prior high bid that then vanished: the contraction skips the
        // now-dead upper candidates instead of camping ticksPerCandidate
        // on each.
        //
        // The ceiling is the max bid seen during the just-finished hold
        // window, NOT the cycle-long `maxBidObserved`: the cycle max is
        // monotone, so a bid observed once early in the cycle and then
        // lowered would pin the ceiling high and disable the contraction
        // for the whole cycle. A window with no auctions (windowMaxBid
        // == 0) carries no new information, so we fall back to the
        // cycle max there.
        val contractionCeiling =
          if (windowMaxBid > 0.0) windowMaxBid else maxBidObserved
        if (contractionCeiling > 0.0) {
          while (cursor < candidates.size && candidates(cursor) > contractionCeiling)
            cursor += 1
        }
        windowMaxBid = 0.0
        if (cursor < candidates.size) {
          val next = candidates(cursor)
          _currentFloor = next
          Some(ObserveResult(next))
        } else {
          // Sweep complete — pick the winner. Filter to candidates at or
          // above the current minFloor since the publisher may have raised
          // minFloor mid-sweep.
          val eligible = results.filter { case (floor, _) => floor >= _minFloor }
          val winner = pickBest(
            if (eligible.nonEmpty) eligible else results,
            config.minImpsForArgmax,
            config.tieTolerance
          )
          val clampedWinner = math.max(winner, _minFloor)
          bestFloor = Some(clampedWinner)
          _currentFloor = clampedWinner
          exploitTicksRemaining = config.exploitTicks
          phase = Phase.Exploit
          Some(ObserveResult(clampedWinner))
        }
      }

    case Phase.Exploit =>
      // Drop revenue accumulated under the exploit floor — the next sweep
      // will re-measure it from scratch alongside the other candidates.
      windowRevenue = 0.0
      windowImpressions = 0L
      exploitTicksRemaining -= 1
      if (exploitTicksRemaining <= 0) {
        phase = Phase.Init
        None
      } else {
        None
      }

    case _ =>
      // Defensive: unknown phase tag (e.g. from a corrupt snapshot). Reset.
      phase = Phase.Init
      None
  }

  def snapshot(): Snapshot = Snapshot(
    phase = phase,
    cursor = cursor,
    candidates = candidates,
    results = results.toVector.map { case (f, (r, i)) => CandidateResult(f, r, i) },
    bestFloor = bestFloor,
    exploitTicksRemaining = exploitTicksRemaining,
    windowRevenue = windowRevenue,
    windowImpressions = windowImpressions,
    currentFloor = _currentFloor,
    minFloor = _minFloor,
    ticksThisCandidate = ticksThisCandidate,
    previousResults = previousResults.toVector.map { case (f, (r, i)) => CandidateResult(f, r, i) },
    previousBestFloor = previousBestFloor,
    maxBidObserved = maxBidObserved,
    minBidObserved = minBidObserved,
    windowMaxBid = windowMaxBid
  )

  def restore(s: Snapshot): Unit = {
    phase = s.phase
    cursor = s.cursor
    candidates = s.candidates
    results = s.results.map(cr => cr.floor -> (cr.revenue, cr.impressions)).toMap
    bestFloor = s.bestFloor
    exploitTicksRemaining = s.exploitTicksRemaining
    windowRevenue = s.windowRevenue
    windowImpressions = s.windowImpressions
    _currentFloor = s.currentFloor
    _minFloor = s.minFloor
    ticksThisCandidate = s.ticksThisCandidate
    previousResults = s.previousResults.map(cr => cr.floor -> (cr.revenue, cr.impressions)).toMap
    previousBestFloor = s.previousBestFloor
    maxBidObserved = s.maxBidObserved
    minBidObserved = s.minBidObserved
    windowMaxBid = s.windowMaxBid
  }
}

object FloorSweepOptimizer {

  /**
   * Outcome of a single auction, reported by AuctioneerEntity to
   * SiteEntity and fed to the floor optimizer. The sweep only consumes
   * `maxObservedCpm`/`minObservedCpm` (to clamp its candidate range),
   * but the full shape is preserved so AuctioneerEntity's existing
   * construction site is untouched.
   */
  final case class AuctionOutcome(
      totalBidders: Int,
      rejectedByFloor: Int,
      winnerCpm: Option[Double],
      clearingPrice: Option[Double],
      maxObservedCpm: Double,
      minObservedCpm: Double = 0.0,
      slotId: Option[String] = None
  )

  /**
   * Result returned by `observe`: the next floor to apply, plus — on the
   * Init tick that closes a measurement cycle — that cycle's decision
   * payload. The optimizer never writes the decision itself; SiteEntity
   * journals it after the state persist succeeds, so a crash between
   * the two can't produce a duplicate row (Init just re-derives the
   * same payload on replay).
   */
  final case class ObserveResult(
      newFloor: Double,
      completedCycle: Option[CycleDecision] = None
  )

  /** A completed sweep cycle's outcome, ready for the decision journal. */
  final case class CycleDecision(
      argmaxFloor: Double,
      prevArgmax: Option[Double],
      cycleRevenue: Double,
      cycleImps: Long,
      candidates: Vector[FloorDecisionCandidate]
  )

  /**
   * Phase tag stored as a plain String. Sealed-trait case-objects are
   * known to round-trip as empty maps through Jackson/CBOR on this
   * codebase's CborSerializable contract (see
   * `feedback_jackson_sealed_traits`), so we use strings instead.
   */
  object Phase {
    val Init: String = "init"
    val Sweep: String = "sweep"
    val Exploit: String = "exploit"
  }

  /**
   * @param candidateCount    Number of candidate floors to sweep (evenly
   *                          spaced across `[minFloor, maxFloor]`).
   * @param ticksPerCandidate Observation ticks to hold each candidate
   *                          before advancing. Averaging over multiple
   *                          ticks reduces the single-tick measurement
   *                          noise that otherwise dominates the argmax
   *                          pick. Minimum effective value is 1.
   * @param exploitTicks      Observation ticks to hold the winning floor
   *                          after a sweep completes, before re-sweeping.
   * @param minImpsForArgmax  Minimum impressions the winning candidate
   *                          must accumulate before `pickBest` trusts
   *                          the argmax. Under low traffic a single
   *                          stray impression can otherwise lock the
   *                          floor at a high candidate that admits only
   *                          the top bidder, starving cheaper campaigns
   *                          for an entire exploit window. When unmet,
   *                          `pickBest` falls back to the lowest swept
   *                          floor (the most-filling option).
   * @param tieTolerance      Fractional band (e.g. 0.05 = 5%) within
   *                          which floors are treated as revenue-tied.
   *                          Among well-evidenced, positive-revenue
   *                          candidates whose revenue is ≥ (1 -
   *                          tieTolerance) × best, `pickBest` prefers the
   *                          HIGHER floor. Under second-price clearing a
   *                          wide band of floors is revenue-equivalent
   *                          (many cheap impressions ≈ few expensive
   *                          ones), so the argmax would otherwise
   *                          flip-flop across that plateau on noise;
   *                          preferring the higher floor settles it at
   *                          the same revenue with fewer impressions.
   *                          0.0 disables (exact-tie behavior only).
   */
  final case class Config(
      candidateCount: Int = 8,
      ticksPerCandidate: Int = 4,
      exploitTicks: Int = 60,
      minImpsForArgmax: Int = 3,
      tieTolerance: Double = 0.05
  )

  /**
   * Build a Config from the `promovolve.floor-optimizer` HOCON block.
   * Each key is optional and falls back to the case-class default, so a
   * site running without an explicit floor-optimizer config still gets
   * the tuned duty cycle. Caller passes the sub-config (i.e. the
   * `floor-optimizer` node), not the root.
   *
   * Why the defaults skew heavily toward exploit: the sweep spends
   * `candidateCount × ticksPerCandidate` ticks probing and only
   * `exploitTicks` ticks parked on the winner. With a dominant bidder,
   * every probing tick at a low candidate hands that bidder the cheap
   * second price instead of a binding reserve, so a probe-heavy duty
   * cycle leaks revenue cycle after cycle. Keeping exploit ≫ sweep
   * means the optimizer sits on the revenue-maximizing floor most of
   * the time and only periodically re-measures to track market drift.
   */
  def configFrom(c: com.typesafe.config.Config): Config = {
    val d = Config()
    Config(
      candidateCount = if (c.hasPath("candidate-count")) c.getInt("candidate-count") else d.candidateCount,
      ticksPerCandidate =
        if (c.hasPath("ticks-per-candidate")) c.getInt("ticks-per-candidate") else d.ticksPerCandidate,
      exploitTicks = if (c.hasPath("exploit-ticks")) c.getInt("exploit-ticks") else d.exploitTicks,
      minImpsForArgmax = if (c.hasPath("min-imps-for-argmax")) c.getInt("min-imps-for-argmax") else d.minImpsForArgmax,
      tieTolerance = if (c.hasPath("tie-tolerance")) c.getDouble("tie-tolerance") else d.tieTolerance
    )
  }

  /**
   * Per-candidate measurement in the snapshot. A named case class
   * (rather than an anonymous tuple) so Jackson/CBOR has the field
   * types and won't box `impressions` as `Integer` on the round-trip
   * — that misboxing previously crashed `restore` with a
   * ClassCastException.
   */
  final case class CandidateResult(
      floor: Double,
      revenue: Double,
      impressions: Long
  ) extends CborSerializable

  final case class Snapshot(
      phase: String,
      cursor: Int,
      candidates: Vector[Double],
      results: Vector[CandidateResult],
      bestFloor: Option[Double],
      exploitTicksRemaining: Int,
      windowRevenue: Double,
      windowImpressions: Long,
      currentFloor: Double,
      minFloor: Double,
      // Ticks already spent on the current sweep candidate. Default
      // covers older snapshots written before this field existed —
      // they restore as "0 ticks in" and finish that candidate on the
      // next observation, which is fine.
      ticksThisCandidate: Int = 0,
      // Per-candidate results from the most-recently-completed cycle.
      // Defaulted to empty so older snapshots restore cleanly. The
      // dashboard uses this to show this-cycle-vs-last-cycle deltas.
      previousResults: Vector[CandidateResult] = Vector.empty,
      previousBestFloor: Option[Double] = None,
      // Highest bid observed in the in-progress measurement cycle
      // (resets at each Phase.Init). Used at the next Init as the
      // candidate-search upper bound. 0.0 = no bids seen yet this
      // cycle, so the optimizer collapses to a single candidate at
      // the publisher's minFloor.
      maxBidObserved: Double = 0.0,
      // Lowest bid observed in the in-progress cycle (qualifying or
      // below-floor reject; resets at each Phase.Init). Used at the
      // next Init to raise the candidate-search lower bound. 0.0 =
      // sentinel "none observed"; optimizer falls back to _minFloor.
      minBidObserved: Double = 0.0,
      // Highest bid observed during the current candidate hold window
      // (resets on cursor advance). Ceiling for the mid-sweep
      // contraction; see the field comment in the class. Defaulted so
      // pre-existing snapshots restore cleanly.
      windowMaxBid: Double = 0.0
  ) extends CborSerializable

  /**
   * Inclusive of both endpoints. With N=1, returns just `[minFloor]`.
   * Deduped after cent-rounding: a range narrower than ~N cents would
   * otherwise emit the same floor at several cursor positions and camp
   * `ticksPerCandidate` ticks on each copy — same measurement, repeated.
   * `distinct` keeps first occurrences, so ascending order is preserved.
   */
  private[publisher] def buildCandidates(minFloor: Double, maxFloor: Double, n: Int): Vector[Double] = {
    if (n <= 1 || maxFloor <= minFloor) Vector(minFloor)
    else {
      val step = (maxFloor - minFloor) / (n - 1).toDouble
      (0 until n).iterator.map(i => round2(minFloor + step * i)).toVector.distinct
    }
  }

  /**
   * Pick the floor with the highest accumulated revenue.
   *
   * Tie-breaker rules:
   *   - Near-revenue ties (within `tieTolerance` of the best) → prefer the
   *     HIGHER floor, but only among candidates that have positive revenue
   *     AND clear the `minImpsForArgmax` evidence bar. Under second-price
   *     clearing a wide band of floors is revenue-equivalent (many cheap
   *     impressions net the same as a few expensive ones), so the strict
   *     argmax flip-flops across that plateau on measurement noise.
   *     Promoting to the highest near-tied floor settles it at the same
   *     revenue with fewer impressions, and is more robust if the dominant
   *     bidder's value rises. The evidence + positive-revenue guards stop a
   *     starved high candidate (0 impressions, trivially "within X% of a
   *     tiny best") from being promoted. `tieTolerance = 0.0` reduces this
   *     to the original exact-tie-prefers-higher behavior.
   *   - All-zero results → prefer the LOWEST floor. When every candidate
   *     produced zero revenue, the system has no signal at all. Picking
   *     the highest floor (which the simple maxBy would do) is the
   *     worst possible choice: it guarantees the next exploit phase
   *     also serves zero impressions, which prevents `maxBidObserved`
   *     from updating, which keeps the candidate range at the wrong
   *     ceiling — the optimizer gets stuck. Picking the lowest floor
   *     "fails open": it admits the broadest possible bidder set,
   *     produces impressions, and breaks the deadlock.
   *   - Low-evidence argmax (best candidate's impressions <
   *     `minImpsForArgmax`) → fall back to the LOWEST floor. Under
   *     thin traffic a single stray impression at a high candidate
   *     wins by default and locks out cheaper bidders for the next
   *     exploit window. Demanding a minimum impression count before
   *     trusting the argmax keeps the floor at the most-filling
   *     option until the data is decisive.
   *
   * Empty results: fall back to a safe minimum.
   */
  private[publisher] def pickBest(
      results: Map[Double, (Double, Long)],
      minImpsForArgmax: Int = 1,
      tieTolerance: Double = 0.0
  ): Double = {
    if (results.isEmpty) 0.50
    else {
      val totalRev = results.values.iterator.map(_._1).sum
      if (totalRev <= 0.0) results.keys.min
      else {
        val (bestFloor, (bestRev, bestImps)) =
          results.toSeq.maxBy { case (floor, (rev, _)) => (rev, floor) }
        if (bestImps < minImpsForArgmax.toLong) results.keys.min
        // Thin-traffic guard: never RAISE above a completely untested
        // (zero-impression) lower floor. On a near-idle site most candidates
        // get 0 impressions while a couple of high floors catch a few stray
        // ones — the strict argmax then "rises" on pure timing noise (e.g.
        // $8.41 chosen off 3 impressions while $5–$7 were never measured),
        // which locks out cheaper bidders. A 0-impression lower floor is NOT
        // evidence that the higher floor earns more; we only trust an upward
        // move once every lower candidate has actually been measured. Raising
        // is risky (excludes demand), lowering is safe — so we fail open to
        // the lowest floor. A lower floor with ≥1 impression counts as
        // measured (preserves the existing thin-but-real argmax behavior).
        else if (results.exists { case (floor, (_, imps)) => floor < bestFloor && imps == 0L })
          results.keys.min
        else if (tieTolerance > 0.0) {
          // Promote to the highest floor whose revenue is within
          // tieTolerance of the best, among candidates that have real
          // evidence and positive revenue. bestFloor itself always
          // qualifies, so `tied` is non-empty and `tied.max >= bestFloor`.
          val threshold = bestRev * (1.0 - tieTolerance)
          val tied = results.collect {
            case (floor, (rev, imps))
                if rev > 0.0 && imps >= minImpsForArgmax.toLong && rev >= threshold =>
              floor
          }
          if (tied.nonEmpty) tied.max else bestFloor
        } else bestFloor
      }
    }
  }

  private def round2(d: Double): Double =
    math.round(d * 100.0) / 100.0

  /**
   * Bid-derived floor — the monopoly shortcut. With exactly ONE bidder in a
   * category, the revenue-optimal reserve is its bid: it still clears (bid ≥
   * floor) and pays its full value, and there's no price-vs-fill tradeoff to
   * sweep for. Computed directly from the observed bid (bounded below by the
   * publisher minimum), so it needs no traffic, never drifts on noise, and
   * follows a bid change instantly. Returns None for a competitive category
   * (≥2 bidders) or when nothing has been observed — the caller should sweep
   * instead.
   */
  def bidDerivedFloor(bidderCount: Int, bid: Double, minFloor: Double): Option[Double] =
    if (bidderCount == 1 && bid > 0.0) Some(math.max(bid, minFloor)) else None
}
