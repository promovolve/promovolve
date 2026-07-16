// Banner Config panel. Lives at the top of the Properties tab, above
// the per-page background controls. Holds creative-wide settings
// that apply to every page and every size — currently just the
// expand-time animation; more fields (font, showTag/showSub, etc.)
// will land here as they're wired through.
//
// The dropdown is sourced from EXPAND_EFFECTS in @banner/types so
// "what the user can pick" stays in sync with "what the engine
// supports" — adding a new effect is one CSS edit in banner.ts plus
// one entry in EXPAND_EFFECTS, no panel change needed.

import { EXPAND_EFFECTS, PAPER_WEIGHTS, type ExpandAnimation, type PaperWeight } from "@banner/types";
import type { Store } from "../store";
import type { DesignerState } from "../types";
import { tokens } from "./tokens";

// Fallback for paperBackColor — keep in sync with PAPER_BASE in
// @banner paper.ts (the peeled page's default warm stock).
const DEFAULT_BACK_COLOR = "#f0e9d9";

const WEIGHT_LABELS: Record<PaperWeight, string> = {
  light:  "Light",
  medium: "Medium (default)",
  heavy:  "Heavy",
};

export interface BannerConfigPanelHandle {
  update(state: DesignerState): void;
}

const EFFECT_LABELS: Record<ExpandAnimation, string> = {
  "fade":          "Fade (default)",
  "crt-power-on":  "CRT power-on",
};

