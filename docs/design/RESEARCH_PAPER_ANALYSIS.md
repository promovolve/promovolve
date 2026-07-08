# Research Paper Analysis: Applicability to Promovolve

This document analyzes three reference materials and maps their algorithms to Promovolve's architecture, identifying concrete opportunities for improvement.

---

## Source Materials

| # | Title | Type | Key Contribution |
|---|-------|------|------------------|
| 1 | An Effective Budget Management Framework for Real-time Bidding (Liu et al., IEEE Access 2020) | Research paper | Piecewise bidding with pCTR thresholds + time-slot budget allocation |
| 2 | Budget Constrained Bidding by Model-free RL (Wu et al., arXiv 1802.08365) | Research paper + memo | DQN-based bid optimization under budget constraints |
| 3 | Grokking Deep Reinforcement Learning (Morales, Manning 2020) | Textbook | Comprehensive DRL reference from MDPs through advanced actor-critic |

---

## Paper 1: Effective Budget Management Framework

### Summary

The paper models budget optimization as a multi-constrained allocation problem with a smooth delivery constraint. Its two key innovations:

1. **Piecewise Bidding Strategy**: Each time slot has its own pCTR threshold. The campaign only bids on impressions whose predicted CTR exceeds this threshold, filtering out low-quality impressions before bidding.

2. **Time-Slot Budget Allocation**: Daily budget is divided across time slots (e.g., 12 x 2-hour slots) using a heuristic optimizer, then dynamically adjusted based on actual spend.

The pCTR threshold for time slot t is derived as:

```
pctr_threshold(t) = (1 - alpha) root of ((1 - alpha) / (c * mprice)) * b*_t
```

Where `c, alpha` are power-law parameters of the pCTR distribution, `mprice` is average market price of high-quality impressions, and `b*_t` is the allocated budget for slot t.

The optimal bidding function per time slot is:

```
bid(theta, t) = sqrt((lambda * k2^2 + theta * k1 * k2) / (lambda * k1^2)) - k2/k1
```

Where `theta` is the impression's pCTR, `lambda` is the Lagrangian multiplier, and `k1, k2` are winning function parameters learned from historical data.

### Relevance to Promovolve: HIGH

#### What Promovolve Currently Does

- **Budget pacing**: PI controller (`AdaptivePacing`) adjusts throttle probability based on spend ratio. Binary decision: serve or don't serve.
- **Traffic shaping**: `TrafficShapeTracker` learns hourly traffic patterns and adjusts pacing targets. Similar spirit to the paper's time-slot allocation but less sophisticated.
- **Creative selection**: Thompson Sampling picks which creative to show, but all candidates that pass the pacing gate are eligible regardless of expected quality.

#### What the Paper Adds

**1. pCTR Threshold as Quality Gate (Medium effort, High impact)**

Currently, `AdServer.shouldServe()` is a binary pacing decision — it controls *volume* but not *quality*. The paper's pCTR threshold adds a second gate: even when pacing allows serving, reject impressions below a quality threshold.

This maps naturally to Promovolve's architecture:

```
Current flow:
  AdServer.Select → pacing gate (shouldServe?) → Thompson Sampling → serve

Proposed flow:
  AdServer.Select → pacing gate (shouldServe?)
                   → quality gate (pCTR >= threshold?)
                   → Thompson Sampling → serve
```

The pCTR can be derived from `TaxonomyRankerEntity`'s Beta posteriors — the category-level CTR estimate is already available. The threshold would be computed per time slot based on remaining budget and expected traffic.

**Impact**: Avoids wasting budget on low-CTR impressions, especially during high-traffic periods where the pacing gate alone lets through many requests. The paper shows this prevents the "budget wiped out too early" problem (their Table IV: 238 missing clicks from budget exhaustion with fixed bidding vs. 0 with their approach).

**2. Dynamic Budget Reallocation Across Time Slots (Low effort, Medium impact)**

The paper's equation (17) for dynamic budget adjustment:

```
b'_t = b*_t * (B - sum(actual_spend)) / sum(remaining_b*_t)
```

This is more principled than the current PI controller's continuous adjustment. It could augment `AdaptivePacing` by providing per-slot budget targets that the PI controller tracks, rather than having the PI controller work against a simple linear schedule.

