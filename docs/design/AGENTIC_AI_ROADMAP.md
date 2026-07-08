# Agentic AI Roadmap

> **Status (June 2026):** still a forward-looking roadmap — none of the seven
> features below have been started. The prerequisite infrastructure (LLM
> provider abstraction, Thompson Sampling, PI pacing, DData) is all in place.

This document outlines how Promovolve can evolve from reactive intelligence (Thompson Sampling, LLM classification, PI-controlled pacing) to autonomous, goal-driven agentic behavior.

---

## Current AI/ML Capabilities

Promovolve already employs four distinct AI/ML systems:

1. **LLM Content Classification** (IABTaxonomy) — maps page content to 600+ IAB categories via OpenAI, Anthropic, or Gemini
2. **LLM Creative Assessment** — multimodal analysis of ad creatives for quality and compliance
3. **Thompson Sampling** — Bayesian CTR learning at both category and creative levels
4. **PI Control + Traffic Shape Learning** — adaptive budget pacing throughout the day

These systems are **reactive**: they respond to inputs and optimize within fixed parameters. The agentic roadmap moves toward systems that **set their own sub-goals, plan ahead, and take autonomous action**.

---

## 1. Autonomous Campaign Optimization Agent

**Status**: Not started
**Impact**: High (revenue)
**Complexity**: Medium

Currently campaigns have static CPM bids and fixed category targeting. An optimization agent would:

- **Auto-tune CPM bids** based on performance goals (target CPA, ROAS, CTR floor)
- **Expand/contract category targeting** — if "Technology > AI" performs well, autonomously test adjacent categories like "Technology > Cloud Computing"
- **Pause underperforming creatives** and reallocate budget to winners without human intervention
- **Set daily micro-goals** — "Today I need to find 3 new viable categories for Campaign X"

### Design Considerations

- Agent operates per-campaign with access to `CampaignEntity` state and `TaxonomyRankerEntity` performance data
- Advertiser sets high-level objectives (maximize clicks under $2 CPA); agent handles tactical decisions
- All autonomous changes logged with reasoning for auditability
- Guardrails: maximum bid ceiling, budget hard limits, category exclusion lists

---

## 2. Publisher Auto-Approval Agent

**Status**: Not started
**Impact**: High (removes biggest manual bottleneck)
**Complexity**: Low-Medium

The approval workflow is the primary human bottleneck. The existing LLM creative assessment infrastructure already evaluates quality and compliance.

### Approach

- Use LLM assessment scores to **auto-approve** creatives above a publisher-defined confidence threshold
- **Learn publisher preferences** from historical approve/reject decisions to build a per-publisher approval model
- Only surface **borderline cases** for human review (human-in-the-loop only when uncertain)
- Support publisher-configurable policies: "auto-approve all creatives in categories X, Y, Z" or "always require manual review for pharmaceutical ads"

### Architecture

- New `ApprovalAgent` behavior wrapping `PendingSelectionStore`
- Consumes assessment results from `CreativeAssessmentUI`
- Maintains learned preference model per publisher (stored in publisher state)
- Emits `AutoApproved` / `EscalatedToHuman` events for audit trail

---

## 3. Self-Healing Auction Pipeline

**Status**: Not started
**Impact**: Medium-High (reliability)
**Complexity**: Medium

Currently, LLM provider failures trigger retries with backoff. A self-healing agent would go further:

- **Detect classification drift** — if a provider starts returning different category distributions for similar content, flag the anomaly and switch providers
- **Cross-validate classifications** across providers and learn which provider is most accurate per content vertical
- **Auto-reclassify** pages when performance data contradicts the original classification (e.g., a page classified as "Finance" consistently gets high CTR from "Technology" campaigns)
- **Monitor provider latency/cost** and dynamically route to the optimal provider

### Triggers

- CTR deviation from expected range for a given classification
- Provider response time exceeding SLA
- Classification confidence below threshold
- Significant divergence between providers on the same content

---

## 4. Goal-Directed Budget Agent

**Status**: Not started
**Impact**: High (efficiency)
**Complexity**: High

Replace or augment the PI controller with a planning agent that reasons about budget allocation:

