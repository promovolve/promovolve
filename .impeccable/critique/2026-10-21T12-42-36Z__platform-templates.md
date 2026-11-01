---
target: whole dashboard design
total_score: 24
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 4
target_identity: "file:/Users/hanishi/promovolve/platform/templates"
timestamp: 2026-10-21T12-42-36Z
slug: platform-templates
---
# Critique: platform/templates (whole dashboard) — 24/40 Acceptable
Method: dual-agent (A design review, B detector + Playwright)

| # | Heuristic | Score |
|---|---|---|
| 1 | Visibility of status | 3 |
| 2 | Match real world | 2 |
| 3 | User control | 2 |
| 4 | Consistency | 2 |
| 5 | Error prevention | 3 |
| 6 | Recognition | 2 |
| 7 | Flexibility | 2 |
| 8 | Minimalist | 3 |
| 9 | Error recovery | 2 |
| 10 | Help | 3 |

Specificity: generic admin shell; specificity carried by copy (bid checker, settlement notes, fraud evidence), not form. Detector: 49 template findings, 42 false positives ({{asset}} css, Alpine :src); real: 7 hardcoded colors creative-editor.html:87,613-623. Rendered: .btn-success 3.3:1; /admin/sites 375px doc width 722 (verified table); editor h1->h3.

## Priority issues
1. [P1] Campaign row not keyboard/SR operable (div onclick advertiser/campaigns.html:341), status colour-only, no aria-current in layout.html — /impeccable harden
2. [P1] No single "where the money went" view; Wallet lacks pending-today, runway, top-up path; four spend framings — /impeccable layout + clarify
3. [P1] Campaign row breaks at 375/JA; /admin/sites table leaks overflow at 375 — /impeccable adapt
4. [P1] Colour: .btn-success fails AA; budget bar red at 90%; Block/Prohibit/Pause red; 6 primaries on admin settings — /impeccable quieter
5. [P2] Section headings at page-title size, 3 stat-tile variants, nav/title name mismatches, stale "Billing always uses UTC days" (admin/settings.html:55) — /impeccable typeset + clarify

## Persona red flags
Alex: no bulk actions, expand-to-edit bids, no saved ranges, 6 submits on settings. Sam: row toggle unfocusable, dots without text, stats chart hover-only (advertiser/stats.html:40), no aria-current. Advertiser-money: four spend numbers, no projected balance, "contact the operator" dead end, red bar at success.

## Minor
Login shadow + generic tagline, logo unused; wallet card ad-hoc; "2 campaigns" vs tile 1; creative tiles titled by state; floor chart without axes; dark mode loses desk/paper contrast; empty states without CTA.

## Questions
Creative-first home? One page per campaign instead of Campaigns/Stats/Report? Auction explanation as the primary content?
