// Animation panel — lives in the sidebar's "Animation" tab.
//
// Preset-first motion: raw targets are hard to think in, so the author
// picks a named ENTRANCE preset ("Fade up", "Stamp", …) that attaches
// to the selected item, then tunes a handful of curated knobs —
// distance/intensity, duration, delay, easing (a named menu, not
// bezier strings). Picking a preset MATERIALIZES concrete MotionFrom
// values onto the item plus `animationPreset` for round-trip display;
// hand-tuned knobs simply update the materialized values ("modified").
//
// Multi-select applies one preset across the selection with an
// auto-stagger (delay += step per item, selection order), which is how
// real pages get choreographed without arithmetic.
//
// The raw animationTo end-pose editor was REMOVED (2026-07): absolute
// target coordinates silently detach when the item is dragged, and
// post-settle drift cuts against the curated-motion stance. The engine
// still plays animationTo (published creatives are frozen), so an item
// that already carries one gets a notice + remove button — never
// invisible state.
//
// Edit semantics match props-panel: `change` events → store.commit
// (one undo step per edit). ▶ Replay re-fires entrances on the canvas
// component (edit mode suppresses the automatic players).

import { ANIMATION_PRESETS, EASING_MENU, EXIT_PRESETS, easingIdFor, exitPresetById, presetById, presetDistance, withDistance } from "../animation-presets";
import { currentItem, updateItem } from "../state";
import type { Store } from "../store";
import type { DesignerState, LayoutItem, MotionFrom } from "../types";
import { tokens } from "./tokens";

export interface AnimationPanelHandle {
  update(state: DesignerState): void;
}

interface RenderedState {
  key: string;
  setters: Array<(item: LayoutItem) => void>;
}

export function mountAnimationPanel(host: HTMLElement, store: Store): AnimationPanelHandle {
  const panel = document.createElement("div");
  panel.className = "cd-animation";
  panel.style.cssText = ["padding: 14px", "display: none"].join(";");
  host.appendChild(panel);

  let rendered: RenderedState | null = null;

  return {
    update(state) {
      if (state.selectedItemIdxs.length === 0) {
        panel.style.display = "none";
        rendered = null;
        return;
      }
      panel.style.display = "block";
      if (state.selectedItemIdxs.length > 1) {
        const key = `multi|${state.selectedItemIdxs.join(",")}`;
        if (!rendered || rendered.key !== key) rendered = buildMulti(panel, state, store);
        return;
      }
      const item = currentItem(state);
      if (!item) {
        panel.style.display = "none";
        rendered = null;
        return;
      }
      const idx = state.selectedItemIdxs[0]!;
      const key = `${idx}|${item.type}|${item.animationPreset ?? ""}|${item.animationExitPreset ?? ""}|${item.animationFrom ? "f" : ""}|${item.animationTo ? "t" : ""}`;
      if (!rendered || rendered.key !== key) {
        rendered = build(panel, idx, item, store, key);
      } else {
        for (const fn of rendered.setters) fn(item);
      }
    },
  };
}

// ─── Single selection ─────────────────────────────────────────────

