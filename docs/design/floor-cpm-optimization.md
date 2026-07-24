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

## Per-category floors (the floors that price real demand)

Each category with observed demand gets its own floor, decided per
observation window from the latest per-category auction report
(`bidderCount`, `rejectedByFloor`, `maxObservedCpm`):

- **Zero servable demand** — nobody bids anymore, **or bidders exist but
  every one was rejected below the floor** — collapses the floor to the
  publisher minimum immediately and resets the optimizer. Demand that
  cannot serve is treated as no demand: a floor can never freeze in a
  state that prices out its whole field.
- **Monopoly (exactly one bidder)** — floor is set to **bid × 0.99**,
  computed directly (no sweep). The sole bidder always clears its own
  floor; the 1% headroom removes the floor==bid floating-point knife-edge.
- **Competitive (≥2 bidders)** — the sweep explores and takes the revenue
  argmax, with its candidate range capped at **the second-highest approved
  bid × 0.99** (not the top bid). Any floor above the second bid
  manufactures a monopoly — only the top bidder clears — which, under the
  per-campaign page cap, forfeits every other campaign's fill; the sweep
  is not allowed to even probe there. A competitive floor may price out
  lowball bids, but never the top two; extraction above the second bid is
  second-price clearing's job, not the reserve's. The same ceiling is
  enforced on the **applied** floor each window (mid-cycle probes and
  stale anchors get clamped immediately, not just next cycle).

## Admission: how a floor reaches a bid request

The floor a campaign is asked to beat is
`effectiveFloor = categoryFloor × min(1.0, 0.5 + slotQuality)` — the
slot-quality prior is **discount-only** (0.5×–1.0×): a weak slot may price
below the category floor to attract fill; a premium slot never surcharges
above it. Consequence: the effective floor is always ≤ the raw floor,
which is always ≤ the observed bids — the system cannot ask bidders to
beat a price derived from their own bids plus a markup. Categories with no
learned floor fall back to the site-level sweep floor under the same rule.

## Observability

An auction that collects **zero candidates while approved bids were
rejected below the floor** logs
`WARN FLOOR-BLOCKED auction: site=… slot=… — N approved bid(s) ALL rejected below floor (best rejected bid=$X)`.
Seeing this once is normal churn; seeing it repeatedly for a category
means the zero-servable collapse failed — treat the WARN as an alarm, not
noise.

## Interaction with the rest of the system

- The publisher's `minFloorCpm` remains the hard lower bound (set in the Sites
  UI); the optimizer explores above it, and every collapse lands on it.
- Floors only matter when bidder values are spread out — in a homogeneous
  market (all bids equal), every floor below the common bid is
  revenue-equivalent and the optimizer correctly settles anywhere on that
  plateau.
- Per-slot floor overrides (admin escape hatch) bypass the category floor and
  the quality scaling entirely; overridden slots are also excluded from floor
  learning.
- Per-category floors are pushed state (SiteEntity → auctioneer → AdServer):
  a freshly restarted auctioneer serves under defaults until the first push
  arrives, then under the learned floors. Both regimes obey the same
  invariants above, so restarts do not change admission outcomes.
