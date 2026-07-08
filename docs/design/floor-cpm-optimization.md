# Floor CPM Optimization — Direct Sweep

> Replaces the earlier DQN/RL floor agent design (removed). The historical
> rationale: floor discovery is a closed-form measurement problem, not a
> sequential decision problem — changing `SiteState.floorCpm` already triggers a
> re-auction in `AuctioneerEntity` (`scheduleReauction` on `UpdateFloorCpm`),
> which re-collects fresh bids at the new threshold, so each candidate floor
> naturally gets its own real revenue measurement. No Q-function needed.

## What it does

`FloorSweepOptimizer` (`modules/core/src/main/scala/promovolve/publisher/FloorSweepOptimizer.scala`)
is the **sole publisher-side floor mechanism**. Per site, it cycles through three
phases:

1. **Init** — build `candidateCount` candidate floors, evenly spaced (inclusive)
   across `[minFloor, maxFloor]`, where the bounds come from the bids actually
   observed in the *previous* cycle (see below).
2. **Sweep** — hold each candidate for `ticksPerCandidate` observation windows,
   accumulating realized revenue and impressions per candidate. Multi-tick
   holds average out single-tick noise.
3. **Exploit** — pick the winner and park on it for `exploitTicks` windows, then
   re-enter Init to track market drift.

## Winner selection (argmax with a plateau tie-break)

The winner is the candidate with the highest accumulated revenue, with one
deliberate twist: under second-price clearing, a wide band of floors is often
revenue-equivalent (many cheap impressions net the same as a few expensive
ones), and a strict argmax flip-flops across that plateau on noise. So
near-ties — candidates within `tieTolerance` of the best — are settled by
preferring the **higher** floor, but only among candidates with positive
revenue that clear the `minImpsForArgmax` evidence bar (so a starved
zero-impression candidate can't win "trivially within tolerance").

## Candidate bounds from observed bids

Both bounds are **per-cycle** (reset at each Init) so one odd cycle can't pin
the range at a stale value:

- **Upper bound = `maxBidObserved`** — there's no point probing floors above
  the highest bid seen; they're mechanically guaranteed zero revenue. AdServer
  reports the *pre-floor* max bid (`CategoryBidResponse.maxRejectedCpm`), so
  the cap stays accurate even while probing above the actual bid ceiling.
- **Lower bound** spans qualifying bids *and* below-floor rejects
  (`min(qualifyingMin, minRejectedCpm)`), so campaigns currently priced out by
  the floor still pull the next cycle's lower bound down — without this, a
  settled-high floor would permanently exclude cheaper bidders from future
  cycles.

If no bids were observed at all, the sweep collapses to `minFloor`.

## Configuration

HOCON block `promovolve.floor-optimizer` (read once by `SiteEntity`; every key
optional, falling back to the case-class defaults):

| Key | Default | Meaning |
|---|---|---|
| `candidate-count` | 8 | floors per sweep |
| `ticks-per-candidate` | 4 | observation windows held per candidate |
| `exploit-ticks` | 60 | windows parked on the winner before re-sweeping |
| `min-imps-for-argmax` | 3 | evidence bar for a candidate to win |
| `tie-tolerance` | 0.05 | revenue fraction considered a near-tie |

The defaults deliberately skew toward exploit: every probing tick at a low
candidate hands a dominant bidder the cheap second price instead of a binding
reserve, so a probe-heavy duty cycle leaks revenue cycle after cycle.

## Persistence

The optimizer state (phase, cursor, per-candidate `CandidateResult`s, best
floor, per-cycle bid bounds) is snapshotted (`Snapshot`, CborSerializable) so
the sweep survives restarts. The previous cycle's results are kept so the
dashboard can show this-cycle-vs-last-cycle deltas.

## Interaction with the rest of the system

- The publisher's `minFloorCpm` remains the hard lower bound (set in the Sites
  UI); the optimizer explores above it.
- Floors only matter when bidder values are spread out — in a homogeneous
  market (all bids equal), every floor below the common bid is
  revenue-equivalent and the optimizer correctly settles anywhere on that
  plateau.
- Per-slot floor overrides (crawler prior + admin escape hatch) layer on top of
  the site-level optimized floor.
