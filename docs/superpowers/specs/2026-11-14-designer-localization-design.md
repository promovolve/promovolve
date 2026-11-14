# Designer localization (Japanese)

Issue: #498. Status: approved in chat 2026-11-14, awaiting spec review.

## Goal

A Japanese advertiser sees the Creative Designer in Japanese, the same as the
dashboard pages before it. Raw technical values (CSS keywords, font stacks,
file hashes, internal jargon) are replaced by plain labels. English stays the
fallback for anything without a translation.

Success means: with a Japanese account, no English UI text remains in the
Designer except server-origin names (see Out of scope), and CI fails if a new
Designer string ships without a Japanese entry.

## Decisions

- **Catalog lives in the Designer bundle**, not the Go catalog. The bundle stays
  self-contained (works in the vite harness), and the two builds don't share a
  drift test. Terms follow the dashboard's glossary (クリエイティブ, です・ます).
- **Language source: `<html lang>`.** `creative-design.html` already renders
  `<html lang="{{lang}}">` from the user's resolved language, so no server
  change is needed.
- **English text is the key**, as in `platform/internal/i18n`.
- **Translated text never becomes data.** Only what is displayed is wrapped.
  Stored values stay English: item types, field names (`headline`, `body`),
  CSS values (`horizontal-tb`), preset ids, brand-kit role names. (The
  dashboard's 2026-07-17 brand-kit regression came from breaking this rule.)

## Components

### 1. `src/i18n.ts` and `src/i18n/ja.ts`

```ts
export function t(key: string, ...args: (string | number)[]): string
```

- Resolves the language once from `document.documentElement.lang`
  (`ja`, `ja-JP` → `ja`; anything else → English).
- Looks the key up in the language's catalog; missing → the key.
- Fills `%s` / `%d` placeholders in order. Catalog values keep the same
  placeholder count.
- `ja.ts` exports `const JA: Record<string, string>`.
- A test-only hook sets the language, so unit tests don't depend on jsdom's
  `lang`.

### 2. String sweep

Every user-visible string under `src/ui/` (and the few in `src/` modules that
produce UI text, e.g. animation preset labels, toasts, confirm text) goes
through `t()`: text content, button labels, menu items, `title`, `aria-label`,
placeholders, toasts, confirms.

Strings built at module load (constant tables like the selection bar's `LABEL`
map, `ANIMATION_PRESETS[].label`) are translated **at render time**
(`t(LABEL[action])`, `t(preset.label)`), not at definition, so the table stays
the English key set.

### 3. Friendly option labels

`selectField(parent, label, value, options, onChange, …)` gains an optional
`labels?: Record<string, string>` (value → English display label, itself passed
through `t()`). Option `value`s are unchanged.

| Control | Stored values | Shown (en / ja) |
|---|---|---|
| writing | `horizontal-tb`, `vertical-rl` | Horizontal / 横書き, Vertical / 縦書き |
| direction | `ltr`, `rtl` | Left to right / 左から右, Right to left / 右から左 |
| v-align | `top`, `middle`, `bottom` | Top / 上, Middle / 中央, Bottom / 下 |
| fill mode | `fill`, `fit` | Fill (crop to box) / 枠いっぱい（切り抜き）, Fit (whole image) / 全体を表示 |
| scrim edge | `bottom`, `top`, `left`, `right`, `radial`, `radial-edge` | From bottom / 下から, From top / 上から, From left / 左から, From right / 右から, Centre / 中央, Edges / 周囲 |

Other displayed values:

- **Font select:** show a readable name. A known-stack map covers the generic
  stacks (e.g. `-apple-system, sans-serif` → System sans-serif /
  システム ゴシック); otherwise the first family in the stack, unquoted.
- **Image layer names:** an image layer whose name is a file hash shows
  Image / 画像 instead.
- **Jargon:** Fanout → Sizes / サイズ展開; Authored / Generated / Empty →
  Edited / Auto / Empty (手動 / 自動 / 未作成); Weight → File size / 容量.
  The English wording changes too, since it is the key.

### 4. Preview chrome (banner-component)

`magazine-preview.ts` labels "Advertisement", "PC", "Mobile" follow the host
page's `<html lang>` through a small inline map (広告, PC, モバイル). No
catalog or helper is shared with the Designer. The same component renders on
the publisher approval page, so those labels follow that page's language too.
This change ships in the banner bundle (its deploy republishes the banner
script).

### 5. Drift test (vitest)

`tests/i18n-drift.test.ts` scans `src/**/*.ts` for `t("…")` / `t('…')`
literal keys and asserts:

- every key has a `JA` entry;
- every `JA` entry is used (or listed in a small `DYNAMIC_KEYS` allowlist for
  keys passed as variables: `LABEL` values, preset labels, option labels);
- `%s` / `%d` counts match between key and value.

## Testing

- Unit: `t()` fallback, `ja`/`ja-JP` resolution, placeholder filling.
- Drift test as above.
- Pure label tests (the Designer's vitest runs without a DOM, and adding
  jsdom would be a new dependency): option entries keep English `value`s with
  translated labels; font display names; layer names for hashes and field
  names; fanout/weight wording.
- Manual: the vite harness gets a `?lang=ja` switch that sets
  `document.documentElement.lang`; visual pass reviewed by the user before
  commit.
- Bundle rebuilt; `node scripts/fanout.mjs --check` clean.

## Delivery

One issue (#498), one PR. Rebase onto main after #496 (Horizontal/Vertical
buttons) merges, since the sweep touches the same selection-bar labels.

## Out of scope

- Server-origin names: layout template names and descriptions from
  `/v1/layout-templates`, LLM-written text. Needs API-side localization.
- The reader's Close button: already follows the creative's content language
  (`pickCloseLabel`), which is right for an ad.
- Languages other than Japanese: the catalog map is keyed by language, so a
  third language is a new file plus one map entry.
