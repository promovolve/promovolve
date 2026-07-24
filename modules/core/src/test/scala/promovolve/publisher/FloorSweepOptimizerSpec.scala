package promovolve.publisher

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import promovolve.SiteId

class FloorSweepOptimizerSpec extends AnyWordSpec with Matchers {

  private val siteId = SiteId("test-site")

  "FloorSweepOptimizer.buildCandidates" should {
    "produce N evenly spaced floors from min to max inclusive" in {
      val xs = FloorSweepOptimizer.buildCandidates(0.10, 10.0, 10)
      xs.size shouldBe 10
      xs.head shouldBe 0.10 +- 0.01
      xs.last shouldBe 10.0 +- 0.01
    }

    "degenerate to a single value when n<=1 or max<=min" in {
      FloorSweepOptimizer.buildCandidates(1.0, 1.0, 5) shouldBe Vector(1.0)
      FloorSweepOptimizer.buildCandidates(1.0, 5.0, 1) shouldBe Vector(1.0)
    }

    "dedupe cent-rounding collisions on a narrow range, preserving order and endpoints" in {
      // 8 raw points across [$0.10, $0.12] all round to 0.10/0.11/0.12 —
      // without dedup the sweep would camp ticksPerCandidate ticks on
      // each copy, re-measuring the same floor.
      val xs = FloorSweepOptimizer.buildCandidates(0.10, 0.12, 8)
      xs shouldBe Vector(0.10, 0.11, 0.12)
      xs shouldBe xs.distinct
      xs shouldBe xs.sorted
    }
  }

