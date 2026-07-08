# Promovolve: LP-to-Creative Auto-Generation

## Overview

Advertisers input a landing page (LP) URL, and Promovolve automatically generates the entire creative package: both the banner and the expandable overlay content. No banner image production, no multi-size asset uploads, no ad server ingestion. The LP is the single source of truth for all creative output.

When a reader clicks the auto-generated banner on a publisher site, rich LP-derived content expands as a full-screen overlay with no page navigation. The experience is analogous to picking up and browsing a magazine insert: the reader explores at will, and only navigates away when they click a CTA inside the overlay.

The advertiser's only input is a URL. Everything else is derived.

## Creative Philosophy: Progressive Disclosure

Every stage of the ad experience is self-sufficient. No stage exists solely to drive the user to the next.

- **Banner**: A complete message at compressed scale. The reader can see what is being offered and why it matters without clicking anything. The banner is a shop window, not a doorknob.
- **Overlay**: A complete experience. Benefits, visuals, pricing, and brand story are all available. The reader can make an informed decision without leaving the publisher page.
- **LP navigation**: A complete action. The reader who clicks through has already understood the offer and is ready to convert.

Each layer stands on its own. Deeper engagement reveals more detail, but no layer is hollow or dependent on the next to deliver value.

This is the inverse of RTB banners, which are designed as "click triggers" with no independent informational value. An RTB banner is a doorknob: it exists only to be clicked, and communicates nothing on its own. A Promovolve banner is a shop window: the content is visible from outside, and entering is a choice, not a requirement.

Consequence for conversion: users who navigate to the LP have already seen the offer in the banner, explored it in the overlay, and chosen to proceed. Every click is pre-qualified. CVR is structurally higher because the funnel filters by genuine interest at every stage, not by willingness to click an opaque image.

## Context-Adaptive Reframing

A single LP contains multiple angles of value. Which angle to lead with depends on who is reading and what they are reading about. Promovolve selects the angle at crawl-time based on publisher page context.

Example: a meal delivery service LP contains information about convenience, nutrition, pricing, and gifting. The same extracted data produces different banners depending on the article context:

| Publisher article context | Banner reframe | Lead message |
|---------------------------|---------------|--------------|
| Time management for working parents | Convenience | "Zero cooking time. Delivered to your door." |
| Solo budgeting tips | Cost efficiency | "Cheaper than you think. Plans from ¥480/meal." |
| Postpartum recovery | Accessibility | "No grocery runs. Nutritionist-designed meals delivered." |
| Remote work productivity | Workflow | "Lunch solved. Back to work in 5 minutes." |
| Caring for elderly parents remotely | Gifting/care | "Send meals to family anywhere in Japan." |
| Bodybuilding and fitness | Nutrition | "High protein. PFC-managed. No meal prep." |

In RTB, this requires the advertiser to manually create separate campaigns with separate banners for each audience segment, each targeted to different content categories. Most advertisers do not do this because the production cost is prohibitive.

In Promovolve, this happens automatically:
1. LP extraction (registration time) captures ALL value angles into structured data
2. Publisher page crawl (crawl-time) determines the article context
3. The auction winner's creative is reframed to emphasize the angle most relevant to the current page context
4. The banner, overlay, and CTA emphasis all adapt accordingly

The advertiser does nothing. One URL produces context-aware variations across every publisher page.

### Architectural implication

This means creative generation has two phases:
- **Registration time** (async): LP crawl → LLM extraction → structured data with all angles → base templates → advertiser review/edit
- **Crawl-time** (per publisher page): page context determines which angle to emphasize → banner and overlay are reframed from pre-extracted data

The reframing at crawl-time is lightweight: selecting and reordering pre-extracted elements, not generating new content. Sub-1ms serve-time target is preserved because the reframed variants can be pre-computed for common context categories.

## Pipeline

```
REGISTRATION TIME (async, per campaign):
  LP URL input
    → pekko-playwright renders LP in headless browser
    → Rendered DOM/HTML passed to LLM
    → Structured data extraction (JSON) with ALL value angles
    → Generate base banner and overlay templates
    → Advertiser previews and edits in dashboard
    → Save → stored as delivery candidate

CRAWL-TIME (per publisher page):
  Crawl publisher page → extract page context
    → Auction: match campaigns to page context
    → Winning campaign's pre-extracted data reframed to page context
    → Reframed banner + overlay variant stored for serve-time

SERVE-TIME:
  Request → return pre-built, context-reframed HTML (sub-1ms)
```

