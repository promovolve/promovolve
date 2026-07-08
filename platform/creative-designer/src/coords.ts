// Coordinate conversions. The designer works entirely in percent space
// (0..100 of the canvas wrap's width/height); the DOM gives us pixels.
// Keeping the conversion pure makes it trivial to test and trivial to
// port to a different canvas element later.

export interface Rect {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface PointPct {
  x: number; // 0..100, though clamping is caller's choice
  y: number;
}

// Pixel point (in viewport client coords) → percent point (in canvas coords).
export function clientToPct(rect: Rect, clientX: number, clientY: number): PointPct {
  return {
    x: ((clientX - rect.left) / rect.width) * 100,
    y: ((clientY - rect.top) / rect.height) * 100,
  };
}

// The inverse — percent point → client coords. Useful for anchoring a
// rotate handle to an item's center without going through the DOM.
export function pctToClient(rect: Rect, pct: PointPct): { x: number; y: number } {
  return {
    x: rect.left + (pct.x / 100) * rect.width,
    y: rect.top + (pct.y / 100) * rect.height,
  };
}
