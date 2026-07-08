// Dynamic alignment guides shown during drag/resize. When a dragged
// item's edge or center aligns with another item's edge/center or
// the canvas edges/center within a small threshold, a thin line
// marks the alignment and the position snaps to it.
//
// Module-level singleton rather than passed through every interaction
// call: a single overlay layer is installed on the canvas wrap at
// boot time, and interaction handlers call showGuides / hideGuides
// during their gestures.

export interface GuideLine {
  orientation: "h" | "v";
  pos: number; // percentage (0-100) along the canvas
}

export interface Bounds {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface SnapResult {
  left: number;
  top: number;
  guides: GuideLine[];
}

const THRESHOLD = 1.5; // percent — how close before we snap + show guide
const GUIDE_COLOR = "#ff3b82";

let layer: HTMLElement | null = null;

/** Attach the guide-rendering layer to the canvas wrap. Idempotent. */
export function mountGuides(canvasWrap: HTMLElement): void {
  if (layer && layer.isConnected) return;
  layer = document.createElement("div");
  layer.className = "cd-guides";
  Object.assign(layer.style, {
    position: "absolute",
    inset: "0",
    pointerEvents: "none",
    zIndex: "1000",
  });
  canvasWrap.appendChild(layer);
}

export function hideGuides(): void {
  if (layer) layer.innerHTML = "";
}

export function showGuides(lines: GuideLine[]): void {
  if (!layer) return;
  layer.innerHTML = "";
  for (const line of lines) {
    const el = document.createElement("div");
    const shared = {
      position: "absolute",
      background: GUIDE_COLOR,
      boxShadow: `0 0 2px ${GUIDE_COLOR}`,
    } as Partial<CSSStyleDeclaration>;
    if (line.orientation === "h") {
      Object.assign(el.style, shared, {
        left: "0",
        right: "0",
        top: `${line.pos}%`,
        height: "1px",
      });
    } else {
      Object.assign(el.style, shared, {
        top: "0",
        bottom: "0",
        left: `${line.pos}%`,
        width: "1px",
      });
    }
    layer.appendChild(el);
  }
}

/**
 * Compute snap adjustment for a dragged item against a set of other
 * items and the canvas. Returns the new left/top (snapped if any
 * axis was close enough to an alignment target) and the guide lines
 * that triggered the snap.
 */
export function computeSnap(dragged: Bounds, others: Bounds[]): SnapResult {
  const guides: GuideLine[] = [];

  // Vertical guide candidates (x positions): canvas edges + center,
  // plus each other-item's left/center/right.
  const xTargets: number[] = [0, 50, 100];
  for (const o of others) {
    xTargets.push(o.left, o.left + o.width / 2, o.left + o.width);
  }
  // Horizontal guide candidates (y positions).
  const yTargets: number[] = [0, 50, 100];
  for (const o of others) {
    yTargets.push(o.top, o.top + o.height / 2, o.top + o.height);
  }

  // The dragged item's anchor points to test against each target.
  const xAnchors = [
    dragged.left,
    dragged.left + dragged.width / 2,
    dragged.left + dragged.width,
  ];
  const yAnchors = [
    dragged.top,
    dragged.top + dragged.height / 2,
    dragged.top + dragged.height,
  ];

  let bestDx = 0;
  let bestDxDist = Infinity;
  let bestDxPos: number | null = null;
  for (const a of xAnchors) {
    for (const t of xTargets) {
      const d = Math.abs(a - t);
      if (d < THRESHOLD && d < bestDxDist) {
        bestDxDist = d;
        bestDx = t - a;
        bestDxPos = t;
      }
    }
  }

  let bestDy = 0;
  let bestDyDist = Infinity;
  let bestDyPos: number | null = null;
  for (const a of yAnchors) {
    for (const t of yTargets) {
      const d = Math.abs(a - t);
      if (d < THRESHOLD && d < bestDyDist) {
        bestDyDist = d;
        bestDy = t - a;
        bestDyPos = t;
      }
    }
  }

  if (bestDxPos !== null) guides.push({ orientation: "v", pos: bestDxPos });
  if (bestDyPos !== null) guides.push({ orientation: "h", pos: bestDyPos });

  return {
    left: dragged.left + bestDx,
    top: dragged.top + bestDy,
    guides,
  };
}
