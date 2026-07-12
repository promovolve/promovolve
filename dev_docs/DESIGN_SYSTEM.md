# PromoVolve Dashboard Design System

Scope: the Go-template dashboard (`platform/templates/`) — publisher + advertiser
pages and login. The creative designer keeps its own dark theme for now; a later
phase can point its `cssText` constants at these tokens via CSS custom properties.

The single source of truth is the token config + component layer in
`platform/tailwind.config.js` (tokens) and `platform/tailwind.input.css`
(component classes) — compiled to the committed `platform/static/tailwind.css`
by `scripts/build-tailwind.sh`. The Play CDN is gone: after ANY class change
in templates, rebuild and commit the CSS or the new class silently renders
unstyled. This file documents the decisions.

## Migration policy

**Opportunistic, not big-bang.** Existing pages keep their raw utility strings
until touched. When you edit a page, swap ad-hoc utilities for the canonical
classes below. New markup must use the canonical classes.

## Tokens

### Color

| Token | Value | Use |
|---|---|---|
| `brand` | `#2563eb` (blue-600) | Primary actions, active nav/tab, links |
| `brand-hover` | `#1d4ed8` (blue-700) | Primary hover |
| `brand-soft` | `#eff6ff` (blue-50) | Selected-row / info backgrounds |
| success | green-600 / green-100+700 | Confirmations, healthy status |
| danger | red-600 / red-100+700 | Destructive actions, errors |
| warning | amber-100+700 | Pending / degraded status |
| neutral page bg | gray-50 | `<body>` |
| sidebar | gray-900 | Left nav |
| surface | white + `border` + `rounded-lg` | Cards, panels |

**Indigo is deprecated.** Several pages (creative editor flows) used
`indigo-600` as a second primary — normalize to `brand` when touching them.

### Shape & type

- Radius: `rounded-md` (6px) for controls, `rounded-lg` (8px) for surfaces,
  `rounded-full` for badges. Bare `rounded` is deprecated.
- Page heading: `.page-title` (`text-2xl font-semibold`), subtitle `.page-sub`.
- Body text `text-sm`; metadata/hints `text-xs text-gray-500`.

## Canonical component classes

Defined in `platform/tailwind.input.css` `@layer components`:

| Class | Composition |
|---|---|
| `.btn` + `.btn-primary` / `.btn-secondary` / `.btn-success` / `.btn-danger` | base is `px-4 py-2 text-sm font-medium rounded-md`; add `.btn-sm` for compact |
| `.card`, `.card-tight` | `bg-white border rounded-lg` with `p-6` / `p-4` |
| `.badge` + `.badge-info` / `-success` / `-warning` / `-danger` / `-neutral` | pill, `*-100` bg + `*-700/800` text |
| `.form-label`, `.form-input`, `.form-hint` | labeled inputs with brand focus ring |
| `.page-title`, `.page-sub` | page header pair |

Usage: `<button class="btn btn-primary">Save</button>`,
`<button class="btn btn-danger btn-sm">Delete</button>`.

## Known debt

- ~~Play CDN~~ — resolved 2026-07-08: `cdn.tailwindcss.com` is gone,
  replaced by the compiled `platform/static/tailwind.css` (see the header
  above for the build loop).
- `platform/static/style.css` (an older hand-rolled system, never linked)
  was deleted 2026-07-03.
- A visual component gallery is published to claude.ai/design
  ("PromoVolve Design System" project) — keep it in sync when tokens change.
