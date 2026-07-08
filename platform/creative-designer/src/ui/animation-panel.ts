// Animation panel — lives in the sidebar's "Animation" tab.
//
// Per-item motion controls: animationTo end-state (left / top /
// rotation / scale / opacity), duration, delay, easing. The add /
// remove affordance also lives here so authors don't have to
// right-click to attach a tween; the context menu still carries it
// as an alternative entry point.
//
// Mirrors mountPropsPanel's update semantics:
//   - selection / animation-presence change → rebuild fields
//   - value change on the same (idx, animationTo-shape) → patch in
//     place so focus stays put during an edit.
//
// Edit semantics match props-panel:
//   - `input` events → store.replace (live preview, no undo step)
//   - `change` events → store.commit (one undo step per edit)

import { currentItem, updateItem } from "../state";
import type { Store } from "../store";
import type { DesignerState, LayoutItem, MotionTarget } from "../types";
import { tokens } from "./tokens";

export interface AnimationPanelHandle {
  update(state: DesignerState): void;
}

interface RenderedState {
  key: string; // "<idx>|<type>|<t?>"
  setters: Record<string, (item: LayoutItem) => void>;
}

export function mountAnimationPanel(host: HTMLElement, store: Store): AnimationPanelHandle {
  const panel = document.createElement("div");
  panel.className = "cd-animation";
  panel.style.cssText = [
    "padding: 14px",
    "display: none",
  ].join(";");
  host.appendChild(panel);

  let rendered: RenderedState | null = null;

  return {
    update(state) {
      const item = currentItem(state);
      if (!item || state.selectedItemIdxs.length !== 1) {
        panel.style.display = "none";
        rendered = null;
        return;
      }
      const idx = state.selectedItemIdxs[0]!;
      panel.style.display = "block";
      const key = `${idx}|${item.type}|${item.animationTo ? "t" : ""}`;
      if (!rendered || rendered.key !== key) {
        rendered = build(panel, idx, item, store);
      } else {
        for (const fn of Object.values(rendered.setters)) fn(item);
      }
    },
  };
}

function build(panel: HTMLElement, idx: number, item: LayoutItem, store: Store): RenderedState {
  panel.innerHTML = "";
  const setters: Record<string, (item: LayoutItem) => void> = {};

  const header = document.createElement("div");
  header.style.cssText = `font-size:11px;letter-spacing:2px;color:${tokens.ink300};margin-bottom:12px;text-transform:uppercase;`;
  header.textContent = "Motion";
  panel.appendChild(header);

  if (!item.animationTo) {
    const empty = document.createElement("div");
    empty.style.cssText = `color:${tokens.ink400};font-size:11px;margin-bottom:10px;line-height:1.4;`;
    empty.textContent = "No animation on this item. Add one to tween the selection from its base state to a target pose.";
    panel.appendChild(empty);
    panel.appendChild(primaryButton("Add animation", () => {
      // Matches the context-menu "Add Animation" default so behaviour
      // is consistent regardless of entry point.
      store.commit(updateItem(store.state, idx, (it) => ({
        ...it,
        animationTo: {
          left: (it.left ?? 0) + 10,
          top: it.top ?? 0,
          duration: 0.8,
        },
      })));
    }));
    return { key: `${idx}|${item.type}|`, setters };
  }

  const target = item.animationTo;
  const setField = (key: NumericMotionKey) =>
    (v: number | null) => commit(store, idx, (it) => ({
      ...it,
      animationTo: withPhaseField(it.animationTo, key, v),
    }));

  setters.animationToLeft     = optionalNumberField(panel, "left (%)",     target.left,     setField("left"));
  setters.animationToTop      = optionalNumberField(panel, "top (%)",      target.top,      setField("top"));
  setters.animationToRotation = optionalNumberField(panel, "rotation (°)", target.rotation, setField("rotation"));
  setters.animationToScale    = optionalNumberField(panel, "scale",        target.scale,    setField("scale"));
  setters.animationToOpacity  = optionalNumberField(panel, "opacity",      target.opacity,  setField("opacity"));
  setters.animationToDuration = optionalNumberField(panel, "duration (s)", target.duration, setField("duration"));
  setters.animationToDelay    = optionalNumberField(panel, "delay (s)",    target.delay,    setField("delay"));
  setters.animationToEasing   = easingField(panel, target.easing ?? "", (v) => commit(store, idx, (it) => ({
    ...it,
    animationTo: { ...(it.animationTo ?? {}), easing: v || undefined },
  })));

  const removeWrap = document.createElement("div");
  removeWrap.style.cssText = `margin-top:14px;padding-top:12px;border-top:1px solid ${tokens.ink500};`;
  removeWrap.appendChild(dangerButton("Remove animation", () => {
    store.commit(updateItem(store.state, idx, (it) => ({ ...it, animationTo: undefined })));
  }));
  panel.appendChild(removeWrap);

  return { key: `${idx}|${item.type}|t`, setters };
}

