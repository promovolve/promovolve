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

import { EXPAND_EFFECTS, type ExpandAnimation } from "@banner/types";
import type { Store } from "../store";
import type { DesignerState } from "../types";
import { tokens } from "./tokens";

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
    },
  };
}