Currently `RateAwarePacing` computes `evenTargetImpsPerSec` as `budget / dayDuration / avgCPM` and scales by traffic shape. The paper suggests each slot should have an independently optimized budget that accounts for the *quality distribution* of impressions in that slot, not just volume.

**3. Time-Varying Bidding Function (High effort, High impact)**

The paper's bid function (equations 15-16) varies by time slot, incorporating:
- The impression's pCTR
- The slot's winning function (learned competition level)
- The slot's spending rate

This is relevant for Promovolve's auction system. Currently `CampaignEntity` has a static CPM bid. With time-varying bidding, the effective bid would adjust based on when the impression occurs and how competitive that time slot is. This aligns with the **Autonomous Campaign Optimization Agent** in the agentic roadmap.

### Recommended Integration Points

| Component | Current | With Paper's Algorithm |
|-----------|---------|----------------------|
| `AdServer.shouldServe()` | Binary pacing gate | Pacing gate + pCTR quality gate |
| `AdaptivePacing` | PI control against linear schedule | PI control against slot-optimized budget allocation |
| `CampaignEntity` bid | Static CPM | Time-varying bid function adjusted by slot competition |
| `TrafficShapeTracker` | Volume-only shape | Volume + quality distribution per slot |

---

## Paper 2: Budget Constrained Bidding by Model-free RL

### Summary (from memo)

The paper uses Deep Q-Network (DQN) to learn optimal bid adjustment under budget constraints. The core idea:

- **Bid formula**: `bid = v / lambda` where `v` is impression value, `lambda` is a learned parameter
- **State space**: CPM, ROI, win rate (WR), and the ratio of winning impressions to total opportunities
- **Action space**: Adjust `lambda` (up/down/hold)
- **Reward**: `r(s, a) = max over episodes E(s,a) of sum(r_t)` — maximizes cumulative impressions
- **Key insight**: The reward function is designed so that optimizing it is equivalent to the original constrained optimization, reducing the RL problem's difficulty

The approach is model-free — it doesn't need to model the market dynamics (pCTR distributions, winning functions, competitor behavior). It learns entirely from interaction.

### Relevance to Promovolve: HIGH (for agentic evolution)

#### Direct Mapping to Promovolve

This paper provides the algorithmic backbone for the **Autonomous Campaign Optimization Agent** and the **Goal-Directed Budget Agent** from the agentic roadmap.

**State space mapping:**

| Paper State | Promovolve Equivalent | Source |
|------------|----------------------|--------|
| CPM | Current effective CPM | `CampaignEntity.state.cpm` |
| ROI | Click value / spend (revenue per click) | Computed from `TaxonomyRankerEntity` CTR |
| Win Rate | Auction win rate | Ratio of `Selected` vs `NoCandidates` events |
| Impression ratio | Fill rate | `AdServer` tracking data |
| Budget remaining | `remainingBudget / dailyBudget` | `CampaignEntity.state` |
| Time remaining | Fraction of day elapsed | `PacingContext.elapsedFraction` |

**Action space mapping:**

The `lambda` adjustment maps to adjusting the campaign's **effective bid multiplier**. Instead of a static CPM, the RL agent outputs a multiplier:

```
effective_bid = base_cpm * (1 / lambda)
```

Where `lambda > 1` means bid lower (conserve budget), `lambda < 1` means bid higher (be aggressive).

This could be implemented as:
- A new `BidStrategy` trait alongside the existing static bid
- The RL agent runs per-campaign, observing state every N minutes
- Actions: increase/decrease/hold the bid multiplier in discrete steps
- Reward: clicks achieved (or conversions, depending on campaign goal)

**Why model-free matters for Promovolve:**

Promovolve's contextual ad market is inherently non-stationary:
- Publisher traffic patterns shift
- Competitor campaigns start/stop unpredictably
- Category performance varies by content trends

A model-based approach would need to model all of this. Model-free RL simply observes outcomes and adjusts — a much better fit for Promovolve's distributed, multi-publisher environment.

#### Replacing vs. Augmenting the PI Controller

The PI controller (`AdaptivePacing`) and the RL bid agent serve different purposes:

- **PI controller**: Controls *volume* — how many impressions to serve (pacing)
- **RL agent**: Controls *value* — how much to bid per impression

