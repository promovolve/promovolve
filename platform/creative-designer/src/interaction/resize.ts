// Pure resize math: given the drag direction, the original bounds at
// drag-start, and the pointer delta in percent, compute the new bounds.
// No DOM access here — the caller reads pointer coords, we compute what
// the item's new bounds should be.

export type ResizeDir = "n" | "s" | "e" | "w" | "nw" | "ne" | "se" | "sw";

export interface Bounds {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface PointDelta {
  dx: number;
  dy: number;
}

// Percent floor on the DRAGGED axis only — prevents collapsing an item
// to zero/inverted, nothing more. This must stay below the thinnest
// legitimate item (the Line tool inserts a 0.4%-tall rect): the old
// floor of 2 fattened a line 5× the moment any handle with a vertical
// component was grabbed, even with zero vertical movement — on a 3px
// line the corner handles overlap the edge ones, so that was every
// grab in practice ("line turns into a box").
const MIN_SIZE = 0.2;

// Compute new bounds for a rectangular item (text, image, rect) being
// dragged by the handle at `dir`. West and north directions adjust both
// size and origin so the opposite edge stays anchored.
export function computeResize(
  dir: ResizeDir,
  orig: Bounds,
  delta: PointDelta,
): Bounds {
  let { left, top, width, height } = orig;
  const { dx, dy } = delta;

  if (dir.includes("e")) {
    width = Math.max(MIN_SIZE, orig.width + dx);
  }
  if (dir.includes("s")) {
    height = Math.max(MIN_SIZE, orig.height + dy);
  }
  if (dir.includes("w")) {
    width = Math.max(MIN_SIZE, orig.width - dx);
    left = orig.left + (orig.width - width);
  }
  if (dir.includes("n")) {
    height = Math.max(MIN_SIZE, orig.height - dy);
    top = orig.top + (orig.height - height);
  }

  return { left, top, width, height };
}

// Circles use a single `radius` field expressed as a percent of the
// design-box's min dimension. Resizing picks whichever axis the handle
// moved by more (scaled by the design aspect) and reconstructs radius
// from it. Returns the new radius; callers still apply left/top from
// the generic `computeResize`.
export function computeCircleRadius(
  orig: Bounds,
  newBounds: Bounds,
  designAspectRatio: number, // h / w of the design box
): number {
  const diamPct = Math.max(
    Math.abs(newBounds.width),
    Math.abs(newBounds.height) / designAspectRatio,
  );
  // Matches the legacy formula: orig `round(diamPct / 2 / ratio * 10) / 10`.
  // See creative-design.html startResize circle branch.
  void orig;
  return Math.max(1, Math.round((diamPct / 2 / designAspectRatio) * 10) / 10);
}
