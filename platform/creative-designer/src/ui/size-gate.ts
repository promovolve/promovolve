// Minimum-viewport gate. The shell reflows well below its design
// size — the selection bar wraps, the size-matrix strip scrolls, the
// sidebar scrolls — and stays usable down to roughly 850×480. 850, not
// the old 760: the canvas was ~376px wide at 760 (760 − 320 × 1.2), and
// the tool rail plus the narrower sidebar take (90 + 300) × 1.2 = 468px,
// so 850 keeps the work surface about as wide as before. Below that the size-matrix chips
// clip and the work surface crushes into unusability, so rather than
// ship a broken-looking tool, cover it with an explanatory overlay
// while the window is too small. The designer stays mounted
// underneath: no state is lost, and the gate lifts the instant the
// window grows past the minimum.

import { tokens } from "./tokens";

export const MIN_W = 850;
export const MIN_H = 480;

export function mountSizeGate(): void {
  const gate = document.createElement("div");
  gate.id = "cd-size-gate";
  gate.style.cssText = [
    "position: fixed",
    "inset: 0",
    "z-index: 1000",
    `background: ${tokens.ink900}`,
    `color: ${tokens.ink200}`,
    `font-family: ${tokens.sans}`,
    "display: none",
    "align-items: center",
    "justify-content: center",
    "text-align: center",
    "padding: 32px",
  ].join(";");

  const inner = document.createElement("div");
  const title = document.createElement("p");
  title.className = "cd-title";
  title.textContent = "This window is too small for the designer";
  title.style.cssText = "font-size: 16px; font-weight: 700; margin: 0 0 8px;";
  const detail = document.createElement("p");
  detail.style.cssText = `font-size: 12px; color: ${tokens.ink300}; margin: 0;`;
  inner.append(title, detail);
  gate.appendChild(inner);
  document.body.appendChild(gate);

  const update = (): void => {
    const w = window.innerWidth;
    const h = window.innerHeight;
    const tooSmall = w < MIN_W || h < MIN_H;
    gate.style.display = tooSmall ? "flex" : "none";
    if (tooSmall) {
      detail.textContent =
        `Needs at least ${MIN_W}×${MIN_H} — currently ${w}×${h}. ` +
        `Enlarge the window (or reduce browser zoom) to continue editing; nothing is lost.`;
    }
  };
  update();
  window.addEventListener("resize", update);
}