- Has an **explicit goal** (e.g., "spend $500/day while maximizing CTR above 2%")
- **Plans ahead** — if it detects a high-traffic period approaching (from TrafficShapeTracker history), it reserves budget
- **Negotiates** between competing objectives (spend rate vs. quality vs. coverage)
- **Explains decisions** — "Throttled Campaign A during 2-4pm because CTR dropped below target; reallocated budget to evening slots where historical CTR is 40% higher"

### Design Considerations

- Operates as a higher-level planner above `AdaptivePacing`
- Generates hourly budget allocation plans based on predicted traffic shape
- Revises plans in response to real-time deviations
- Exposes reasoning via structured logs or dashboard

---

## 5. Autonomous Content Strategy Agent

**Status**: Not started
**Impact**: Medium (publisher value)
**Complexity**: Medium

An agent that acts on behalf of publishers to maximize ad revenue:

- **Recommends content categories** to produce based on advertiser demand and CPM rates
- **Detects content gaps** — "You have high-value tech traffic but no finance content; finance CPMs are 3x higher in your audience segment"
- **Monitors competitive landscape** across the publisher's category mix
- **Suggests crawl priorities** — which pages to re-crawl based on staleness and revenue potential

### Data Sources

- Aggregated auction data (which categories have unfilled demand)
- Publisher's existing category distribution from `TaxonomyRankerEntity`
- Historical CPM and fill rate per category

---

## 6. Multi-Step Auction Reasoning

**Status**: Not started
**Impact**: Medium (ad quality)
**Complexity**: High

Currently the auction pipeline is linear: classify, rank, bid, serve. Multi-step reasoning adds coordination:

- **Cross-slot coordination** — if slot A shows a car ad, slot B should show a complementary brand, not a competitor
- **Session-level coherence** — without tracking individuals, use page-level signals to maintain thematic consistency across ad slots on the same page
- **Advertiser intent reasoning** — if an advertiser's landing page indicates a time-limited promotion, boost serving urgency
- **Contextual exclusion** — reason about why certain ad/content combinations are inappropriate beyond category matching

---

## 7. Observability and Self-Diagnosis Agent

**Status**: Not started
**Impact**: High (operational)
**Complexity**: Medium

An always-on agent that monitors system health and takes corrective action:

### Detection

- **Anomalous patterns**: CTR sudden drops, fill rate spikes, budget burn rate deviations
- **Infrastructure issues**: crawler returning stale content, LLM provider degradation
- **Data quality**: classification confidence trending downward, creative assessment failures

### Response

- **Root-cause analysis** — "CTR dropped 40% because the crawler is returning stale content for 30% of URLs; last successful crawl was 72 hours ago"
- **Corrective action** — trigger re-crawl, adjust pacing parameters, switch LLM providers
- **Operator alerts** — escalate issues that require human judgment with context and recommended actions

### Architecture

- Consumes events from `TrackingEventJournal` and `DashboardProjectionHandler`
- Maintains rolling statistical baselines per publisher, campaign, and category
- Decision tree for automated responses vs. human escalation

---

## Implementation Priority

| Priority | Feature | Rationale |
|----------|---------|-----------|
| 1 | Publisher Auto-Approval Agent | Removes the biggest manual bottleneck; builds on existing LLM assessment infrastructure |
| 2 | Autonomous Campaign Optimization Agent | Moves from static to goal-directed bidding; largest revenue impact |
| 3 | Self-Diagnosis Agent | Reduces operational burden; critical for scaling beyond manual monitoring |
| 4 | Goal-Directed Budget Agent | Improves spend efficiency; builds on existing pacing infrastructure |
| 5 | Self-Healing Auction Pipeline | Improves reliability; most value at scale with multiple LLM providers |
| 6 | Content Strategy Agent | Publisher retention feature; requires aggregated data maturity |
| 7 | Multi-Step Auction Reasoning | Highest complexity; most value with high slot density publishers |

---

## Cross-Cutting Concerns

### Auditability

Every autonomous decision must be logged with:
- The agent's reasoning (structured, not free-text)
- Input data that informed the decision
- The action taken and its expected outcome
- Rollback capability where possible

### Guardrails

- Hard limits on all autonomous actions (max bid, budget ceiling, category exclusions)
- Confidence thresholds below which the system escalates to humans
- Kill switches per agent per publisher/advertiser

### Observability

- Agent decision traces visible in dashboard
- A/B testing framework for comparing agent vs. manual performance
- Gradual rollout: shadow mode (agent recommends, human decides) before autonomous mode