## Components

### 1. LP Crawler (pekko-playwright)

Uses hanishi/pekko-playwright.

- Full headless browser rendering of the advertiser's LP URL
- Captures dynamically rendered DOM (works with React, Next.js, any SPA)
- Extracts:
  - Rendered HTML/DOM (scripts and styles stripped)
  - Image URLs (og:image, hero images, product images)
  - Metadata (title, description, OGP tags)
- LP update detection: periodic re-crawl or manual re-generation from advertiser dashboard

### 2. LLM Structured Extraction

The rendered HTML is passed to an LLM to extract creative-relevant elements into structured data.

Input: Rendered HTML with unnecessary script/style tags removed.

Output JSON schema:

```json
{
  "headline": "Primary headline",
  "subheadline": "Secondary headline if present",
  "benefits": [
    "Benefit 1",
    "Benefit 2",
    "Benefit 3"
  ],
  "value_angles": [
    {
      "angle": "convenience",
      "headline": "Zero cooking time. Delivered to your door.",
      "lead_benefit": "Benefit 1",
      "context_signals": ["time management", "busy lifestyle", "productivity"]
    },
    {
      "angle": "nutrition",
      "headline": "High protein. PFC-managed. No meal prep.",
      "lead_benefit": "Benefit 2",
      "context_signals": ["fitness", "health", "diet", "bodybuilding"]
    }
  ],
  "images": [
    {
      "url": "https://...",
      "alt": "Description",
      "role": "hero | product | lifestyle"
    }
  ],
  "price": {
    "amount": "48,000",
    "currency": "JPY",
    "qualifier": "from"
  },
  "cta": {
    "text": "Sign up now",
    "url": "https://..."
  },
  "brand": {
    "name": "Brand name",
    "logoUrl": "https://..."
  },
  "tone": "luxury | casual | professional | playful"
}
```

The `value_angles` array is the key addition. The LLM identifies all distinct angles of value present in the LP and generates a reframed headline and lead benefit for each. The `context_signals` field lists topic keywords that would trigger this angle at crawl-time.

Why LLM over traditional scraping:
- No dependency on CSS selectors; LP structure changes don't break extraction
- Semantic judgment: can identify "this is the main benefit" vs. boilerplate
- Multilingual LP support with no additional configuration

### 3. Creative HTML Generation

The same structured data drives both the banner and the overlay. Both are self-contained HTML with no external dependencies.

#### 3a. Banner Generation

The banner itself is auto-generated from LP-extracted data. No designer, no image production.

- Headline, brand logo, key image, and CTA text composed into banner HTML
- Ad slot sizes are already known from publisher site crawl-time data. The advertiser does not choose or manage sizes. Banners are generated to fit whatever slots exist on the target publisher sites.
- Styled to match the extracted `tone` (luxury, casual, professional, playful)
- The banner is the entry point: clicking it triggers the overlay expansion

#### 3b. Overlay Generation

The expanded overlay provides the full "magazine insert" experience.

Requirements:
- Zero external dependencies (CSS/JS/fonts all inlined or Base64-embedded)
- No CSS collision with publisher site (Shadow DOM or fully scoped styles)
- Images hosted on Promovolve side (no dependency on source URL availability)
- On click: expands from banner to full-screen overlay
- Inside overlay: multi-page swipe/navigation support
- Final CTA: first and only point where page navigation to advertiser LP occurs
- Close button: dismiss overlay and return to article at any time

#### Template Variations (future expansion)

Templates apply to both banner and overlay as a matched pair:
- `magazine`: Editorial multi-page layout, magazine feature style
- `simple`: Single-page LP summary
- `rich`: Image-heavy, branding-focused

### 4. Advertiser Dashboard (Editing UI)

UI for advertisers to review and edit auto-generated creatives.

Features:
- LP URL input → banner and overlay previews appear within seconds
- Inline-editable fields (changes apply to both banner and overlay):
  - Headline / subheadline
  - Benefit text
  - Image selection and replacement
  - CTA copy
  - Price display