They should coexist:

```
RL Agent (slow loop, every 15 min):
  Observes: CPM, ROI, win rate, budget remaining, time remaining
  Outputs: bid multiplier (lambda adjustment)
  → Updates CampaignEntity effective bid

PI Controller (fast loop, every request):
  Observes: spend ratio, request rate, traffic shape
  Outputs: throttle probability
  → Gates each individual serving decision
```

This two-level architecture matches the paper's structure: RL handles strategic bid optimization while the pacing mechanism handles tactical delivery.

### Implementation Considerations

**Episode structure**: The paper trains on 7-day episodes with auctions every 15 minutes (96 per day). Promovolve's natural episode is one campaign day, with state observations at regular intervals.

**Cold start**: New campaigns have no training data. Options:
- Transfer learning from similar campaigns (same category, similar budget)
- Start with the paper's heuristic baseline and let RL fine-tune
- Use the current static bid as the initial policy

**Computational cost**: DQN is lightweight — a small neural network (2-3 hidden layers) that runs inference every 15 minutes per campaign. Training can happen offline on historical data. This fits within Pekko actors without requiring GPU infrastructure.

---

## Book: Grokking Deep Reinforcement Learning

### Relevance: REFERENCE for implementing Papers 1 and 2

The book is not directly applicable as-is, but specific chapters provide implementation guidance:

### Most Relevant Chapters

**Chapter 3: Balancing Immediate and Long-Term Goals (pp. 65-96)**

Directly relevant to budget pacing. The discount factor `gamma` in RL maps to Promovolve's pacing trade-off: do we serve this impression now (immediate reward) or save budget for potentially better impressions later (long-term reward)?

Key concept: The optimal policy balances exploitation of known high-CTR placements with exploration of new opportunities — exactly what Thompson Sampling does at the creative level, but the book shows how to extend this to the *bidding level* via value functions.

**Chapter 4: Balancing Gathering and Use of Information (pp. 97-130)**

Maps to Promovolve's exploration/exploitation trade-off in multiple places:
- `ThompsonSampling.select()` already handles this for creative selection
- The pCTR threshold from Paper 1 introduces a new explore/exploit dimension: should we bid on uncertain-quality impressions?
- Category expansion (testing adjacent categories) is pure exploration

The chapter's treatment of **Upper Confidence Bound (UCB)** could enhance Thompson Sampling for categories with very few observations (where Beta posteriors have high variance).

**Chapter 6: Improving Agents' Behaviors (pp. 167-202)**

Covers policy improvement methods (SARSA, Q-learning) that form the basis for Paper 2's DQN approach. The section on **decoupling behavior from learning** (off-policy methods, p. 187) is critical: Promovolve needs to learn from historical serving data without re-running live auctions.

**Chapter 9: DQN (pp. 275-308)**

Direct implementation reference for Paper 2's approach:
- Experience replay: store (state, action, reward, next_state) tuples from campaign history
- Target network: stabilize training when market conditions shift
- The chapter explains both the standard DQN and Double DQN (reduces overestimation of bid values)

**Chapter 12: Advanced Actor-Critic — SAC, PPO (pp. 375-410)**

For continuous action spaces (bid amount instead of discrete lambda adjustment):
- **SAC (Soft Actor-Critic)**: Maximizes reward + entropy, encouraging exploration. Good fit for bid optimization where you want to discover new price points.
- **PPO (Proximal Policy Optimization)**: Stable training with policy constraints. Good fit for production systems where you can't afford large policy swings.

Either could be a more sophisticated replacement for DQN in Paper 2's framework, especially if the bid adjustment is continuous rather than discrete.

---

## Synthesis: Combined Roadmap

These three sources support a phased evolution:

### Phase 1: Quality-Aware Pacing (from Paper 1)

**Add pCTR quality gate to the pacing pipeline.**

Minimal change to existing architecture:
- `TaxonomyRankerEntity` already has category-level CTR posteriors
- Compute pCTR threshold per time slot based on remaining budget and impression quality distribution
- Add threshold check in `AdServer` between pacing gate and Thompson Sampling
- Dynamic budget reallocation across time slots (augments TrafficShapeTracker)

This is a **classical optimization** approach — no neural networks, no training pipeline. It can be implemented entirely within the existing Pekko actor model.

