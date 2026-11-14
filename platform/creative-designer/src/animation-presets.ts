// The Designer's curated animation presets. Raw motion targets are
// hard for authors to think in; a preset is a named entrance the user
// attaches to a selected item, then tunes with a handful of curated
// knobs (distance, duration, delay, easing).
//
// Presets MATERIALIZE: picking one writes concrete MotionFrom values
// onto the item plus `animationPreset` (the id, for round-trip UI).
// The engine only ever reads the concrete values, so published
// creatives stay frozen even if these definitions evolve — the same
// immutability rule as everything else that ships.
//
// Curation over capability, deliberately: the platform's identity is
// dignified ads publishers are proud to carry. Six entrances with
// tuned defaults beat a keyframe timeline that invites abuse.

import type { MotionFrom, MotionTarget } from "./types";
import { tk } from "./i18n";

export interface AnimationPreset {
  id: string;
  label: string;
  from: MotionFrom;
  // Which curated knob scales the pose: directional presets expose
  // "distance" (rewrites dx/dy proportionally); scale presets expose
  // "intensity" (rewrites the start scale). "none" = timing knobs only.
  knob: "distance" | "intensity" | "none";
}

export const ANIMATION_PRESETS: AnimationPreset[] = [
  { id: "fade-in",    label: tk("Fade in"),
    from: { opacity: 0, duration: 0.6 }, knob: "none" },
  { id: "fade-up",    label: tk("Fade up"),
    from: { opacity: 0, dy: 5, duration: 0.6 }, knob: "distance" },
  { id: "fade-down",  label: tk("Fade down"),
    from: { opacity: 0, dy: -5, duration: 0.6 }, knob: "distance" },
  { id: "fade-left",  label: tk("From the left"),
    from: { opacity: 0, dx: -5, duration: 0.6 }, knob: "distance" },
  { id: "fade-right", label: tk("From the right"),
    from: { opacity: 0, dx: 5, duration: 0.6 }, knob: "distance" },
  { id: "rise",       label: tk("Rise"),
    from: { opacity: 0, dy: 3, scale: 0.96, duration: 0.7 }, knob: "distance" },
  { id: "pop",        label: tk("Pop"),
    from: { opacity: 0, scale: 0.85, duration: 0.45, easing: "cubic-bezier(0.34,1.56,0.64,1)" },
    knob: "intensity" },
  { id: "stamp",      label: tk("Stamp"),
    // A hanko press: arrives larger, settles onto the page, fast.
    from: { opacity: 0, scale: 1.18, duration: 0.35, easing: "cubic-bezier(0.5,0,0.2,1)" },
    knob: "intensity" },
];

export function presetById(id: string | undefined): AnimationPreset | undefined {
  return ANIMATION_PRESETS.find((p) => p.id === id);
}

// ─── Exit presets ─────────────────────────────────────────────────
//
// Curated EXITS materialize `animationTo` (the engine's end-pose
// substrate) + `animationExitPreset`. Deliberately opacity/scale only —
// no positional targets, so an exit can never point at a stale
// location after the item is dragged (the trap that killed the raw
// end-pose editor); directional exits use dx/dy OFFSETS, which ride
// the item's current position the same way entrances do. `delay` +
// afterEntrance reads as "show for (s)": how long the item stays
// fully visible after its entrance lands (or after page activation
// when there's no entrance) — no timing arithmetic for the author.

export interface ExitPreset {
  id: string;
  label: string;
  to: MotionTarget;
}

export const EXIT_PRESETS: ExitPreset[] = [
  { id: "fade-out",    label: tk("Fade out"),
    to: { opacity: 0, delay: 2, duration: 0.6, afterEntrance: true } },
  { id: "shrink-away", label: tk("Shrink away"),
    to: { opacity: 0, scale: 0.85, delay: 2, duration: 0.45, afterEntrance: true } },
  // Rise's counterpart: drifts UP and fades, gently shrinking — the
  // reverse of arriving from below.
  { id: "float-away", label: tk("Float away"),
    to: { opacity: 0, dy: -3, scale: 0.96, delay: 2, duration: 0.7, afterEntrance: true } },
];

export function exitPresetById(id: string | undefined): ExitPreset | undefined {
  return EXIT_PRESETS.find((p) => p.id === id);
}

// Curated easing menu — named curves, not bezier strings. "Smooth" is
// the engine default (omitted from the stored JSON so configs stay
// minimal and can follow the engine default).
export const EASING_MENU: Array<{ id: string; label: string; css: string | undefined }> = [
  { id: "smooth",    label: tk("Smooth"),    css: undefined },
  { id: "snappy",    label: tk("Snappy"),    css: "cubic-bezier(0.5,0,0.2,1)" },
  { id: "overshoot", label: tk("Overshoot"), css: "cubic-bezier(0.34,1.56,0.64,1)" },
  { id: "linear",    label: tk("Linear"),    css: "linear" },
];

export function easingIdFor(css: string | undefined): string {
  return EASING_MENU.find((e) => e.css === css)?.id ?? "smooth";
}

/** The preset's dominant offset magnitude — what the "distance" knob
  * reads and rewrites (preserving each axis's direction). */
export function presetDistance(from: MotionFrom): number {
  return Math.max(Math.abs(from.dx ?? 0), Math.abs(from.dy ?? 0));
}

export function withDistance(from: MotionFrom, distance: number): MotionFrom {
  const next = { ...from };
  if (from.dx !== undefined && from.dx !== 0) next.dx = Math.sign(from.dx) * distance;
  if (from.dy !== undefined && from.dy !== 0) next.dy = Math.sign(from.dy) * distance;
  return next;
}