function build(panel: HTMLElement, idx: number, item: LayoutItem, store: Store, key: string): RenderedState {
  panel.innerHTML = "";
  const setters: Array<(item: LayoutItem) => void> = [];

  header(panel, "Entrance");

  // Preset picker. "None" clears the entrance (and its preset tag);
  // anything else materializes the preset's MotionFrom.
  const presetSelect = select(panel, "Preset", [
    { value: "", label: "None" },
    ...ANIMATION_PRESETS.map((p) => ({ value: p.id, label: p.label })),
  ], item.animationPreset ?? "", (v) => {
    commit(store, idx, (it) => {
      if (v === "") return { ...it, animationFrom: undefined, animationPreset: undefined };
      const preset = presetById(v)!;
      return { ...it, animationFrom: { ...preset.from }, animationPreset: preset.id };
    });
  });
  setters.push((it) => {
    if (document.activeElement === presetSelect) return;
    const v = it.animationPreset ?? "";
    if (presetSelect.value !== v) presetSelect.value = v;
  });

  const from = item.animationFrom;
  const preset = presetById(item.animationPreset);
  if (from) {
    // Curated knobs — which ones show depends on the preset family.
    if (preset?.knob === "distance") {
      const dist = numberField(panel, "Distance (%)", presetDistance(from), 0.5, (v) =>
        commit(store, idx, (it) => ({
          ...it,
          animationFrom: it.animationFrom ? withDistance(it.animationFrom, Math.max(0, v)) : it.animationFrom,
        })));
      setters.push((it) => dist(it.animationFrom ? presetDistance(it.animationFrom) : 0));
    }
    if (preset?.knob === "intensity") {
      const inten = numberField(panel, "Start scale", from.scale ?? 1, 0.01, (v) =>
        commit(store, idx, (it) => ({
          ...it,
          animationFrom: { ...(it.animationFrom ?? {}), scale: v },
        })));
      setters.push((it) => inten(it.animationFrom?.scale ?? 1));
    }
    const dur = numberField(panel, "Duration (s)", from.duration ?? 0.6, 0.05, (v) =>
      commit(store, idx, (it) => ({
        ...it,
        animationFrom: { ...(it.animationFrom ?? {}), duration: Math.max(0.05, v) },
      })));
    setters.push((it) => dur(it.animationFrom?.duration ?? 0.6));
    const del = numberField(panel, "Delay (s)", from.delay ?? 0, 0.05, (v) =>
      commit(store, idx, (it) => ({
        ...it,
        animationFrom: { ...(it.animationFrom ?? {}), delay: Math.max(0, v) || undefined },
      })));
    setters.push((it) => del(it.animationFrom?.delay ?? 0));
    const ease = select(panel, "Easing", EASING_MENU.map((e) => ({ value: e.id, label: e.label })),
      easingIdFor(from.easing), (v) =>
        commit(store, idx, (it) => ({
          ...it,
          animationFrom: { ...(it.animationFrom ?? {}), easing: EASING_MENU.find((e) => e.id === v)?.css },
        })));
    setters.push((it) => {
      if (document.activeElement === ease) return;
      const v = easingIdFor(it.animationFrom?.easing);
      if (ease.value !== v) ease.value = v;
    });
  }

  // Exit — curated, materializes animationTo (opacity/scale only, so
  // it can't detach when the item moves the way raw end poses could).
  const exitWrap = document.createElement("div");
  exitWrap.style.cssText = `margin-top:14px;padding-top:12px;border-top:1px solid ${tokens.ink500};`;
  panel.appendChild(exitWrap);
  header(exitWrap, "Exit");
  const exitSelect = select(exitWrap, "Preset", [
    { value: "", label: "None" },
    ...EXIT_PRESETS.map((p) => ({ value: p.id, label: p.label })),
  ], item.animationExitPreset ?? "", (v) => {
    commit(store, idx, (it) => {
      if (v === "") return { ...it, animationTo: undefined, animationExitPreset: undefined };
      const preset = exitPresetById(v)!;
      return { ...it, animationTo: { ...preset.to }, animationExitPreset: preset.id };
    });
  });
  setters.push((it) => {
    if (document.activeElement === exitSelect) return;
    const v = it.animationExitPreset ?? "";
    if (exitSelect.value !== v) exitSelect.value = v;
  });
  if (item.animationExitPreset && item.animationTo) {
    const after = numberField(exitWrap, "Show for (s)", item.animationTo.delay ?? 0, 0.1, (v) =>
      commit(store, idx, (it) => ({
        ...it,
        animationTo: { ...(it.animationTo ?? {}), delay: Math.max(0, v) },
      })));
    setters.push((it) => after(it.animationTo?.delay ?? 0));
    const edur = numberField(exitWrap, "Duration (s)", item.animationTo.duration ?? 0.6, 0.05, (v) =>
      commit(store, idx, (it) => ({
        ...it,
        animationTo: { ...(it.animationTo ?? {}), duration: Math.max(0.05, v) },
      })));
    setters.push((it) => edur(it.animationTo?.duration ?? 0.6));
    const eease = select(exitWrap, "Easing", EASING_MENU.map((e) => ({ value: e.id, label: e.label })),
      easingIdFor(item.animationTo.easing), (v) =>
        commit(store, idx, (it) => ({
          ...it,
          animationTo: { ...(it.animationTo ?? {}), easing: EASING_MENU.find((e) => e.id === v)?.css },
        })));
    setters.push((it) => {
      if (document.activeElement === eease) return;
      const v = easingIdFor(it.animationTo?.easing);
      if (eease.value !== v) eease.value = v;
    });
  }

  // Replay previews the WHOLE choreography (entrance + exit), so it
  // must exist whenever either does.
  if (item.animationFrom || item.animationTo) panel.appendChild(replayButton());

  // Items from before the raw end-pose editor was removed may still
  // carry a positional tween (no exit preset tag). Surface it — it
  // plays in delivery, so it must be visible and removable here.
  // Picking an Exit preset above overwrites it (a clean migration).
  if (item.animationTo && !item.animationExitPreset) {
    const legacy = document.createElement("div");
    legacy.style.cssText = `margin-top:14px;padding-top:12px;border-top:1px solid ${tokens.ink500};`;
    const note = document.createElement("div");
    note.style.cssText = `color:${tokens.ink300};font-size:11px;line-height:1.4;margin-bottom:8px;`;
    note.textContent = "This item has an end-pose tween (a legacy feature): after entering, it moves to a fixed position. ▶ Replay previews it.";
    legacy.appendChild(note);
    legacy.appendChild(dangerButton("Remove end-pose tween", () => {
      store.commit(updateItem(store.state, idx, (it) => ({ ...it, animationTo: undefined })));
    }));
    panel.appendChild(legacy);
  }

  return { key, setters };
}