  "FloorSweepOptimizer.pickBest" should {
    "return argmax of revenue across candidates" in {
      val results = Map(
        0.50 -> (1.0, 100L),
        1.00 -> (3.0, 100L), // winner
        2.00 -> (2.0, 100L)
      )
      FloorSweepOptimizer.pickBest(results) shouldBe 1.00
    }

    "break ties toward higher floor" in {
      val results = Map(
        0.50 -> (2.0, 100L),
        1.00 -> (2.0, 100L),
        2.00 -> (2.0, 100L)
      )
      FloorSweepOptimizer.pickBest(results) shouldBe 2.00
    }

    "fall back to lowest floor when the best candidate's impressions are below the evidence threshold" in {
      // Scenario: a single stray impression at the highest candidate
      // makes it the argmax by default, but it's not a meaningful
      // signal. The evidence guard should reject it and pick the
      // lowest floor (the most-filling option).
      val results = Map(
        1.17 -> (0.0, 0L),
        1.43 -> (0.0014, 1L), // would be argmax without guard
        1.50 -> (0.0, 0L)
      )
      FloorSweepOptimizer.pickBest(results, minImpsForArgmax = 3) shouldBe 1.17
    }

    "trust the argmax once impressions clear the evidence threshold" in {
      val results = Map(
        1.17 -> (0.0024, 2L),
        1.43 -> (0.0070, 5L), // clears threshold and is argmax
        1.50 -> (0.0030, 2L)
      )
      FloorSweepOptimizer.pickBest(results, minImpsForArgmax = 3) shouldBe 1.43
    }

    "promote to the higher floor across a revenue-tied plateau when tieTolerance is set" in {
      // Mirrors the second-price plateau seen live: a low floor (many cheap
      // impressions) and a high floor (few expensive ones) earn nearly the
      // same revenue. Strict argmax is the LOW floor here; with a 5% band
      // the high floor is within tolerance and should win.
      val results = Map(
        1.17 -> (0.385, 114L), // strict argmax
        4.00 -> (0.300, 80L), // outside the band
        7.00 -> (0.375, 55L) // within 5% of 0.385 → promoted
      )
      FloorSweepOptimizer.pickBest(results, minImpsForArgmax = 3, tieTolerance = 0.05) shouldBe 7.00
      // Without the band, the strict argmax (low floor) wins.
      FloorSweepOptimizer.pickBest(results, minImpsForArgmax = 3, tieTolerance = 0.0) shouldBe 1.17
    }

    "stay at the revenue peak and NOT promote a higher floor that earns materially less" in {
      // When revenue genuinely peaks mid-range and declines toward the top
      // (high floor earns ~half), the high floor is outside the band and
      // must not be chosen — the plateau logic only promotes near-ties.
      val results = Map(
        4.00 -> (0.467, 96L),
        5.67 -> (0.661, 109L), // peak
        7.00 -> (0.321, 46L) // ~half of peak → outside band
      )
      FloorSweepOptimizer.pickBest(results, minImpsForArgmax = 3, tieTolerance = 0.05) shouldBe 5.67
    }

    "not promote a near-tied high floor that lacks evidence (stray impression)" in {
      // A high floor whose revenue is within the band but rests on a single
      // lucky impression must NOT be promoted — the evidence guard keeps it
      // from starving the well-measured low floor.
      val results = Map(
        1.00 -> (0.50, 100L), // strict argmax, well-evidenced
        5.00 -> (0.49, 1L) // within band but only 1 imp (< minImps)
      )
      FloorSweepOptimizer.pickBest(results, minImpsForArgmax = 3, tieTolerance = 0.05) shouldBe 1.00
    }

    "NOT raise the floor on thin-traffic noise when lower candidates were never measured" in {
      // The live $8.41 failure: a near-idle site, most candidates got 0
      // impressions, a couple of high floors caught a few stray ones. The
      // strict argmax is the high floor, but the lower floors were never
      // tested — so concluding "high earns more" is noise. Must hold at the
      // lowest floor (fail open, admit the cheap bidders) instead of drifting
      // up and locking them out.
      val results = Map(
        5.00 -> (0.0, 0L),
        5.57 -> (0.0, 0L),
        6.71 -> (0.0, 0L),
        7.27 -> (0.018, 2L),
        8.41 -> (0.027, 3L) // raw argmax, but every floor below it is untested
      )
      FloorSweepOptimizer.pickBest(results, minImpsForArgmax = 3) shouldBe 5.00
    }

    "still raise to the argmax when every lower candidate WAS measured (real signal)" in {
      // Counterpart: with genuine traffic across the range, a higher floor
      // that truly earns more is trusted — the guard only blocks raises over
      // UNMEASURED low floors, not well-evidenced ones.
      val results = Map(
        5.00 -> (10.0, 100L),
        6.00 -> (12.0, 100L),
        7.00 -> (15.0, 100L) // argmax, all lower floors well-measured
      )
      FloorSweepOptimizer.pickBest(results, minImpsForArgmax = 3) shouldBe 7.00
    }
  }

  "FloorSweepOptimizer.bidDerivedFloor (monopoly shortcut)" should {
    "set the floor just under the single bidder's bid so it always clears" in {
      // ×0.99 headroom: admission rejects on maxCpm < floor, and floor == bid
      // is a Double knife-edge (both values travel different arithmetic
      // paths). A monopolist must never reject its own floor — that dead-end
      // had no recovery path before the zero-servable collapse (2026-07-24).
      FloorSweepOptimizer.bidDerivedFloor(bidderCount = 1, bid = 5.0, minFloor = 0.10) shouldBe Some(4.95)
    }
    "leave the monopolist clearing at the live outage numbers (bid $10.00)" in {
      val floor = FloorSweepOptimizer.bidDerivedFloor(bidderCount = 1, bid = 10.0, minFloor = 0.10).get
      floor should be < 10.0 // maxCpm < floor must be false → clears
      floor shouldBe 9.90 +- 1e-9
    }
    "raise the monopoly floor to the publisher minimum when the bid is below it" in {
      FloorSweepOptimizer.bidDerivedFloor(bidderCount = 1, bid = 0.05, minFloor = 0.10) shouldBe Some(0.10)
    }
    "return None for a competitive category (caller should sweep)" in {
      FloorSweepOptimizer.bidDerivedFloor(bidderCount = 2, bid = 5.0, minFloor = 0.10) shouldBe None
      FloorSweepOptimizer.bidDerivedFloor(bidderCount = 3, bid = 7.0, minFloor = 0.10) shouldBe None
    }
    "return None when nothing has been observed" in {
      FloorSweepOptimizer.bidDerivedFloor(bidderCount = 1, bid = 0.0, minFloor = 0.10) shouldBe None
      FloorSweepOptimizer.bidDerivedFloor(bidderCount = 0, bid = 0.0, minFloor = 0.10) shouldBe None
    }
  }