- Banner-specific edits: which elements to feature in the compact format
- Template switching (magazine / simple / rich) for both banner and overlay
- Mobile / desktop preview toggle
- Preview banner state, overlay expanded state, and the transition between them
- Context simulation: preview how the banner/overlay reframes across different article contexts (e.g. "how does my ad look on a fitness article vs. a parenting article?")
- Save → enters delivery candidate pool
- "Regenerate" button (re-extract from LP after updates)

Design philosophy:
- Never make the advertiser build from scratch. Start from a near-complete draft, adjust minimally.
- The entire workflow is "enter URL, tweak, publish."
- Complete replacement for the legacy workflow of producing banner images in multiple sizes and uploading them to an ad server.

## Serve-Time Behavior

1. Crawl-time: crawler visits publisher site, extracts page context
2. Auction runs, winning campaign's creative ID is determined
3. Winning creative is reframed to match publisher page context (angle selection from pre-extracted value_angles)
4. Serve request: pre-built, context-reframed HTML returned (sub-1ms target), includes both banner and overlay
5. User's browser renders the context-appropriate auto-generated banner
6. User clicks → overlay expands (client-side only, no server round-trip)
7. CTA click inside overlay → first and only navigation to advertiser's LP

## Structural Anti-Fraud

LP-derived creative generation is not just a UX improvement. It is an architectural anti-fraud mechanism.

In RTB, anyone can upload a banner image. There is no structural link between the banner content and the destination. This enables:
- Bait-and-switch: banner promises one thing, LP delivers another
- Cloaking: malicious LP shown to users, clean LP shown to ad platform reviewers
- Domain spoofing: fraudulent actors impersonating legitimate advertisers

Promovolve eliminates these by design, not by policy:
- No LP, no ad. A real, crawlable landing page must exist to generate creatives.
- Content parity is structurally guaranteed. The banner and overlay are derived from the LP itself, so what the user sees in the ad is what the LP actually contains.
- Bait-and-switch is architecturally impossible. The creative cannot diverge from its source.
- Promovolve crawls the LP, creating a natural inspection layer. Malicious content, redirect chains, and cloaking can be detected at generation time before any ad is ever served.

The key insight: fraud prevention enforced by architecture is stronger than fraud prevention enforced by policy review. Policies can be circumvented. Structure cannot.

## Structural Contrast with RTB

| Dimension | RTB | Promovolve |
|-----------|-----|------------|
| Advertiser input | Banner images in multiple sizes | LP URL only |
| Size management | Advertiser produces every size variant | Auto-fitted to publisher slots (sizes known from crawl) |
| Creative format | Single banner image | Auto-generated banner + rich overlay, both from LP |
| Banner value | Doorknob: no informational value, exists only to be clicked | Shop window: self-contained message, valuable without clicking |
| Production cost | Designer required, multi-size asset upload | Zero: URL in, creatives out |
| User experience | Click → immediate page navigation | Browse the "insert" in place |
| Contextual adaptation | Manual: advertiser creates separate campaigns per audience segment | Automatic: same LP produces context-reframed variants per publisher page |
| LP synchronization | Separate manual management | LP updates → banner and overlay auto-follow |
| LLM utilization | Impossible (100ms latency constraint) | Feasible (async pipeline) |
| Fraud prevention | Policy-based review (circumventable) | Architectural (structurally inexpressible) |

## Implementation Priority

1. LLM extraction prompt design and JSON schema finalization (including value_angles)
2. pekko-playwright LP fetch → HTML preprocessing pipeline
3. Banner HTML template generation (multiple sizes from same data)
4. Overlay HTML template (start with `magazine`, one template)
5. Context-adaptive reframing: page context → angle selection → banner/overlay variant
6. End-to-end pipeline integration (URL input → banner + overlay preview with context simulation)
7. Advertiser editing UI
8. Delivery store integration
9. LP update detection and re-generation

## Delivery Format

The expandable banner is delivered as a web component (`expandable-magazine-banner.js`):
- Shadow DOM encapsulation — no CSS collision with publisher page
- No React or framework dependency
- Embeddable via `<expandable-magazine-banner>` custom element
- `pages` attribute accepts JSON for dynamic content from the extraction pipeline
- Source at `platform/banner-component/` (TypeScript, Vite library build)
