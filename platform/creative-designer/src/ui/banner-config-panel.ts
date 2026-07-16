// Banner Config panel. Lives at the top of the Properties tab, above
// the per-page background controls. Holds creative-wide settings that
// apply to every page and every size: reading direction and the paper
// stock (weight + back colour). There is no animation picker — the
// reader always deals its sheets in and flies them away (the kawaraban
// lifecycle), with tempo derived from the paper weight.

import { PAPER_WEIGHTS, type PaperWeight } from "@banner/types";
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

  // Reading direction: drives the page-turn peel corner, the dog-ear
  // corner, and the nav arrows. Auto resolves to RTL for Arabic /
  // vertical-rl content; LTR/RTL force it.
  const dirRow = document.createElement("div");
  dirRow.style.cssText = "display:flex;align-items:center;gap:8px;";

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
