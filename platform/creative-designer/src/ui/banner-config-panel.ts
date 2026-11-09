// Banner Config panel. Lives at the top of the Properties tab, above
// the per-page background controls. Holds creative-wide settings that
// apply to every page and every size: the entrance (the kawaraban deal
// by default, or a plain fade for advertisers who don't want the
// theatre), reading direction, and the paper stock (weight + back
// colour). Deal tempo derives from the paper weight.

import { DISSOLVE_COLS, PAPER_FEEL, PAPER_WEIGHTS, TEMPO_MAX, TEMPO_MIN, type BannerConfig, type DissolveSize, type PaperWeight } from "@banner/types";
import type { Store } from "../store";
import type { DesignerState } from "../types";
import { tokens } from "./tokens";

// Fallback for paperBackColor — keep in sync with PAPER_BASE in
// @banner paper.ts (the peeled page's default warm stock).
const DEFAULT_BACK_COLOR = "#f0e9d9";

const SQUARE_LABELS: Record<DissolveSize, string> = {
  small:  "Small",
  medium: "Medium (default)",
  large:  "Large",
};

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

  // Entrance: how the reader arrives and leaves. "deal" (default) =
  // sheets fly in on open and fly away on close; "fade" = a plain
  // cross-fade both ways. Page turns inside the reader are unaffected.
  const entRow = document.createElement("div");
  entRow.style.cssText = "display:flex;align-items:center;gap:8px;";
  const entLabel = document.createElement("label");
  entLabel.textContent = "Entrance";
  entLabel.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink300};`;
  entRow.appendChild(entLabel);
  const entSelect = document.createElement("select");
  entSelect.style.cssText = [
    "flex: 1 1 auto",
    "min-width: 0",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 2px",
    "padding: 4px 6px",
    "font: inherit",
    "font-size: 11px",
  ].join(";");
  // Options depend on the format (Fold-out: dissolve | fade); rebuilt in
  // update() when the set changes.
  let entKeys = "";
  const setEntOptions = (cfg: BannerConfig) => {
    const opts = entranceOptions(cfg);
    const keys = opts.map((o) => o.value).join();
    if (keys === entKeys) return;
    entKeys = keys;
    entSelect.replaceChildren(...opts.map((o) => {
      const opt = document.createElement("option");
      opt.value = o.value;
      opt.textContent = o.label;
      return opt;
    }));
  };
  entSelect.addEventListener("change", () => {
    const v = entSelect.value as NonNullable<BannerConfig["entrance"]>;
    const current = store.state.bannerConfig;
    store.commit({
      ...store.state,
      // The format's default is stored as absence so configs stay minimal
      // and the default can evolve without stale pins.
      bannerConfig: { ...current, entrance: v === entranceOptions(current)[0].value ? undefined : v },
    });
  });
  entRow.appendChild(entSelect);
  panel.appendChild(entRow);

  // Fold-out pixel dissolve: how big its squares are. Shown only while the
  // Fold-out plays the dissolve; medium is the default, stored as absence.
  const sqRow = document.createElement("div");
  sqRow.style.cssText = "display:flex;align-items:center;gap:8px;margin-top:8px;";
  const sqLabel = document.createElement("label");
  sqLabel.textContent = "Squares";
  sqLabel.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink300};`;
  sqRow.appendChild(sqLabel);
  const sqSelect = document.createElement("select");
  sqSelect.style.cssText = entSelect.style.cssText;
  sqSelect.title = "Size of the squares the poster dissolves in and out with";
  for (const v of Object.keys(DISSOLVE_COLS) as DissolveSize[]) {
    const opt = document.createElement("option");
    opt.value = v;
    opt.textContent = SQUARE_LABELS[v];
    sqSelect.appendChild(opt);
  }
  sqSelect.addEventListener("change", () => {
    const v = sqSelect.value as DissolveSize;
    const current = store.state.bannerConfig;
    store.commit({ ...store.state, bannerConfig: { ...current, dissolveSize: v === "medium" ? undefined : v } });
  });
  sqRow.appendChild(sqSelect);
  panel.appendChild(sqRow);

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
    "border-radius: 2px",
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
    "border-radius: 2px",
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

  // Deal/fly tempo: how fast the reader's sheets deal in on open and fly
  // away on turns/close (Fold-out: the dissolve, the fall open, the fold-up). Unset ("auto") follows the paper stock's preset
  // (light snaps, heavy is stately); the slider overrides it per
  // creative. The indicator always shows the EFFECTIVE multiplier.
  const tempoRow = document.createElement("div");
  tempoRow.style.cssText = "display:flex;align-items:center;gap:8px;margin-top:8px;";
  const tempoLabel = document.createElement("label");
  tempoLabel.textContent = "Tempo";
  tempoLabel.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink300};`;
  tempoRow.appendChild(tempoLabel);
  const tempoSlider = document.createElement("input");
  tempoSlider.type = "range";
  tempoSlider.min = String(TEMPO_MIN);
  tempoSlider.max = String(TEMPO_MAX);
  tempoSlider.step = "0.05";
  tempoSlider.title = "Deal-in / fly-away pace — 1.0 is natural, higher is slower, statelier";
  tempoSlider.style.cssText = "flex:1 1 auto;min-width:0;";
  tempoRow.appendChild(tempoSlider);
  const tempoOut = document.createElement("output");
  tempoOut.style.cssText = `flex:0 0 34px;text-align:right;font-size:11px;color:${tokens.ink100};font-variant-numeric:tabular-nums;`;
  tempoRow.appendChild(tempoOut);
  const tempoReset = document.createElement("button");
  tempoReset.type = "button";
  tempoReset.textContent = "Auto";
  tempoReset.title = "Follow the paper stock's own tempo";
  tempoReset.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink300};background:${tokens.ink900};border:1px solid ${tokens.ink500};border-radius:2px;padding:4px 6px;cursor:pointer;`;
  tempoRow.appendChild(tempoReset);
  const presetTempo = (): number =>
    PAPER_FEEL[store.state.bannerConfig.paperWeight ?? "medium"].tempo;
  tempoSlider.addEventListener("input", () => {
    const v = parseFloat(tempoSlider.value);
    const current = store.state.bannerConfig;
    store.commit({ ...store.state, bannerConfig: { ...current, tempo: v } });
  });
  tempoReset.addEventListener("click", () => {
    const current = store.state.bannerConfig;
    if (current.tempo === undefined) return;
    store.commit({ ...store.state, bannerConfig: { ...current, tempo: undefined } });
  });
  panel.appendChild(tempoRow);

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
  backColor.style.cssText = `flex:1 1 auto;min-width:0;height:26px;padding:0;background:${tokens.ink900};border:1px solid ${tokens.ink500};border-radius:2px;cursor:pointer;`;
  backColor.addEventListener("input", () => {
    const current = store.state.bannerConfig;
    store.commit({ ...store.state, bannerConfig: { ...current, paperBackColor: backColor.value } });
  });
  backRow.appendChild(backColor);
  const backReset = document.createElement("button");
  backReset.type = "button";
  backReset.textContent = "Reset";
  backReset.title = "Back to the default warm paper stock";
  backReset.style.cssText = `flex:0 0 auto;font-size:11px;color:${tokens.ink300};background:${tokens.ink900};border:1px solid ${tokens.ink500};border-radius:2px;padding:4px 6px;cursor:pointer;`;
  backReset.addEventListener("click", () => {
    const current = store.state.bannerConfig;
    if (current.paperBackColor === undefined) return;
    store.commit({ ...store.state, bannerConfig: { ...current, paperBackColor: undefined } });
  });
  backRow.appendChild(backReset);
  panel.appendChild(backRow);

  return {
    update(state) {
      setEntOptions(state.bannerConfig);
      const ent = effectiveEntrance(state.bannerConfig);
      if (entSelect.value !== ent) entSelect.value = ent;
      sqRow.style.display = ent === "dissolve" ? "flex" : "none";
      const sq = state.bannerConfig.dissolveSize ?? "medium";
      if (sqSelect.value !== sq) sqSelect.value = sq;
      const dir = state.bannerConfig.readingDirection ?? "auto";
      if (dirSelect.value !== dir) dirSelect.value = dir;
      const weight = state.bannerConfig.paperWeight ?? "medium";
      if (weightSelect.value !== weight) weightSelect.value = weight;
      // Tempo indicator: slider + number always show the EFFECTIVE
      // multiplier; the ×-suffix goes ink (focus) when overriding the stock.
      const overridden = state.bannerConfig.tempo !== undefined;
      const effTempo = overridden ? state.bannerConfig.tempo! : presetTempo();
      const slider = String(effTempo);
      if (tempoSlider.value !== slider) tempoSlider.value = slider;
      tempoOut.textContent = `${effTempo.toFixed(2)}×`;
      tempoSlider.title = state.bannerConfig.format === "foldout"
        ? "Unfold pace — 1.0 is natural, higher is slower, heavier paper"
        : "Deal-in / fly-away pace — 1.0 is natural, higher is slower, statelier";
      tempoOut.style.color = overridden ? tokens.ink100 : tokens.ink300;
      tempoReset.style.opacity = overridden ? "1" : "0.5";
      // Unset back colour → show the default warm tone in the picker (it
      // can't represent "unset", so the swatch mirrors the effective look).
      const back = state.bannerConfig.paperBackColor ?? DEFAULT_BACK_COLOR;
      if (backColor.value !== back) backColor.value = back;
    },
  };
}

/** Entrance choices per format; the first is the default (stored as absence). */
export function entranceOptions(cfg: BannerConfig): { value: "deal" | "fade" | "dissolve"; label: string }[] {
  return cfg.format === "foldout"
    ? [{ value: "dissolve", label: "Pixel dissolve (default)" }, { value: "fade", label: "Fade in / out" }]
    : [{ value: "deal", label: "Sheets fly in / out (default)" }, { value: "fade", label: "Fade in / out" }];
}
/** The entrance the format will play: "fade", else the format's default. */
export function effectiveEntrance(cfg: BannerConfig): "deal" | "fade" | "dissolve" {
  return cfg.entrance === "fade" ? "fade" : entranceOptions(cfg)[0].value;
}
