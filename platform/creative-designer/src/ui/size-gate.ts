// Minimum-viewport gate. The designer shell (38px menu bar + 96px
// size matrix + canvas header/foot + 260px sidebar) degrades into an
// unusable crush below ~1024×640 instead of reflowing — so rather
// than ship a broken-looking tool, cover it with an explanatory
// overlay while the window is too small. The designer stays mounted
// underneath: no state is lost, and the gate lifts the instant the
// window grows past the minimum.

import { tokens } from "./tokens";

export const MIN_W = 1024;
export const MIN_H = 640;

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
  title.textContent = "This window is too small for the designer";
  title.style.cssText = "font-size: 16px; font-weight: 600; margin: 0 0 8px;";
  const detail = document.createElement("p");
  detail.style.cssText = `font-size: 12px; color: ${tokens.ink400}; margin: 0;`;
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
        `Enlarge the window to continue editing; nothing is lost.`;
    }
  };
  update();
  window.addEventListener("resize", update);
}
