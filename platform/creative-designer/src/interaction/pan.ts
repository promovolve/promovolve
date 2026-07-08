// Figma-style canvas panning via CSS-transform translate. The canvas
// host has overflow:hidden and uses flex centering for the initial
// position; pan adds a translate offset on the canvas wrap (or the
// rulers' outer frame, whichever is the host's direct child) so the
// content can sit at any x,y on what feels like an infinite plane.
//
// Why transform, not native scroll: with overflow:auto the browser
// clamps scrollLeft/Top back into the valid range on layout recalc,
// which felt like a snap-back on release.
//
// Triggers, in priority order:
//   - Plain left-drag on the empty host background (the dotted-grid
//     area outside the canvas) — no modifier needed.
//   - Hold Space + drag with left mouse, anywhere.
//   - Press H to toggle a persistent Hand tool, drag anywhere (V or
//     Esc to leave the tool).
//   - Middle-mouse drag, anywhere.
//
// Cmd/Ctrl+0 recenters (resets the translate to 0,0).
//
// Why capture phase: the overlay (render/overlay.ts) binds pointerdown
// on its own root for item drag/resize/rotate. Pan needs to win when
// its conditions are met, so we listen on the canvas-host with
// `capture: true` and stopPropagation when we're taking the gesture.
// In all other cases we no-op and the overlay handles things normally.

let panTarget: HTMLElement | null = null;
let panX = 0;
let panY = 0;

function applyTransform(): void {
  if (!panTarget) return;
  panTarget.style.transform = `translate(${panX}px, ${panY}px)`;
}

export function installPan(host: HTMLElement): void {
  // The pan target is the host's direct child after all mounting is
  // done — that's the rulers' outer frame (which contains the canvas
  // wrap) when rulers are mounted, otherwise the canvas wrap itself.
  panTarget = host.firstElementChild as HTMLElement | null;
  if (!panTarget) {
    console.warn("[pan] canvas-host has no children; pan will not be active until a child appears");
  }

  let spaceDown = false;
  let handTool = false;
  let panning = false;
  let activePointer = -1;
  let startPanX = 0;
  let startPanY = 0;
  let startX = 0;
  let startY = 0;

  const updateCursor = (): void => {
    if (panning) host.style.cursor = "grabbing";
    else if (spaceDown || handTool) host.style.cursor = "grab";
    else host.style.cursor = "";
  };

  // Don't hijack space/H/V while the user is typing in an input or
  // a contenteditable text item.
  const isTextEdit = (target: EventTarget | null): boolean => {
    if (!(target instanceof HTMLElement)) return false;
    if (target.isContentEditable) return true;
    const tag = target.tagName;
    return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT";
  };

  window.addEventListener("keydown", (e) => {
    if (isTextEdit(e.target)) return;
    if ((e.metaKey || e.ctrlKey) && e.key === "0") {
      e.preventDefault();
      recenterPan();
      return;
    }
    if (e.code === "Space" && !e.repeat) {
      spaceDown = true;
      e.preventDefault();
      updateCursor();
      return;
    }
    if (e.key === "h" || e.key === "H") {
      handTool = true;
      updateCursor();
      return;
    }
    if ((e.key === "v" || e.key === "V") && !e.metaKey && !e.ctrlKey) {
      handTool = false;
      updateCursor();
      return;
    }
    if (e.key === "Escape") {
      handTool = false;
      updateCursor();
    }
  });

  window.addEventListener("keyup", (e) => {
    if (e.code === "Space") {
      spaceDown = false;
      updateCursor();
    }
  });

  window.addEventListener("blur", () => {
    spaceDown = false;
    updateCursor();
  });

  host.addEventListener("pointerdown", (e) => {
    if (!panTarget) return;
    const middleClick = e.button === 1;
    const leftClick = e.button === 0;
    const onHostBackground = leftClick && e.target === host;
    const shouldPan =
      middleClick
      || onHostBackground
      || (leftClick && (spaceDown || handTool));
    if (!shouldPan) return;
    e.stopPropagation();
    e.preventDefault();
    panning = true;
    activePointer = e.pointerId;
    startPanX = panX;
    startPanY = panY;
    startX = e.clientX;
    startY = e.clientY;
    try { host.setPointerCapture(e.pointerId); } catch { /* not all hosts support capture */ }
    updateCursor();
  }, { capture: true });

  host.addEventListener("pointermove", (e) => {
    if (!panning || e.pointerId !== activePointer) return;
    panX = startPanX + (e.clientX - startX);
    panY = startPanY + (e.clientY - startY);
    applyTransform();
  });

  const endPan = (e: PointerEvent): void => {
    if (!panning || e.pointerId !== activePointer) return;
    panning = false;
    activePointer = -1;
    try { host.releasePointerCapture(e.pointerId); } catch { /* already released */ }
    updateCursor();
  };
  host.addEventListener("pointerup", endPan);
  host.addEventListener("pointercancel", endPan);

  // Suppress middle-click auto-scroll cursor on Windows/Linux.
  host.addEventListener("auxclick", (e) => {
    if (e.button === 1) e.preventDefault();
  });
  host.addEventListener("mousedown", (e) => {
    if (e.button === 1) e.preventDefault();
  });
}

// Reset pan to the centred frame (0,0 translate). Bound to Cmd/Ctrl+0
// inside installPan; exported so other UI (e.g. a toolbar button) can
// call it too.
export function recenterPan(): void {
  panX = 0;
  panY = 0;
  applyTransform();
}
