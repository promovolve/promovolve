---
version: 1
slug: "platform-templates-layout-html"
primary_target: "platform/templates/layout.html"
related_targets: ["platform/templates"]
---

# Surface brief: Promovolve dashboard (all roles)

Scope: the whole server-rendered dashboard (platform/templates), advertiser first, then publisher and admin. Mode: Operate (help pages: Read). Replaces the "Editor's Desk" world; user verdict on the old look: generic AI shell, palette, components and type. Nothing visual is sacred; product truth, EN/JA parity, WCAG 2.2 AA and Go templates stay.

Audience and job: advertisers checking delivery and where money went; publishers approving ads and watching earnings; operators running approvals, billing and fraud.

## Direction contract

THESIS: Delivery and money read like a Japanese weather forecast: what happened hour by hour, what is coming at this pace, and official-style advisory/warning levels. Refuses the category default of a dark sidebar, KPI-card row and pill badges.

OWN-WORLD: White ground, ink #1a1a1a, blue-grey #dfe7ee top bar and region fields, 2px corners, no shadows. JMA rainfall-intensity scale (#f2f2ff→#a0d2ff→#2185ff→#0041ff→#fff500→#ff9900→#ff2800→#b40068) used only for intensity data. Advisory = yellow tag with ink text; warning = red tag. BIZ UDPGothic for all text, tabular figures. Ink-filled primary buttons. Raises: hierarchy by scale alone; one reserved "yours" colour in shared charts; no chrome beyond words and colour; focus opens detail in place; comparisons share one fixed scale.

STORY: The advertiser sees today's spend, balance and runway at a glance, reads each campaign's day as a forecast strip, and acts on the advisories before money is wasted.

FIRST VIEWPORT: Top bar (wordmark, role tabs, account, language). Title with "as of" time and primary action at right. One large figure (today's spend) with balance/runway small beside it. Segmented filter, then the campaign table whose hourly strip column and outlook column carry the forecast; legend for the scale under it.

FORM: JMA forecast graphics, my grounded list position 6 of 7 (round 2). seed:c08f9734641357f8

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Unresolved
- Hourly per-campaign spend and the projection must come from real data; verify before building the strip.
- Font delivery (self-host BIZ UDPGothic vs system fallback) under the dashboard CSP.