  "FloorSweepOptimizer.competitiveRangeTop (second-bid ceiling)" should {
    def outcome(bidders: Int, max: Double, second: Double) =
      FloorSweepOptimizer.AuctionOutcome(
        totalBidders = bidders,
        rejectedByFloor = 0,
        winnerCpm = None,
        clearingPrice = None,
        maxObservedCpm = max,
        secondMaxObservedCpm = second
      )

    "cap the sweep range at secondBid × 0.99 in a competitive category" in {
      // Live outage shape: bids $12 / $10 / $10. Probing floors in
      // (10, 12] manufactures a monopoly — only the $12 bidder clears —
      // so the range top must be 9.90, keeping all three admissible.
      FloorSweepOptimizer.competitiveRangeTop(outcome(3, 12.0, 10.0)) shouldBe 9.90 +- 1e-9
    }
    "let a floor price out lowball bids but never the top two" in {
      // Bids $12 / $0.50: the ceiling drops to $0.495 — the reserve gives
      // up extraction above the second bid (that's second-price
      // clearing's job), in exchange every approved bidder stays live.
      FloorSweepOptimizer.competitiveRangeTop(outcome(2, 12.0, 0.5)) shouldBe 0.495 +- 1e-9
    }
    "leave a monopoly outcome untouched (bid-derived path owns it)" in {
      FloorSweepOptimizer.competitiveRangeTop(outcome(1, 12.0, 0.0)) shouldBe 12.0
    }
    "leave the range untouched when the second bid is unknown" in {
      // 0.0 = unknown (mixed-version rolling deploy, or reject-only field
      // whose middle is invisible). Fail open to the old max-bid bound.
      FloorSweepOptimizer.competitiveRangeTop(outcome(3, 12.0, 0.0)) shouldBe 12.0
    }
    "never raise the range when the second bid exceeds the max (defensive)" in {
      FloorSweepOptimizer.competitiveRangeTop(outcome(2, 10.0, 20.0)) shouldBe 10.0
    }
  }

  "FloorSweepOptimizer sweep lifecycle" should {

    "emit each candidate once during sweep then converge to argmax" in {
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 3, ticksPerCandidate = 1, exploitTicks = 2, minImpsForArgmax = 1)
      )
      opt.setMinFloor(1.0)
      opt.setInitialFloor(1.0)
      // Pre-seed an observation so Phase.Init builds a real 3-candidate
      // range. Without this, cold start collapses to a single candidate
      // at _minFloor.
      opt.recordObservedBid(5.0)

      // Tick 1 (Init → Sweep): emits candidate 0 (floor=1.0)
      val r1 = opt.observe()
      r1.map(_.newFloor) shouldBe Some(1.0)
      opt.recordServedImpression(0.10) // low revenue at low floor

      // Tick 2 (Sweep): emits candidate 1 (floor=3.0)
      val r2 = opt.observe()
      r2.map(_.newFloor) shouldBe Some(3.0)
      opt.recordServedImpression(0.50) // best revenue
      opt.recordServedImpression(0.50)

      // Tick 3 (Sweep): emits candidate 2 (floor=5.0)
      val r3 = opt.observe()
      r3.map(_.newFloor) shouldBe Some(5.0)
      opt.recordServedImpression(0.20)

      // Tick 4 (Sweep last → Exploit): picks argmax=3.0
      val r4 = opt.observe()
      r4.map(_.newFloor) shouldBe Some(3.0)
      opt.currentFloorCpm shouldBe 3.0

