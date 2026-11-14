// Plain labels for values the Designer stores in CSS / internal form
// (GH #498). The stored value never changes; only what's shown does.

import { t, tk } from "./i18n";

export interface OptionEntry { value: string; label: string }

/** Select options: English `value`s, translated `label`s. */
export function optionEntries(options: string[], labels?: Record<string, string>): OptionEntry[] {
  return options.map((value) => ({ value, label: labels?.[value] ? t(labels[value]!) : value }));
}

export const WRITING_LABELS: Record<string, string> = {
  "horizontal-tb": tk("Horizontal"),
  "vertical-rl": tk("Vertical"),
};
export const DIRECTION_LABELS: Record<string, string> = {
  ltr: tk("Left to right"),
  rtl: tk("Right to left"),
};
export const VALIGN_LABELS: Record<string, string> = {
  top: tk("Top"),
  middle: tk("Middle"),
  bottom: tk("Bottom"),
};
export const TEXT_ALIGN_LABELS: Record<string, string> = {
  left: tk("Align left"),
  center: tk("Align centre"),
  right: tk("Align right"),
  justify: tk("Justify"),
};
export const WEIGHT_LABELS: Record<string, string> = {
  normal: tk("Normal"),
  bold: tk("Bold"),
};
export const FILL_MODE_LABELS: Record<string, string> = {
  fill: tk("Fill (crop to box)"),
  fit: tk("Fit (whole image)"),
};
export const SCRIM_EDGE_LABELS: Record<string, string> = {
  bottom: tk("From bottom"),
  top: tk("From top"),
  left: tk("From left"),
  right: tk("From right"),
  radial: tk("Centre"),
  "radial-edge": tk("Edges"),
};
/** Page fields a text/image item can be bound to. */
export const FIELD_LABELS: Record<string, string> = {
  tag: tk("Tag"),
  headline: tk("Headline"),
  sub: tk("Subhead"),
  body: tk("Body"),
  accent: tk("Accent"),
  caption: tk("Caption"),
  img: tk("Image"),
};

const SYSTEM_STACKS: Record<string, string> = {
  "-apple-system, sans-serif": tk("System sans-serif"),
  "sans-serif": tk("System sans-serif"),
  serif: tk("System serif"),
  monospace: tk("System monospace"),
};

/** A font stack as a person would name it: the system stacks by kind,
  * anything else by its first family, unquoted. */
export function fontDisplayName(stack: string): string {
  const s = stack.trim();
  if (SYSTEM_STACKS[s]) return t(SYSTEM_STACKS[s]!);
  return (s.split(",")[0] ?? s).trim().replace(/^["']|["']$/g, "");
}

/** Uploaded assets are stored under content-hash names — not something to
  * show a person. */
export function isHashName(name: string): boolean {
  return /^[0-9a-f]{12,}(\.[a-z0-9]+)?$/i.test(name.split("?")[0]!);
}