**Where it helps**: Prevents budget waste on low-quality impressions. Paper's results show 40%+ improvement in clicks compared to fixed bidding with no budget management (Table V: 244 clicks OAA vs. 101 clicks NBA at 5M budget).

### Phase 2: RL-Based Bid Optimization (from Paper 2 + Book Ch 9)

**Replace static campaign CPM with learned bid policy.**

New component:
- `BidOptimizationAgent` actor per campaign
- State: (CPM, CTR, win_rate, budget_remaining, time_remaining, traffic_shape_bucket)
- Action: adjust bid multiplier (discrete: -20%, -10%, 0%, +10%, +20%)
- Reward: clicks (or conversions) in the period
- DQN with experience replay, trained on historical campaign data

This requires:
- A training pipeline (can run offline, nightly)
- State observation collection (extend `TrackingEventJournal`)
- Action application (new `BidStrategy` in `CampaignEntity`)

Reference: Book Chapter 9 (DQN implementation), Chapter 10 (Dueling DDQN for better sample efficiency)

### Phase 3: Advanced RL (from Book Ch 11-12)

**Upgrade to continuous action space with actor-critic.**

Once DQN proves the concept, upgrade to:
- **PPO** for stable production deployment (Book Ch 12, p. 398)
- **SAC** if exploration remains important (Book Ch 12, p. 391)
- Continuous bid adjustment instead of discrete steps
- Multi-objective reward: balance clicks, spend smoothness, and CPA targets

### Phase 4: Multi-Agent RL

**Multiple campaigns as interacting agents.**

When multiple campaigns compete for the same publisher slots:
- Each campaign runs its own RL agent
- Agents implicitly learn competitive dynamics through the auction mechanism
- Book Chapter 7 ("Agents that interact, learn, and plan", p. 217) provides the theoretical foundation

---

## Mapping to Agentic AI Roadmap

| Roadmap Item | Paper/Book Source | Algorithm |
|-------------|------------------|-----------|
| Autonomous Campaign Optimization | Paper 2 + Book Ch 9 | DQN bid optimization per campaign |
| Goal-Directed Budget Agent | Paper 1 + Paper 2 | Slot budget allocation + RL bid adjustment |
| Self-Healing Auction Pipeline | Paper 1 Sec IV.C | CTR estimation quality monitoring |
| Quality-Aware Pacing | Paper 1 Sec IV.A | pCTR threshold filtering |
| Exploration vs Exploitation | Book Ch 4 | UCB + Thompson Sampling enhancements |
| Cross-Day Learning | Paper 2 (episode structure) | RL agent carries forward Q-values across days |

---

## Key Differences: Promovolve vs. Paper Assumptions

The papers assume a standard RTB ecosystem (DSP bidding on exchanges). Promovolve differs in important ways:

| Aspect | Standard RTB (Papers) | Promovolve |
|--------|----------------------|------------|
| Auction type | Second-price (GSP) on exchange | First-party contextual auction |
| User targeting | Cookie/ID-based behavioral | Privacy-first, content-only |
| Bid volume | Millions of bid requests/day | Lower volume, higher value |
| pCTR source | User behavior model | LLM content classification + Thompson Sampling |
| Competition | Multi-DSP exchange | Internal campaigns only |
| Market dynamics | Highly competitive, many actors | Controlled marketplace |

These differences mean:
- **Paper 1's winning function** simplifies: since Promovolve controls the auction, the winning rate model is deterministic (highest bid wins)
- **Paper 2's state space** can be simpler: no need to model external competitor behavior
- **Budget constraints** are more important: smaller budgets mean each impression counts more
- **pCTR estimation** relies on LLM classification + historical CTR rather than user-level prediction models

The algorithms adapt well to Promovolve's context — if anything, the simpler market structure makes them easier to apply effectively.

---

## Recommended Reading Order

For implementation:
1. Paper 1, Sections III-IV (problem formulation + budget management framework) — implement Phase 1
2. Book Chapter 9 (DQN) — understand the implementation substrate for Phase 2
3. Paper 2 + memo (RL bid optimization) — design the RL agent for Phase 2
4. Book Chapter 12 (SAC/PPO) — upgrade path for Phase 3