// ─── Motion-target field helpers ──────────────────────────────────

type NumericMotionKey = Exclude<keyof MotionTarget, "easing">;

function withPhaseField(
  target: MotionTarget | undefined,
  key: NumericMotionKey,
  v: number | null,
): MotionTarget {
  const next = { ...(target ?? {}) };
  if (v === null) delete next[key];
  else next[key] = v;
  return next;
}

function commit(store: Store, idx: number, fn: (it: LayoutItem) => LayoutItem): void {
  store.commit(updateItem(store.state, idx, fn));
}

// ─── Field builders ───────────────────────────────────────────────

const INPUT_STYLE = `background:${tokens.ink900};color:${tokens.ink100};border:1px solid ${tokens.ink500};border-radius:4px;padding:4px 6px;font:inherit;`;

const EASING_OPTIONS: Array<{ value: string; label: string }> = [
  { value: "", label: "(default)" },
  { value: "linear", label: "linear" },
  { value: "ease", label: "ease" },
  { value: "ease-in", label: "ease-in" },
  { value: "ease-out", label: "ease-out" },
  { value: "ease-in-out", label: "ease-in-out" },
];

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

function optionalNumberField(
  parent: HTMLElement, label: string, value: number | undefined,
  onChange: (v: number | null) => void,
): (item: LayoutItem) => void {
  const input = document.createElement("input");
  input.type = "number";
  input.step = "0.1";
  input.placeholder = "—";
  input.value = value === undefined ? "" : String(value);
  input.style.cssText = INPUT_STYLE;
  const emit = (): void => {
    const raw = input.value.trim();
    if (raw === "") onChange(null);
    else {
      const n = Number(raw);
      if (Number.isFinite(n)) onChange(n);
    }
  };
  // Animation tweens only kick in on playback, so there's no "live
  // preview" benefit to firing on `input`; commit on `change` only.
  input.addEventListener("change", emit);
  row(parent, label, input);
  return (item) => {
    if (document.activeElement === input) return;
    const v = item.animationTo?.[phaseKeyFor(label)];
    const next = v === undefined ? "" : String(v);
    if (input.value !== next) input.value = next;
  };
}

function easingField(
  parent: HTMLElement, value: string,
  onChange: (v: string) => void,
): (item: LayoutItem) => void {
  const input = document.createElement("select");
  input.style.cssText = INPUT_STYLE;
  for (const { value: v, label } of EASING_OPTIONS) {
    const opt = document.createElement("option");
    opt.value = v;
    opt.textContent = label;
    if (v === value) opt.selected = true;
    input.appendChild(opt);
  }
  input.addEventListener("change", () => onChange(input.value));
  row(parent, "easing", input);
  return (item) => {
    if (document.activeElement === input) return;
    const v = item.animationTo?.easing ?? "";
    if (input.value !== v) input.value = v;
  };
}

function primaryButton(label: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.textContent = label;
  b.style.cssText = [
    "padding: 6px 12px",
    `background: ${tokens.amber}`,
    "color: oklch(0.12 0.04 55)",
    "border: none",
    "border-radius: 4px",
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
    "border-radius: 4px",
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

// Map the field's display label back to the animationTo property.
// Central so the DOM update path reads the same keys the write path
// writes.
function phaseKeyFor(label: string): NumericMotionKey {
  const map: Record<string, NumericMotionKey> = {
    "left (%)":     "left",
    "top (%)":      "top",
    "rotation (°)": "rotation",
    "scale":        "scale",
    "opacity":      "opacity",
    "duration (s)": "duration",
    "delay (s)":    "delay",
  };
  return map[label] ?? "left";
}