// ─── Multi selection: one preset, staggered ───────────────────────

function buildMulti(panel: HTMLElement, state: DesignerState, store: Store): RenderedState {
  panel.innerHTML = "";
  const idxs = [...state.selectedItemIdxs];

  header(panel, `Entrance — ${idxs.length} items`);

  const hint = document.createElement("div");
  hint.style.cssText = `color:${tokens.ink300};font-size:11px;margin-bottom:10px;line-height:1.4;`;
  hint.textContent = "Apply one entrance across the selection; each item starts a step later, in selection order.";
  panel.appendChild(hint);

  let presetId = ANIMATION_PRESETS[0]!.id;
  const presetSelect = select(panel, "Preset", [
    { value: "", label: "None (clear)" },
    ...ANIMATION_PRESETS.map((p) => ({ value: p.id, label: p.label })),
  ], presetId, (v) => { presetId = v; });

  let stagger = 0.08;
  numberField(panel, "Stagger (s)", stagger, 0.01, (v) => { stagger = Math.max(0, v); });

  const applyWrap = document.createElement("div");
  applyWrap.style.cssText = "margin-top:10px;display:flex;gap:8px;align-items:center;";
  applyWrap.appendChild(primaryButton("Apply to selection", () => {
    const chosen = presetById(presetSelect.value);
    let s = store.state;
    idxs.forEach((idx, i) => {
      s = updateItem(s, idx, (it) => {
        if (!chosen) return { ...it, animationFrom: undefined, animationPreset: undefined };
        const from: MotionFrom = { ...chosen.from };
        const delay = i * stagger;
        if (delay > 0) from.delay = Math.round(delay * 100) / 100;
        return { ...it, animationFrom: from, animationPreset: chosen.id };
      });
    });
    store.commit(s);
  }));
  applyWrap.appendChild(replayButton());
  panel.appendChild(applyWrap);

  return { key: `multi|${idxs.join(",")}`, setters: [] };
}

// ─── Replay ───────────────────────────────────────────────────────

