You are working in a Scala codebase with these files:
- ThompsonSampling.scala
- PacingStrategy.scala
- AdaptivePacing.scala

Goal
----
Fix the issue where pacing “kills” Thompson Sampling by censoring exploration.
  Right now pacing is effectively applied AFTER ThompsonSampling picks a winner (or otherwise changes which arms get served).
  This biases learning because exploration arms get disproportionately filtered out.

Core requirement
  ----------------
Pacing must control ONLY the overall delivery rate (volume), not WHICH candidate/arm is eligible.
  So invert the control flow:

  ❌ current (bad):
winner = ThompsonSampling.pick(candidates)
if (shouldThrottle) drop else serve(winner)

✅ desired (good):
if (shouldServeNow) {
  winner = ThompsonSampling.pick(candidates)
  serve(winner)
} else {
  drop // but do NOT run TS, do NOT update TS
}

Implementation instructions
  --------------------------
1) Keep ThompsonSampling.scala PURE (do not add pacing awareness there).
  ThompsonSampling should only pick among a candidate set and not be told about throttling.

2) Refactor pacing into a “traffic shaper” API that answers:
  shouldServe(now, state): Boolean
or produces tokens (token bucket style). This should live in PacingStrategy.scala / AdaptivePacing.scala.

3) Convert existing probabilistic throttle that is applied per-winner into ONE of these:
  Option A (preferred): token bucket / leaky bucket
- Each time tick/refill, add tokens proportional to the PI controller’s desired rate.
  - Serving consumes 1 token.
- If no token, you skip serving and DO NOT call ThompsonSampling.
Option B: Bernoulli gate (minimal change)
- Compute allowProb from PI controller
- if (rng.nextDouble < allowProb) { run TS and serve } else { skip }
- Crucially: gating happens BEFORE TS, not after TS.

4) Ensure candidate eligibility is unchanged by pacing:
  - Pacing must not drop low-ranked candidates
- Pacing must not be applied per-candidate or per-arm
- Pacing does not reweight Thompson sampling scores

5) Do NOT update ThompsonSampling posterior on skipped impressions.
(Skipped == “not shown”, so no reward observation.)

6) Wire-up / call-site changes:
  - Find the place where TS picks a winner and pacing drop happens.
  - Move pacing gating BEFORE calling TS.
  - Keep existing logging/metrics but update them to distinguish:
- skipped_due_to_pacing
- served_impressions

Acceptance criteria
  -------------------
- When pacing blocks an impression, ThompsonSampling.pick(...) is not called.
  - ThompsonSampling sees the same candidate set distribution whenever a serve happens.
- Exploration is not selectively filtered by pacing.
  - Existing behavior remains the same when pacing is effectively “100% allow”.

Tests / verification
--------------------
Add or update unit tests (ScalaTest or whatever is used) to cover:
  1) When pacing denies, TS pick is never invoked (use a stub/spy).
2) When pacing allows, TS is invoked exactly once and serve happens.
3) Under heavy throttling, the distribution of WHICH arms are served (conditional on serving) remains governed by TS, not by pacing.

  Deliverables
------------
- Updated Scala code in the above files (and any necessary call sites).
  - Tests demonstrating the new behavior.
- A short comment in PacingStrategy/AdaptivePacing explaining: “pacing gates volume, not choice”.

Make minimal invasive changes where possible, but prioritize correctness of the flow.