      // Tick 5 (Exploit): holds 3.0, no change emitted
      val r5 = opt.observe()
      r5 shouldBe None
      opt.currentFloorCpm shouldBe 3.0

      // Tick 6 (Exploit countdown → Init transition): None this tick
      val r6 = opt.observe()
      r6 shouldBe None

      // Tick 7 (Init → Sweep again): emits candidate 0
      val r7 = opt.observe()
      r7.map(_.newFloor) shouldBe Some(1.0)
    }

    "respect minFloor at sweep start" in {
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 4, ticksPerCandidate = 1, exploitTicks = 1)
      )
      opt.setMinFloor(2.0)
      val first = opt.observe().map(_.newFloor)
      first shouldBe Some(2.0)
    }

    "never exploit at a floor below the current minFloor, even if minFloor was raised mid-sweep" in {
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 4, ticksPerCandidate = 1, exploitTicks = 2)
      )
      opt.setMinFloor(1.0)
      opt.setInitialFloor(1.0)
      // Seed so cycle 1 builds the full 4-candidate range.
      opt.recordObservedBid(10.0)

      // Sweep across candidates 1, 4, 7, 10. Give the lowest candidate
      // the highest revenue so it'd "win" without clamping.
      opt.observe(); opt.recordServedImpression(10.0) // floor 1.0
      opt.observe(); opt.recordServedImpression(1.0) // floor 4.0
      opt.observe(); opt.recordServedImpression(1.0) // floor 7.0
      opt.observe(); opt.recordServedImpression(1.0) // floor 10.0

      // Publisher raises minFloor mid-sweep, before the final transition.
      opt.setMinFloor(5.0)

      // Final sweep tick → Exploit. With clamping, we should NOT settle
      // at $1 even though it had the highest measured revenue.
      val winner = opt.observe().map(_.newFloor)
      winner.exists(_ >= 5.0) shouldBe true
      opt.currentFloorCpm should be >= 5.0
    }

    "setMinFloor immediately clamps current floor when it's below the new min" in {
      val opt = new FloorSweepOptimizer(siteId)
      opt.setInitialFloor(2.0)
      opt.setMinFloor(5.0)
      opt.currentFloorCpm shouldBe 5.0
    }

    "resetToMinFloor collapses to min, clears the stale bid anchor, and restarts a clean sweep" in {
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 3, ticksPerCandidate = 1, exploitTicks = 2, minImpsForArgmax = 1)
      )
      opt.setMinFloor(1.0)
      // Drive the optimizer to a high, $50-anchored state (the flagged
      // monopoly bidder): a $50 anchor pins the floor and the range.
      opt.setInitialFloor(50.0)
      opt.recordObservedBid(50.0)
      opt.currentFloorCpm shouldBe 50.0
      opt.maxBidObservedCpm shouldBe 50.0

      // Sole bidder flagged → demand goes to zero → collapse.
      opt.resetToMinFloor()
      opt.currentFloorCpm shouldBe 1.0
      opt.maxBidObservedCpm shouldBe 0.0
      opt.minBidObservedCpm shouldBe 0.0

      // With the anchor wiped and no fresh bids, the next cycle starts a
      // clean sweep from the minimum (cold-start collapses to _minFloor),
      // NOT the stale $50-biased range.
      opt.observe().map(_.newFloor) shouldBe Some(1.0)
    }

    "hold each candidate for ticksPerCandidate ticks and average measurements" in {
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 2, ticksPerCandidate = 3, exploitTicks = 1)
      )
      opt.setMinFloor(1.0)
      opt.setInitialFloor(1.0)
      // Seed so cycle 1 builds the 2-candidate range [$1, $2].
      opt.recordObservedBid(2.0)

      // Init → Sweep: candidate 0 ($1) emitted on tick 0
      opt.observe().map(_.newFloor) shouldBe Some(1.0)

      // Ticks 1 + 2 on candidate 0: revenue accumulates, no advance
      opt.recordServedImpression(0.10)
      opt.observe() shouldBe None
      opt.currentFloorCpm shouldBe 1.0

      opt.recordServedImpression(0.20)
      opt.observe() shouldBe None
      opt.currentFloorCpm shouldBe 1.0

      // Tick 3: third record, then ticksPerCandidate met → advance to candidate 1 ($2)
      opt.recordServedImpression(0.30)
      opt.observe().map(_.newFloor) shouldBe Some(2.0)

      // 3 ticks of low revenue on $2 candidate
      opt.recordServedImpression(0.05); opt.observe() shouldBe None
      opt.recordServedImpression(0.05); opt.observe() shouldBe None
      opt.recordServedImpression(0.05)
      // Final tick on last candidate → Exploit. With (.60 vs .15) argmax = $1.
      opt.observe().map(_.newFloor) shouldBe Some(1.0)
    }
  }

  "FloorSweepOptimizer decision payload" should {

    // Drives one full 3-candidate cycle: Init, 3 sweep ticks, 1 exploit
    // tick — leaving the optimizer one observe() away from the
    // cycle-closing Init. Revenue: $0.10 @ $1, $1.00 (2 imps) @ $3,
    // $0.20 @ $5 → argmax $3.
    def runOneCycle(opt: FloorSweepOptimizer): Unit = {
      opt.setMinFloor(1.0)
      opt.setInitialFloor(1.0)
      opt.recordObservedBid(5.0)
      opt.observe(); opt.recordServedImpression(0.10) // $1
      opt.observe(); opt.recordServedImpression(0.50); opt.recordServedImpression(0.50) // $3
      opt.observe(); opt.recordServedImpression(0.20) // $5
      opt.observe() // sweep complete → Exploit at argmax $3
      opt.observe() shouldBe None // exploit tick → phase flips to Init
    }

    def cycleConfig = FloorSweepOptimizer.Config(
      candidateCount = 3, ticksPerCandidate = 1, exploitTicks = 1, minImpsForArgmax = 1)

    "be absent on the first Init (no completed cycle yet)" in {
      val opt = new FloorSweepOptimizer(siteId, cycleConfig)
      opt.setMinFloor(1.0)
      opt.recordObservedBid(5.0)
      opt.observe().flatMap(_.completedCycle) shouldBe None
    }

    "carry the completed cycle's argmax, totals, and floor-sorted candidates on the cycle-closing Init" in {
      val opt = new FloorSweepOptimizer(siteId, cycleConfig)
      runOneCycle(opt)

      val init2 = opt.observe()
      init2.isDefined shouldBe true
      val decision = init2.flatMap(_.completedCycle).getOrElse(fail("expected completedCycle"))
      decision.argmaxFloor shouldBe 3.0
      decision.prevArgmax shouldBe None
      decision.cycleRevenue shouldBe 1.30 +- 1e-9
      decision.cycleImps shouldBe 4L
      decision.candidates.map(_.floor) shouldBe Vector(1.0, 3.0, 5.0)
      decision.candidates.map(_.impressions) shouldBe Vector(1L, 2L, 1L)
    }

    "re-derive the same payload when Init replays after a crash (snapshot taken pre-Init)" in {
      // The decision is journaled by SiteEntity AFTER the state persist.
      // If the process dies between deriving the payload and persisting,
      // recovery restores the pre-Init snapshot (phase=Init, results
      // intact) and the next observe() must produce the identical
      // payload — that re-derivation is what makes the post-persist
      // write crash-safe without deduplication.
      val opt = new FloorSweepOptimizer(siteId, cycleConfig)
      runOneCycle(opt)
      val preInit = opt.snapshot()

      val original = opt.observe().flatMap(_.completedCycle)
      val replayed = {
        val fresh = new FloorSweepOptimizer(siteId, cycleConfig)
        fresh.restore(preInit)
        fresh.observe().flatMap(_.completedCycle)
      }
      original.isDefined shouldBe true
      replayed shouldBe original
    }
  }

  "FloorSweepOptimizer cycle ceiling" should {

    "reset max-bid ceiling each cycle so a vanished high bidder doesn't permanently inflate the sweep range" in {
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 3, ticksPerCandidate = 1, exploitTicks = 1)
      )
      opt.setMinFloor(0.10)

      // Seed an $8 observation so cycle 1's Init builds a real wide range.
      opt.recordObservedBid(8.0)
      opt.observe() // Init → Sweep with candidates [0.10, 4.05, 8.00]
      val c1 = opt.snapshot().candidates
      c1.size shouldBe 3
      c1.last shouldBe 8.0 +- 0.05

      // During cycle 1 only $2 bids arrive — the $8 bidder has vanished.
      // Mid-sweep contraction skips $4.05 and $8 immediately.
      opt.recordObservedBid(2.0); opt.observe() // → Exploit
      opt.observe() // Exploit → Init

      // Cycle 2 Init must rebuild the ceiling at $2, not stay at $8.
      opt.observe() // Init → Sweep, candidates rebuilt from cycle 1's $2
      val c2 = opt.snapshot().candidates
      c2.size shouldBe 3
      c2.last shouldBe 2.0 +- 0.05
    }

    "raise the sweep floor to the lowest bid observed in the prior cycle" in {
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 3, ticksPerCandidate = 1, exploitTicks = 1)
      )
      opt.setMinFloor(0.10)

      // Cycle 1: bids range $4..$8.
      opt.observe()
      opt.recordObservedMinBid(4.0); opt.recordObservedBid(8.0); opt.observe()
      opt.recordObservedMinBid(4.0); opt.recordObservedBid(8.0); opt.observe()
      opt.recordObservedMinBid(4.0); opt.recordObservedBid(8.0); opt.observe()
      opt.recordObservedMinBid(4.0); opt.recordObservedBid(8.0); opt.observe()

      // Cycle 2 Init builds candidates in [max($0.10, $4), $8] = [$4, $8].
      opt.observe()
      val c2 = opt.snapshot().candidates
      c2.size shouldBe 3
      c2.head shouldBe 4.0 +- 0.05
      c2.last shouldBe 8.0 +- 0.05
    }

    "include below-floor rejects in the next cycle's lower bound so excluded campaigns can re-enter" in {
      // Scenario: floor settles high enough that only the top bidder
      // ($1.50) qualifies; cheaper campaigns ($1.20, $1.17) are
      // rejected. Without below-floor rejects in `minBidObserved`, the
      // next cycle's range would collapse to [$1.50, $1.50] and the
      // cheaper campaigns would be permanently excluded.
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 3, ticksPerCandidate = 1, exploitTicks = 1)
      )
      opt.setMinFloor(0.10)

      // Cycle 1 emits a single qualifying bid at $1.50 and a below-floor
      // reject at $1.17. Production callsite is AuctioneerEntity, which
      // already folds rejects into AuctionOutcome.minObservedCpm.
      opt.observe()
      opt.recordObservedBid(1.50); opt.recordObservedMinBid(1.17); opt.observe()
      opt.recordObservedBid(1.50); opt.recordObservedMinBid(1.17); opt.observe()
      opt.recordObservedBid(1.50); opt.recordObservedMinBid(1.17); opt.observe()
      opt.recordObservedBid(1.50); opt.recordObservedMinBid(1.17); opt.observe()

      // Cycle 2 Init: range should span [$1.17, $1.50], not collapse to $1.50.
      opt.observe()
      val c2 = opt.snapshot().candidates
      c2.head shouldBe 1.17 +- 0.05
      c2.last shouldBe 1.50 +- 0.05
      c2.size shouldBe 3
    }

    "fall back to _minFloor when no bids were observed in the prior cycle" in {
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 3, ticksPerCandidate = 1, exploitTicks = 1)
      )
      opt.setMinFloor(0.25)

      // Cycle 1: max bid signal arrives via rejects, but no qualifying min.
      opt.observe()
      opt.recordObservedBid(6.0); opt.observe()
      opt.recordObservedBid(6.0); opt.observe()
      opt.recordObservedBid(6.0); opt.observe()
      opt.recordObservedBid(6.0); opt.observe()

      // Cycle 2 floor falls back to _minFloor, ceiling clamps at $6.
      opt.observe()
      val c2 = opt.snapshot().candidates
      c2.head shouldBe 0.25 +- 0.05
      c2.last shouldBe 6.0 +- 0.05
    }

    "fast-forward past candidates above maxBidObserved mid-sweep" in {
      // Scenario: cycle 1 ended with maxBidObserved=$10 (market had a
      // high bidder), so cycle 2's Init builds a wide range
      // [_minFloor, $10]. But during cycle 2 the only bidder left is
      // at $1. Mid-sweep contraction should skip candidates above $1
      // once that bid is observed, instead of camping on $2..$10 for
      // ticksPerCandidate ticks each.
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 5, ticksPerCandidate = 1, exploitTicks = 1)
      )
      opt.setMinFloor(0.10)

      // Seed prior-cycle observation so Phase.Init builds a real range
      // instead of cold-start single-candidate.
      opt.recordObservedBid(10.0)
      opt.observe() // Init → Sweep, range [0.10..10.00] with 5 candidates
      val snap0 = opt.snapshot()
      snap0.cursor shouldBe 0
      snap0.candidates.size shouldBe 5

      // At the low candidate, only the $1 bidder shows up — record
      // and advance.
      opt.recordObservedBid(1.0)
      opt.observe() // cursor wants index 1 ($2.58), but $2.58 > $1 so fast-forward past all
      val snap1 = opt.snapshot()
      snap1.phase shouldBe FloorSweepOptimizer.Phase.Exploit
      opt.currentFloorCpm shouldBe 0.10 +- 0.05 // best (and only) measured floor
    }

    "contract on the current window's max when a bid is LOWERED mid-cycle" in {
      // Incident 2026-07-20 (Cooking/216 on publisher-programmer-llc): a
      // cycle Init'd while one campaign bid $12 and another $10, building
      // the grid [10.00..12.00]. The $12 bid was observed once early in
      // the new cycle, then the advertiser cut it to $10. The cycle-long
      // maxBidObserved is a monotone high-water mark, so it stayed 12 and
      // the mid-sweep contraction never fired — the sweep camped a full
      // hold window on each of $10.29..$12.00, all guaranteed no-fill.
      // The contraction must key off the max observed in the most recent
      // hold window instead.
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 8, ticksPerCandidate = 1, exploitTicks = 1)
      )
      opt.setMinFloor(0.10)

      // Prior cycle: bids span $10..$12.
      opt.recordObservedBid(12.0)
      opt.recordObservedMinBid(10.0)
      opt.observe() // Init → Sweep, grid [10.00..12.00], 8 candidates
      val snap0 = opt.snapshot()
      snap0.candidates.head shouldBe 10.0 +- 0.01
      snap0.candidates.last shouldBe 12.0 +- 0.01

      // Candidate 1 window: the $12 bidder is still live — cycle max
      // pins at 12, no contraction on advance.
      opt.recordObservedBid(12.0); opt.recordObservedMinBid(10.0)
      opt.observe() // advance to candidate 2 ($10.29)
      opt.snapshot().cursor shouldBe 1

      // Advertiser cuts the bid to $10. Candidate 2 window observes only
      // $10 bids; the next advance must skip every remaining candidate
      // above $10 — despite the cycle-long max still being 12 — which
      // ends the sweep.
      opt.recordObservedBid(10.0); opt.recordObservedMinBid(10.0)
      opt.observe()
      val snap1 = opt.snapshot()
      snap1.phase shouldBe FloorSweepOptimizer.Phase.Exploit
      // All measured candidates had zero revenue → argmax prefers the
      // lowest, which is also the only floor the $10 market can fill.
      opt.currentFloorCpm shouldBe 10.0 +- 0.01
    }

    "collapse cold-start to a single candidate at _minFloor when no bids have been observed" in {
      // Previously the optimizer fell back to a static max ceiling when
      // it had no observations, blindly sweeping up to it. That produced
      // the "$10 floor before any bid arrives" deadlock
      // where every candidate above the bidders' max kept the floor
      // too high and starved traffic. The new behavior is to collapse
      // to a single candidate at _minFloor — admits the widest bidder
      // set, gathers a real maxBidObserved signal, and lets the next
      // cycle build a sweep from real data.
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 3, ticksPerCandidate = 1, exploitTicks = 1)
      )
      opt.setMinFloor(0.10)

      opt.observe() // Init → Sweep
      val candidates = opt.snapshot().candidates
      candidates shouldBe Vector(0.10)
    }

    "escape a single-price market instead of boxing at the sole bid" in {
      // Incident 2026-07-06: two campaigns both bid $5.00, so min and max
      // observed bids were both 5.0 and the candidate range collapsed to
      // ONE candidate sitting exactly on the bid ("probing candidate 1/1
      // at $5.00" on the dashboard, forever — each cycle's rejected/served
      // $5 bids re-reported the same bounds and re-boxed the next cycle).
      // The escape widens one step below the sole price so the sweep can
      // compare at-the-bid vs under-the-bid revenue.
      val opt = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 4, ticksPerCandidate = 1, exploitTicks = 1)
      )
      opt.setMinFloor(0.10)
      // A market where every bid is the same price.
      opt.recordObservedBid(5.0)
      opt.recordObservedMinBid(5.0)

      opt.observe() // Init: builds candidates from the degenerate (5.0, 5.0)
      val candidates = opt.snapshot().candidates
      candidates.max shouldBe 5.0
      candidates.min should be < 5.0 // never a lone candidate AT the bid
      candidates.size should be > 1
    }
  }

  "FloorSweepOptimizer snapshot roundtrip" should {
    "preserve mid-sweep state" in {
      val cfg = FloorSweepOptimizer.Config(candidateCount = 5, ticksPerCandidate = 1, exploitTicks = 3)
      val a = new FloorSweepOptimizer(siteId, cfg)
      a.setMinFloor(1.0)
      a.observe() // candidate 0
      a.recordServedImpression(0.10)
      a.observe() // candidate 1
      a.recordServedImpression(0.30)

      val snap = a.snapshot()

      // Same config on b — restoring a snapshot only makes sense against
      // the same Config (snapshot persists state, not configuration).
      val b = new FloorSweepOptimizer(siteId, cfg)
      b.setMinFloor(1.0)
      b.restore(snap)

      b.currentFloorCpm shouldBe a.currentFloorCpm
      b.epsilon shouldBe a.epsilon
      // Continuing from the restored snapshot should follow the same path
      val nextA = a.observe().map(_.newFloor)
      val nextB = b.observe().map(_.newFloor)
      nextA shouldBe nextB
    }

    "carry impressions as Long in CandidateResult snapshot" in {
      // Regression note: the original snapshot used
      //   Vector[(Double, Double, Long)]
      // which CBOR-deserialized with the third element boxed as Integer
      // (Jackson doesn't have field-type info for anonymous tuple positions),
      // crashing restore() with ClassCastException on BoxesRunTime.unboxToLong.
      // Using a named case class with declared field types fixes that.
      // This test pins the new shape — a true CBOR round-trip would need an
      // integration test against Pekko's durable_state serializer.
      val a = new FloorSweepOptimizer(
        siteId,
        FloorSweepOptimizer.Config(candidateCount = 3, ticksPerCandidate = 1, exploitTicks = 1)
      )
      a.setMinFloor(1.0)
      a.observe()
      (1 to 10).foreach(_ => a.recordServedImpression(0.001))
      a.observe()

      val snap = a.snapshot()
      snap.results should not be empty
      snap.results.head.impressions shouldBe 10L
    }
  }
}