// The canvas hosts the real banner component in edit mode; entrances
// are suppressed there, so the ▶ asks the component to re-fire them
// once. (State changes re-render the canvas before this runs — the
// nodes always carry the latest materialized values.)
function replayButton(): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = "▶ Replay";
  b.title = "Play the entrances on the canvas";
  b.style.cssText = [
    "margin-top: 10px",
    "padding: 5px 10px",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 2px",
    "font: inherit",
    "font-size: 11px",
    "cursor: pointer",
  ].join(";");
  b.addEventListener("click", () => {
    const el = document.querySelector('expandable-magazine-banner[mode="edit"]') as
      (HTMLElement & { _replayItemEntrances?: () => void }) | null;
    el?._replayItemEntrances?.();
  });
  return b;
}

// ─── Shared plumbing ──────────────────────────────────────────────

function commit(store: Store, idx: number, fn: (it: LayoutItem) => LayoutItem): void {
  store.commit(updateItem(store.state, idx, fn));
}

const INPUT_STYLE = `background:${tokens.ink900};color:${tokens.ink100};border:1px solid ${tokens.ink500};border-radius:2px;padding:4px 6px;font:inherit;`;

function header(parent: HTMLElement, text: string): void {
  const h = document.createElement("div");
  h.style.cssText = `font-size:11px;letter-spacing:2px;color:${tokens.ink300};margin-bottom:12px;text-transform:uppercase;`;
  h.textContent = text;
  parent.appendChild(h);
}

function row(parent: HTMLElement, label: string, input: HTMLElement): void {
  const wrap = document.createElement("label");
  wrap.style.cssText = "display:flex;align-items:center;gap:8px;margin-bottom:6px;";
  const lbl = document.createElement("span");
  lbl.textContent = label;
  lbl.style.cssText = `flex:0 0 80px;color:${tokens.ink300};font-size:11px;`;
  (input.style as CSSStyleDeclaration).cssText += ";flex:1;";
  wrap.append(lbl, input);
  parent.appendChild(wrap);
}

function select(
  parent: HTMLElement, label: string,
  options: Array<{ value: string; label: string }>,
  value: string, onChange: (v: string) => void,
): HTMLSelectElement {
  const input = document.createElement("select");
  input.style.cssText = INPUT_STYLE;
  for (const { value: v, label: l } of options) {
    const opt = document.createElement("option");
    opt.value = v;
    opt.textContent = l;
    if (v === value) opt.selected = true;
    input.appendChild(opt);
  }
  input.addEventListener("change", () => onChange(input.value));
  row(parent, label, input);
  return input;
}

/** Required numeric knob — always has a value; returns a DOM updater. */
function numberField(
  parent: HTMLElement, label: string, value: number, step: number,
  onChange: (v: number) => void,
): (v: number) => void {
  const input = document.createElement("input");
  input.type = "number";
  input.step = String(step);
  input.value = String(value);
  input.style.cssText = INPUT_STYLE;
  input.addEventListener("change", () => {
    const n = Number(input.value);
    if (Number.isFinite(n)) onChange(n);
  });
  row(parent, label, input);
  return (v) => {
    if (document.activeElement === input) return;
    const next = String(v);
    if (input.value !== next) input.value = next;
  };
}

function primaryButton(label: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = label;
  b.style.cssText = [
    "padding: 6px 12px",
    `background: ${tokens.ink100}`,
    `color: ${tokens.ink900}`,
    "border: none",
    "border-radius: 2px",
    "font: inherit",
    "font-size: 12px",
    "font-weight: 500",
    "cursor: pointer",
  ].join(";");
  b.addEventListener("click", onClick);
  return b;
}

function dangerButton(label: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = label;
  b.style.cssText = [
    "padding: 5px 10px",
    "background: transparent",
    `color: ${tokens.err}`,
    `border: 1px solid ${tokens.err}`,
    "border-radius: 2px",
    "font: inherit",
    "font-size: 11px",
    "cursor: pointer",
    "transition: background .12s, color .12s",
  ].join(";");
  b.addEventListener("mouseenter", () => {
    b.style.background = tokens.err;
    b.style.color = tokens.ink100;
  });
  b.addEventListener("mouseleave", () => {
    b.style.background = "transparent";
    b.style.color = tokens.err;
  });
  b.addEventListener("click", onClick);
  return b;
}