export function mountBannerConfigPanel(
  container: HTMLElement,
  store: Store
): BannerConfigPanelHandle {
  const panel = document.createElement("div");
  panel.className = "cd-banner-config";
  panel.style.cssText = "padding: 14px;";
  container.appendChild(panel);

  // (Section title "Banner" is provided by the sidebar's collapsible
  // section header — see makeSection in ui/sidebar.ts.)

  // Per-effect natural durations. Used as the placeholder so authors
  // see the actual default they'd get if they leave the field blank,
  // not just a generic "ms" hint. Source of truth for these numbers
  // is the var() fallback in EXPAND_EFFECT_CSS in banner.ts — keep
  // them in sync (a future refactor could pull both from a shared
  // constant).
  const NATURAL_DURATION: Record<ExpandAnimation, number> = {
    "fade": 400,
    "crt-power-on": 650,
  };

  // Animation row: label + select.
  const animRow = document.createElement("div");
  animRow.style.cssText = "display:flex;align-items:center;gap:8px;margin-bottom:8px;";

  const animLabel = document.createElement("label");
  animLabel.textContent = "Animation";
  animLabel.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink300};`;
  animRow.appendChild(animLabel);

  const select = document.createElement("select");
  select.style.cssText = [
    "flex: 1 1 auto",
    "min-width: 0",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "padding: 4px 6px",
    "font: inherit",
    "font-size: 11px",
  ].join(";");
  for (const name of EXPAND_EFFECTS) {
    const opt = document.createElement("option");
    opt.value = name;
    opt.textContent = EFFECT_LABELS[name];
    select.appendChild(opt);
  }
  // `change` (not `input`) so a single undo step covers the swap.
  // Use store.commit (not replace) so the choice is undoable.
  select.addEventListener("change", () => {
    const next = select.value as ExpandAnimation;
    const current = store.state.bannerConfig;
    store.commit({
      ...store.state,
      bannerConfig: { ...current, expandAnimation: next },
    });
  });
  animRow.appendChild(select);
  panel.appendChild(animRow);

  // Duration row: label + number input. Empty input = use the
  // selected effect's natural timing. The placeholder updates when
  // the effect changes so authors see the actual fallback for the
  // current pick, not a generic hint.
  const durRow = document.createElement("div");
  durRow.style.cssText = "display:flex;align-items:center;gap:8px;";

  const durLabel = document.createElement("label");
  durLabel.textContent = "Duration";
  durLabel.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink300};`;
  durRow.appendChild(durLabel);

  const durInput = document.createElement("input");
  durInput.type = "number";
  durInput.min = "50";
  durInput.max = "5000";
  durInput.step = "50";
  durInput.style.cssText = [
    "flex: 1 1 auto",
    "min-width: 0",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "padding: 4px 6px",
    "font: inherit",
    "font-size: 11px",
  ].join(";");

  const durSuffix = document.createElement("span");
  durSuffix.textContent = "ms";
  durSuffix.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink400};`;

  // change (blur / Enter) → commit one undo step. input → preview
  // would need replace+commit pattern; durations don't need that
  // continuous feedback so plain change is fine.
  // Minimum visible animation duration. Below ~50ms the human eye
  // can't perceive an animation; values like 1ms look identical to
  // "no effect" and confuse authors who assume the feature is broken.
  // HTML min="50" only constrains spinner clicks, not typed values,
  // so we enforce it explicitly here.
  const MIN_DURATION_MS = 50;
  const MAX_DURATION_MS = 5000;
  durInput.addEventListener("change", () => {
    const raw = durInput.value.trim();
    const current = store.state.bannerConfig;
    let next: number | undefined;
    if (raw === "") {
      next = undefined;
    } else {
      const parsed = parseInt(raw, 10);
      if (!Number.isFinite(parsed) || parsed <= 0) {
        next = undefined;
      } else {
        // Clamp into [MIN, MAX] and reflect the clamped value back into
        // the input so the author sees what actually got saved — beats
        // a silent rounding that makes the form lie about its state.
        next = Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, parsed));
        if (next !== parsed) {
          durInput.value = String(next);
        }
      }
    }
    if (next === current.expandDurationMs) return;
    store.commit({
      ...store.state,
      bannerConfig: { ...current, expandDurationMs: next },
    });
  });

  durRow.append(durInput, durSuffix);
  panel.appendChild(durRow);

  // Reading direction: drives the page-turn peel corner, the dog-ear
  // corner, and the nav arrows. Auto resolves to RTL for Arabic /
  // vertical-rl content; LTR/RTL force it.
  const dirRow = document.createElement("div");
  dirRow.style.cssText = "display:flex;align-items:center;gap:8px;margin-top:8px;";

  const dirLabel = document.createElement("label");
  dirLabel.textContent = "Reading";
  dirLabel.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink300};`;
  dirRow.appendChild(dirLabel);

  const dirSelect = document.createElement("select");
  dirSelect.style.cssText = [
    "flex: 1 1 auto",
    "min-width: 0",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "padding: 4px 6px",
    "font: inherit",
    "font-size: 11px",
  ].join(";");
  const DIR_LABELS: Record<string, string> = {
    auto: "Auto",
    ltr: "Left → Right",
    rtl: "Right → Left",
  };
  for (const v of ["auto", "ltr", "rtl"]) {
    const opt = document.createElement("option");
    opt.value = v;
    opt.textContent = DIR_LABELS[v];
    dirSelect.appendChild(opt);
  }
  dirSelect.addEventListener("change", () => {
    const v = dirSelect.value as "auto" | "ltr" | "rtl";
    const current = store.state.bannerConfig;
    store.commit({
      ...store.state,
      bannerConfig: { ...current, readingDirection: v },
    });
  });
  dirRow.appendChild(dirSelect);
  panel.appendChild(dirRow);

  // Shared control chrome so the two paper rows match the selects above.
  const controlCss = [
    "flex: 1 1 auto",
    "min-width: 0",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "padding: 4px 6px",
    "font: inherit",
    "font-size: 11px",
  ].join(";");

  // Paper weight: the page-peel hand-feel preset (how the reader pulls a
  // page over — pull resistance, commit point, flick, settle spring). No
  // visual change; it only affects the interactive turn in the reader.
  const weightRow = document.createElement("div");
  weightRow.style.cssText = "display:flex;align-items:center;gap:8px;margin-top:8px;";
  const weightLabel = document.createElement("label");
  weightLabel.textContent = "Paper";
  weightLabel.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink300};`;
  weightRow.appendChild(weightLabel);
  const weightSelect = document.createElement("select");
  weightSelect.style.cssText = controlCss;
  for (const w of PAPER_WEIGHTS) {
    const opt = document.createElement("option");
    opt.value = w;
    opt.textContent = WEIGHT_LABELS[w];
    weightSelect.appendChild(opt);
  }
  weightSelect.addEventListener("change", () => {
    const next = weightSelect.value as PaperWeight;
    const current = store.state.bannerConfig;
    store.commit({ ...store.state, bannerConfig: { ...current, paperWeight: next } });
  });
  weightRow.appendChild(weightSelect);
  panel.appendChild(weightRow);

  // Paper back colour: the stock revealed as a page peels (flap, dog-ear
  // tease, folded corner — one magazine, one paper). The fiber/mottle/
  // sheen texture rides on top of whatever base tone is picked. The reset
  // clears it back to the default warm stock (paperBackColor unset).
  const backRow = document.createElement("div");
  backRow.style.cssText = "display:flex;align-items:center;gap:8px;margin-top:8px;";
  const backLabel = document.createElement("label");
  backLabel.textContent = "Back";
  backLabel.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink300};`;
  backRow.appendChild(backLabel);
  const backColor = document.createElement("input");
  backColor.type = "color";
  backColor.title = "Back of the peeled page";
  backColor.style.cssText = `flex:1 1 auto;min-width:0;height:26px;padding:0;background:${tokens.ink900};border:1px solid ${tokens.ink500};border-radius:4px;cursor:pointer;`;
  backColor.addEventListener("input", () => {
    const current = store.state.bannerConfig;
    store.commit({ ...store.state, bannerConfig: { ...current, paperBackColor: backColor.value } });
  });
  backRow.appendChild(backColor);
  const backReset = document.createElement("button");
  backReset.type = "button";
  backReset.textContent = "Reset";
  backReset.title = "Back to the default warm paper stock";
  backReset.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink300};background:${tokens.ink900};border:1px solid ${tokens.ink500};border-radius:4px;padding:4px 6px;cursor:pointer;`;
  backReset.addEventListener("click", () => {
    const current = store.state.bannerConfig;
    if (current.paperBackColor === undefined) return;
    store.commit({ ...store.state, bannerConfig: { ...current, paperBackColor: undefined } });
  });
  backRow.appendChild(backReset);
  panel.appendChild(backRow);

  return {
    update(state) {
      const current = state.bannerConfig.expandAnimation;
      if (select.value !== current) {
        select.value = current;
      }
      const ms = state.bannerConfig.expandDurationMs;
      const renderedValue = ms === undefined ? "" : String(ms);
      // Avoid clobbering user's in-progress typing — only patch when
      // the actual stored value differs from what the input shows.
      if (durInput.value !== renderedValue) {
        durInput.value = renderedValue;
      }
      // Placeholder follows the selected effect's natural timing.
      durInput.placeholder = `${NATURAL_DURATION[current]} (default)`;
      const dir = state.bannerConfig.readingDirection ?? "auto";
      if (dirSelect.value !== dir) dirSelect.value = dir;
      const weight = state.bannerConfig.paperWeight ?? "medium";
      if (weightSelect.value !== weight) weightSelect.value = weight;
      // Unset back colour → show the default warm tone in the picker (it
      // can't represent "unset", so the swatch mirrors the effective look).
      const back = state.bannerConfig.paperBackColor ?? DEFAULT_BACK_COLOR;
      if (backColor.value !== back) backColor.value = back;
    },
  };
}
