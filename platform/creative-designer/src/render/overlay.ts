// Interactive overlay sitting on top of the canvas. Responsibilities:
//   1. An invisible hitbox per layout item so users can click to
//      select and drag items that live inside the banner's Shadow
//      DOM (which we can't reach from outside).
//   2. A selection frame around selected items, with resize + rotate
//      handles when exactly one item is selected.
//   3. A dashed motion ghost at animationTo for the solo-selected
//      item, plus its own rotate + scale handles and a dashed path
//      line from start-center to ghost-center.
//   4. Pointer dispatch for all the above, plus marquee + context
//      menu.
//
// The overlay is a child of the canvas wrap and shares its aspect
// ratio, so percent coordinates map directly to its inner box.

import { isMultiPage } from "../modes";
import { startDrag } from "../interaction/drag";
import { selectionBounds, startGroupResize } from "../interaction/group-resize-gesture";
import { startGroupRotate } from "../interaction/group-rotate-gesture";
import { startResize } from "../interaction/resize-gesture";
import { startRotate } from "../interaction/rotate-gesture";
import { startMarquee } from "../interaction/marquee";
import { startMotionDrag } from "../interaction/motion-drag";
import { startMotionRotate } from "../interaction/motion-rotate-gesture";
import { startMotionScale } from "../interaction/motion-scale-gesture";
import type { ResizeDir } from "../interaction/resize";
import {
  bringForward,
  bringToFront,
  currentItem,
  currentLayout,
  deleteSelection,
  groupSelection,
  hasSelection,
  isSelected,
  selectItem,
  sendBackward,
  sendToBack,
  setItemContent,
  setSelection,
  toggleSelect,
  ungroupSelection,
  updateItem,
} from "../state";
import type { Store } from "../store";
import type { DesignerState, LayoutItem } from "../types";
import type { Rect } from "../coords";
import { itemBoundsPct, type BoundsPct } from "../geometry";
import { copy, duplicate, hasClipboard, paste } from "../interaction/clipboard";
import { openContextMenu, type MenuEntry } from "../ui/context-menu";
import { tokens } from "../ui/tokens";
import { packTextItemHeight } from "./canvas";

const SELECTION_COLOR = tokens.amber;

// Click-to-fit: clicking a text box resizes the BOX to hug its text ("fit
// box to text"). The editor canvas doesn't auto-pack, so selecting the box
// is where the author applies it. Deferred a frame so the freshly-rendered
// element is measurable. packTextItemHeight no-ops when the box already
// fits, so re-clicking to select doesn't spam undo. (Font shrink-to-fit
// still runs in the reader/delivery as an overflow safety net.)
function fitTextItem(store: Store, idx: number, fitWidth = false): void {
  requestAnimationFrame(() => packTextItemHeight(store, idx, fitWidth));
}

export interface OverlayHandle {
  update(state: DesignerState): void;
}

export function mountOverlay(canvasWrap: HTMLElement, store: Store): OverlayHandle {
  const root = document.createElement("div");
  root.className = "cd-overlay";
  root.style.cssText = [
    "position: absolute",
    "inset: 0",
    "pointer-events: none", // children opt back in
  ].join(";");
  canvasWrap.appendChild(root);

  const canvasRect = (): Rect => {
    const r = canvasWrap.getBoundingClientRect();
    return { left: r.left, top: r.top, width: r.width, height: r.height };
  };

  root.addEventListener("pointerdown", (e) => {
    const target = e.target as HTMLElement;
    const state = store.state;
    const singleSelected = state.selectedItemIdxs.length === 1 ? state.selectedItemIdxs[0]! : null;

    // Motion-ghost rotation handle — takes precedence over the ghost
    // itself so clicking the handle doesn't also fire a drag.
    const motionRotate = target.closest<HTMLElement>("[data-cd-motion-rotate-idx]");
    if (motionRotate) {
      const idx = Number(motionRotate.dataset.cdMotionRotateIdx);
      startMotionRotate({ e, idx, store, canvasRect });
      return;
    }
    const motionScale = target.closest<HTMLElement>("[data-cd-motion-scale-idx]");
    if (motionScale) {
      const idx = Number(motionScale.dataset.cdMotionScaleIdx);
      startMotionScale({ e, idx, store, canvasRect });
      return;
    }
    // Motion ghost — drag the end-position marker to reposition the
    // tween target. Single-selection only; takes precedence over the
    // item hitbox it overlaps with.
    const ghost = target.closest<HTMLElement>("[data-cd-ghost-idx]");
    if (ghost) {
      const idx = Number(ghost.dataset.cdGhostIdx);
      startMotionDrag({ e, idx, store, canvasRect });
      return;
    }

    // Group rotate + resize handles take priority over the solo
    // versions when 2+ items are selected.
    if (target.classList.contains("cd-group-rotate") && state.selectedItemIdxs.length > 1) {
      startGroupRotate({ e, store, canvasRect });
      return;
    }
    const groupResizeHandle = target.closest<HTMLElement>("[data-cd-group-dir]");
    if (groupResizeHandle && state.selectedItemIdxs.length > 1) {
      const dir = groupResizeHandle.dataset.cdGroupDir as ResizeDir;
      startGroupResize({ e, dir, store, canvasRect });
      return;
    }

    // Solo handles.
    if (target.classList.contains("cd-rotate") && singleSelected !== null) {
      startRotate({ e, idx: singleSelected, store, canvasRect });
      return;
    }
    const resizeHandle = target.closest<HTMLElement>("[data-cd-dir]");
    if (resizeHandle && singleSelected !== null) {
      const dir = resizeHandle.dataset.cdDir as ResizeDir;
      startResize({ e, dir, idx: singleSelected, store, canvasRect });
      return;
    }

    // Item hitbox — Figma-style:
    //   plain click                      → replace selection with [idx]
    //   shift/cmd+click on selected      → remove idx from selection
    //   shift/cmd+click on unselected    → append idx to selection
    // After selection settles, start a drag if any item is selected.
    const hitbox = target.closest<HTMLElement>("[data-cd-idx]");
    if (hitbox) {
      const idx = Number(hitbox.dataset.cdIdx);
      const additive = e.shiftKey || e.metaKey || e.ctrlKey;
      const alreadySoloSelected =
        !additive &&
        state.selectedItemIdxs.length === 1 &&
        state.selectedItemIdxs[0] === idx;
      const items = currentLayout(state);
      const item = items[idx];

      // PowerPoint/Slides: a second pointerdown on an already-selected
      // text item starts a "maybe-edit" gesture. If the user drags
      // before releasing, we treat it as a move; if they release in
      // place, we enter text-edit mode at the click point.
      if (alreadySoloSelected && item?.type === "text" && isMultiPage(state.mode)) {
        const startX = e.clientX;
        const startY = e.clientY;
        let moved = false;
        let dragStarted = false;
        const onMove = (ev: PointerEvent): void => {
          if (moved) return;
          if (Math.hypot(ev.clientX - startX, ev.clientY - startY) > 3) {
            moved = true;
            cleanup();
            dragStarted = true;
            startDrag({ e, store, canvasRect });
          }
        };
        const onUp = (ev: PointerEvent): void => {
          cleanup();
          if (!moved && !dragStarted) {
            startInlineTextEdit(canvasWrap, root, idx, item, store, ev.clientX, ev.clientY);
          }
        };
        const cleanup = (): void => {
          window.removeEventListener("pointermove", onMove);
          window.removeEventListener("pointerup", onUp);
          window.removeEventListener("pointercancel", cleanup);
        };
        window.addEventListener("pointermove", onMove);
        window.addEventListener("pointerup", onUp);
        window.addEventListener("pointercancel", cleanup);
        return;
      }

      if (additive) {
        store.replace(toggleSelect(state, idx));
      } else if (!isSelected(state, idx)) {
        store.replace(setSelection(state, [idx]));
        // (fit-to-box is driven by selection change in handle.update)
      }
      // Selecting a component on the canvas surfaces its Properties (tab +
      // section) — see the listener in ui/sidebar.ts. Canvas-only, so
      // clicking layer rows keeps the Layers list open.
      document.dispatchEvent(new CustomEvent("cd:component-selected"));
      // Drag moves the entire current selection together.
      startDrag({ e, store, canvasRect });
      return;
    }
    // Empty space — start a marquee selection. Additive if Shift/Cmd
    // is held; otherwise the existing selection is cleared by the
    // marquee's first pointermove (the baseSelection parameter).
    const additive = e.shiftKey || e.metaKey || e.ctrlKey;
    if (!additive) {
      store.replace(selectItem(state, null));
      // Clicking the creative's background = selecting the page background:
      // surface its section (mirror of cd:component-selected above). Without
      // this, deselection leaves the Properties section open with an empty
      // body — the whole sidebar reads as collapsed.
      document.dispatchEvent(new CustomEvent("cd:background-selected"));
    }
    startMarquee({ e, overlayRoot: root, store, canvasRect, additive });
  });

  // Double-click a text item → edit its actual text element inside the
  // banner's shadow DOM in place (Google Slides / PowerPoint style),
  // so the caret sits in the real rendered text at the real font size
  // and alignment — no floating overlay to go out of sync.
  root.addEventListener("dblclick", (e) => {
    const target = e.target as HTMLElement;
    const hitbox = target.closest<HTMLElement>("[data-cd-idx]");
    if (!hitbox) return;
    const idx = Number(hitbox.dataset.cdIdx);
    const item = currentLayout(store.state)[idx];
    if (!item || item.type !== "text") return;
    // Field-bound content stays synced from the expanded view (the single
    // source every size renders), so double-clicking it in a size does
    // nothing — untick "synced" in Properties to override first. LOCAL
    // text (no field — e.g. dropped straight onto a size) and already-
    // overridden items exist only here, so they edit in place anywhere.
    if (!isMultiPage(store.state.mode)) {
      const bound = item as { field?: string; text?: string };
      const synced = !!bound.field && (bound.text == null || bound.text === "");
      if (synced) return;
    }
    e.preventDefault();
    e.stopPropagation();
    startInlineTextEdit(canvasWrap, root, idx, item, store, e.clientX, e.clientY);
  });

  root.addEventListener("contextmenu", (e) => {
    const target = e.target as HTMLElement;
    const hitbox = target.closest<HTMLElement>("[data-cd-idx]");
    if (hitbox) {
      // Right-click on an unselected item selects it so the menu
      // acts on what the user pointed at.
      const idx = Number(hitbox.dataset.cdIdx);
      if (!isSelected(store.state, idx)) {
        store.replace(setSelection(store.state, [idx]));
      }
    }
    e.preventDefault();
    openContextMenu(e.clientX, e.clientY, buildMenu(store));
  });

  // Fit-the-box-to-text fires when a text item BECOMES the single
  // selection — driven by selection state, not the pointer event, so it
  // isn't swallowed by the click→edit-mode path (clicking an already-
  // selected text box enters inline edit and never reaches the select
  // branch). Keyed by (page, mode, idx) so re-selecting the same item
  // (e.g. entering edit mode) doesn't re-fit and spam undo.
  let lastFitKey: string | null = null;
  const handle: OverlayHandle = {
    update(state) {
      const sel = state.selectedItemIdxs.length === 1 ? state.selectedItemIdxs[0]! : null;
      const selItem = sel !== null ? currentLayout(state)[sel] : null;
      if (sel !== null && selItem?.type === "text") {
        const key = `${state.pageIdx}|${state.mode.key}|${sel}`;
        if (key !== lastFitKey) {
          lastFitKey = key;
          fitTextItem(store, sel);
        }
      } else {
        lastFitKey = null;
      }
      renderOverlay(root, state);
    },
  };
  renderOverlay(root, store.state);
  return handle;
}

function renderOverlay(root: HTMLElement, state: DesignerState): void {
  root.innerHTML = "";
  // Full-canvas background catcher, BEHIND every hitbox. The overlay root
  // is pointer-events:none so the creative shows through — which means a
  // click on EMPTY canvas passed straight through and never reached the
  // pointerdown handler, so clicking off a component never deselected it
  // (and marquee / locked-item fall-through never fired either). This
  // transparent layer receives those clicks: e.target is the catcher,
  // which matches no hitbox/handle, so the handler's "empty space" branch
  // runs (selectItem(null) → deselect, then marquee). Appended first so
  // item hitboxes paint and take clicks on top of it. Hidden along with
  // the overlay during inline text edit (root display:none).
  const bg = document.createElement("div");
  bg.dataset.cdBg = "1";
  bg.style.cssText = "position:absolute;inset:0;pointer-events:auto";
  root.appendChild(bg);
  const items = currentLayout(state);
  // Group-bbox handles render BEHIND items in z-order — append the
  // group overlay first so individual item outlines paint on top of
  // its dashed frame. The handles themselves stay clickable because
  // they sit outside the bbox edges.
  if (state.selectedItemIdxs.length > 1) {
    renderGroupOverlay(root, items, state.selectedItemIdxs);
  }
  items.forEach((item, idx) => {
    // Hidden items aren't on the canvas — skip their hitbox so they
    // can't be selected, dragged, or covered by the layer badge.
    // They remain in the layers panel (that's the only entry point
    // to un-hide them).
    if ((item as LayoutItem & { hidden?: boolean }).hidden) return;

    const locked = (item as LayoutItem & { locked?: boolean }).locked === true;
    const bounds = itemBoundsPct(item);
    const hit = document.createElement("div");
    hit.dataset.cdIdx = String(idx);
    hit.style.cssText = [
      "position: absolute",
      `left: ${bounds.left}%`,
      `top: ${bounds.top}%`,
      `width: ${bounds.width}%`,
      `height: ${bounds.height}%`,
      // Locked items are visual-only: clicks fall through to whatever
      // is behind them (usually the canvas for marquee). The Layers
      // panel is the only way to interact with a locked item.
      locked ? "pointer-events: none" : "pointer-events: auto",
      locked ? "cursor: default" : "cursor: move",
      item.rotation ? `transform: rotate(${item.rotation}deg)` : "",
      "transform-origin: center",
    ].filter(Boolean).join(";");
    if (isSelected(state, idx)) {
      hit.style.outline = `1.5px solid ${SELECTION_COLOR}`;
      hit.style.outlineOffset = "-1px";
      // Solo selection: handles on the item itself. Multi-selection:
      // handles live on a separate group-bbox overlay rendered after
      // the item loop. Locked items never get handles.
      if (state.selectedItemIdxs.length === 1 && !locked) renderHandles(hit);
    }
    // Layer-order badge: 1 = topmost, N = bottom of stack. Gives users
    // a quick visual inventory without a separate layers panel.
    hit.appendChild(renderBadge(items.length - idx, isSelected(state, idx)));
    root.appendChild(hit);

    // Motion ghost: if this item has animationTo and is selected,
    // render a faded duplicate at the end position plus a dashed line
    // between the two centers. The ghost is a draggable hit target
    // (data-cd-ghost-idx). Skipped on locked items — the ghost would
    // be draggable and that contradicts the lock.
    if (item.animationTo && isSelected(state, idx) && state.selectedItemIdxs.length === 1 && !locked) {
      renderMotionGhost(root, item, bounds, idx);
    }
  });
}

function renderMotionGhost(
  root: HTMLElement,
  item: LayoutItem,
  startBounds: BoundsPct,
  idx: number,
): void {
  const to = item.animationTo;
  if (!to) return;

  const endBounds: BoundsPct = {
    left: to.left ?? startBounds.left,
    top: to.top ?? startBounds.top,
    width: startBounds.width,
    height: startBounds.height,
  };

  // Dashed-outline ghost at end position. Applies end-rotation,
  // end-scale, and end-opacity so the ghost visually previews the
  // final state. Scale is baked into width/height (not applied via
  // transform:scale) so the dashed stroke stays a constant 1.5px
  // regardless of how much the item grows/shrinks — otherwise the
  // outline scales with the box and looks thicker at scale > 1,
  // thinner at scale < 1.
  const endRotation = to.rotation ?? item.rotation ?? 0;
  const endScale = to.scale ?? 1;
  const endOpacity = to.opacity ?? 1;
  const scaledWidth = endBounds.width * endScale;
  const scaledHeight = endBounds.height * endScale;
  const scaledLeft = endBounds.left + endBounds.width / 2 - scaledWidth / 2;
  const scaledTop = endBounds.top + endBounds.height / 2 - scaledHeight / 2;

  const ghost = document.createElement("div");
  ghost.dataset.cdGhostIdx = String(idx);
  ghost.style.cssText = [
    "position: absolute",
    `left: ${scaledLeft}%`,
    `top: ${scaledTop}%`,
    `width: ${scaledWidth}%`,
    `height: ${scaledHeight}%`,
    `outline: 1.5px dashed ${SELECTION_COLOR}`,
    "outline-offset: -1px",
    `background: rgba(59,130,246,${0.08 * endOpacity})`,
    "pointer-events: auto",
    "cursor: move",
    endRotation ? `transform: rotate(${endRotation}deg)` : "",
    "transform-origin: center",
    `opacity: ${endOpacity}`,
  ].filter(Boolean).join(";");

  // Rotation grip above the ghost — click+drag to set animationTo.rotation.
  const rot = document.createElement("div");
  rot.dataset.cdMotionRotateIdx = String(idx);
  rot.style.cssText = [
    "position: absolute",
    "left: 50%",
    "top: -30px",
    "width: 14px",
    "height: 14px",
    "margin-left: -7px",
    `background: ${tokens.ink800}`,
    `border: 1.5px dashed ${SELECTION_COLOR}`,
    "border-radius: 50%",
    "box-sizing: border-box",
    "pointer-events: auto",
    "cursor: grab",
  ].join(";");
  const tether = document.createElement("div");
  tether.style.cssText = [
    "position: absolute",
    "left: 50%",
    "top: -22px",
    "width: 1px",
    "height: 16px",
    `background: ${SELECTION_COLOR}`,
    "opacity: 0.6",
    "pointer-events: none",
  ].join(";");
  // Scale grip at the SE corner of the ghost. Drag outward → bigger
  // end-scale; inward → smaller. Stores as animationTo.scale.
  const scaleHandle = document.createElement("div");
  scaleHandle.dataset.cdMotionScaleIdx = String(idx);
  scaleHandle.style.cssText = [
    "position: absolute",
    "right: -6px",
    "bottom: -6px",
    "width: 10px",
    "height: 10px",
    `background: ${tokens.ink800}`,
    `border: 1.5px dashed ${SELECTION_COLOR}`,
    "border-radius: 2px",
    "box-sizing: border-box",
    "pointer-events: auto",
    "cursor: nwse-resize",
  ].join(";");
  ghost.append(tether, rot, scaleHandle);
  root.appendChild(ghost);

  // Motion path: thin blue line from start center to end center.
  const x1 = startBounds.left + startBounds.width / 2;
  const y1 = startBounds.top + startBounds.height / 2;
  const x2 = endBounds.left + endBounds.width / 2;
  const y2 = endBounds.top + endBounds.height / 2;
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("style", "position:absolute;inset:0;width:100%;height:100%;pointer-events:none;");
  svg.setAttribute("viewBox", "0 0 100 100");
  svg.setAttribute("preserveAspectRatio", "none");
  const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
  line.setAttribute("x1", String(x1));
  line.setAttribute("y1", String(y1));
  line.setAttribute("x2", String(x2));
  line.setAttribute("y2", String(y2));
  // Set via CSS (not the presentation attribute) so the themeable
  // var(--cd-amber) in SELECTION_COLOR resolves — var() is inert in
  // SVG presentation attributes.
  line.style.stroke = SELECTION_COLOR;
  line.setAttribute("stroke-width", "0.2");
  line.setAttribute("stroke-dasharray", "1 1");
  line.setAttribute("vector-effect", "non-scaling-stroke");
  svg.appendChild(line);
  root.appendChild(svg);
}

function renderBadge(n: number, selected: boolean): HTMLElement {
  const badge = document.createElement("div");
  badge.textContent = String(n);
  badge.style.cssText = [
    "position: absolute",
    "top: -8px",
    "right: -8px",
    "min-width: 18px",
    "height: 18px",
    "padding: 0 4px",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    `background: ${selected ? SELECTION_COLOR : tokens.ink800}`,
    `color: ${tokens.ink100}`,
    `border: 1px solid ${selected ? SELECTION_COLOR : tokens.ink500}`,
    "border-radius: 10px",
    "font-size: 10px",
    "font-weight: 500",
    "line-height: 1",
    "pointer-events: none",
    "user-select: none",
    "box-shadow: 0 1px 2px rgba(0,0,0,0.4)",
  ].join(";");
  return badge;
}

// In-place editing inside the banner's shadow DOM (Google Slides /
// PowerPoint style). Makes the actual rendered text node
// contenteditable — the caret appears in the real text at the real
// font size. While editing we:
//   - suspend the canvas subscriber so a sibling state change can't
//     re-render the banner and wipe the editable node,
//   - flip overlay to pointer-events:none so clicks stay on the text,
//   - move the cursor to the click point (or to the end if invoked
//     from the props panel).
function startInlineTextEdit(
  canvasWrap: HTMLElement,
  root: HTMLElement,
  idx: number,
  item: LayoutItem & { type: "text" },
  store: Store,
  caretClientX?: number,
  caretClientY?: number,
): void {
  const banner = canvasWrap.querySelector<HTMLElement & { shadowRoot: ShadowRoot | null }>(
    "expandable-magazine-banner",
  );
  const shadow = banner?.shadowRoot;
  if (!shadow) return;
  const el = shadow.querySelector<HTMLElement>(`[data-layout-idx="${idx}"]`);
  if (!el || el.isContentEditable) return;

  const originalText = item.text ?? "";
  const prevDisplay = root.style.display;
  const prevUserSelect = el.style.userSelect;
  const prevCursor = el.style.cursor;
  const prevOutline = el.style.outline;
  const prevHeight = el.style.height;
  const prevOverflow = el.style.overflow;
  const prevWidth = el.style.width;
  const prevMaxWidth = el.style.maxWidth;
  const prevFontSize = el.style.fontSize;

  el.contentEditable = "true";
  el.style.userSelect = "text";
  el.style.cursor = "text";
  el.style.outline = "1px dashed rgba(59,130,246,0.7)";
  el.style.outlineOffset = "2px";
  // Never clip while editing. A stamped/auto-measured height carries
  // overflow:hidden (render-collapsed/layout-item), so in a box that's
  // too narrow the wrapped text would be hidden as you type — you'd
  // enter text blind. Let the element grow during the edit; the
  // post-commit re-measure (textRemeasureIndices → measureTextHeights)
  // stamps the correct height when the edit lands. Restored on cancel.
  el.style.height = "auto";
  el.style.overflow = "visible";
  // Widen-while-typing: edit at the AUTHORED font size and let the box grow
  // with the content (max-content) up to the canvas edge (max-width = the
  // space from the box's left to the right edge). So the orange box widens
  // live as you type instead of wrapping at the old width; only once the
  // text is longer than one canvas-width line does it wrap and grow taller —
  // all at full font size. The blur re-fit (packTextItemHeight) then stamps
  // the same width/height. Restored on cancel.
  el.style.fontSize = `${item.fontSize ?? 5}cqmax`;
  el.style.width = "max-content";
  el.style.maxWidth = `${100 - (item.left ?? 0)}%`;
  // Hide the overlay completely — its hitboxes' pointer-events: auto
  // would otherwise override a parent pointer-events:none and steal
  // clicks from the editable element.
  root.style.display = "none";

  // Freeze the banner against state-driven re-renders while editing —
  // otherwise a sibling subscriber could setAttribute("pages", …) and
  // wipe the contenteditable mid-keystroke (that's what broke last time).
  const unsub = freezeCanvas(store, banner!);

  el.focus();
  placeCaret(shadow, el, caretClientX, caretClientY);

  let done = false;
  const finish = (commit: boolean): void => {
    if (done) return;
    done = true;
    // Trim ALL trailing whitespace (spaces + blank lines), not just one
    // newline — stray trailing whitespace inflates the measured box so it
    // no longer hugs the text. fitTextItem() below then re-fits to it.
    const text = el.innerText.replace(/\s+$/, "");
    el.contentEditable = "inherit";
    el.style.userSelect = prevUserSelect;
    el.style.cursor = prevCursor;
    el.style.outline = prevOutline;
    el.style.outlineOffset = "";
    el.style.height = prevHeight;
    el.style.overflow = prevOverflow;
    el.style.width = prevWidth;
    el.style.maxWidth = prevMaxWidth;
    el.style.fontSize = prevFontSize;
    root.style.display = prevDisplay;
    el.removeEventListener("blur", onBlur);
    el.removeEventListener("keydown", onKey);
    unsub();
    if (commit) {
      if (text !== originalText) {
        // Field-bound text edited in the expanded master writes the shared
        // page field (syncs every size); a size edit bakes a local override.
        store.commit(setItemContent(store.state, idx, text, "text"));
      }
      // Losing focus fits the box to the now-current text so it hugs the
      // content (clicking outside → box fit to text). Pass fitWidth so a
      // single-line / under-filled box also shrinks its WIDTH to the text
      // (drops the empty space + any trimmed trailing whitespace).
      // packTextItemHeight no-ops when the box already fits, so no undo spam.
      fitTextItem(store, idx, true);
    } else {
      el.textContent = originalText;
    }
  };
  const onBlur = (): void => finish(true);
  const onKey = (e: KeyboardEvent): void => {
    if (e.key === "Escape") {
      e.preventDefault();
      el.blur();
      finish(false);
    } else if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      el.blur();
      finish(true);
    }
    e.stopPropagation();
  };
  el.addEventListener("blur", onBlur);
  el.addEventListener("keydown", onKey);
}

// Freeze the banner's pages attribute for the duration of the edit by
// snapshotting it and restoring if anything reassigns it. Without this,
// canvas.update would overwrite pages on any sibling state change,
// which re-renders the shadow DOM and wipes the editable element.
function freezeCanvas(store: Store, banner: HTMLElement): () => void {
  const saved = banner.getAttribute("pages");
  const unsubscribe = store.subscribe(() => {
    if (banner.getAttribute("pages") !== saved) {
      if (saved === null) banner.removeAttribute("pages");
      else banner.setAttribute("pages", saved);
    }
  });
  return unsubscribe;
}

// Try to place the caret at the click point using caretPositionFromPoint
// / caretRangeFromPoint. Falls back to placing it at the end.
function placeCaret(
  shadow: ShadowRoot,
  el: HTMLElement,
  x: number | undefined,
  y: number | undefined,
): void {
  const sel = window.getSelection();
  if (!sel) return;
  let range: Range | null = null;
  if (x !== undefined && y !== undefined) {
    const doc = shadow as unknown as { caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null };
    const pos = doc.caretPositionFromPoint?.(x, y);
    if (pos) {
      range = document.createRange();
      range.setStart(pos.offsetNode, pos.offset);
      range.collapse(true);
    } else if (document.caretRangeFromPoint) {
      range = document.caretRangeFromPoint(x, y);
    }
  }
  if (!range) {
    range = document.createRange();
    range.selectNodeContents(el);
    range.collapse(false);
  }
  sel.removeAllRanges();
  sel.addRange(range);
}

function buildMenu(store: Store): MenuEntry[] {
  const hasSel = hasSelection(store.state);
  const modKey = navigator.platform.includes("Mac") ? "⌘" : "Ctrl";
  const soloIdx = store.state.selectedItemIdxs.length === 1 ? store.state.selectedItemIdxs[0]! : -1;
  const solo = soloIdx >= 0 ? currentItem(store.state) : null;
  const hasAnim = !!solo?.animationTo;
  const selCount = store.state.selectedItemIdxs.length;
  const layoutNow = currentLayout(store.state);
  const anyGrouped = store.state.selectedItemIdxs.some(
    (i) => !!(layoutNow[i] as (typeof layoutNow[number]) & { groupId?: string })?.groupId,
  );
  return [
    {
      label: "Bring to Front",
      shortcut: "}",
      disabled: !hasSel,
      onSelect: () => store.commit(bringToFront(store.state)),
    },
    {
      label: "Bring Forward",
      shortcut: "]",
      disabled: !hasSel,
      onSelect: () => store.commit(bringForward(store.state)),
    },
    {
      label: "Send Backward",
      shortcut: "[",
      disabled: !hasSel,
      onSelect: () => store.commit(sendBackward(store.state)),
    },
    {
      label: "Send to Back",
      shortcut: "{",
      disabled: !hasSel,
      onSelect: () => store.commit(sendToBack(store.state)),
    },
    "---",
    {
      label: "Group",
      shortcut: `${modKey}G`,
      disabled: selCount < 2,
      onSelect: () => store.commit(groupSelection(store.state)),
    },
    {
      label: "Ungroup",
      shortcut: `${modKey}⇧G`,
      disabled: !anyGrouped,
      onSelect: () => store.commit(ungroupSelection(store.state)),
    },
    "---",
    {
      label: hasAnim ? "Remove Animation" : "Add Animation",
      disabled: !solo,
      onSelect: () => {
        if (!solo) return;
        if (hasAnim) {
          store.commit(updateItem(store.state, soloIdx, (it) => ({ ...it, animationTo: undefined })));
        } else {
          store.commit(updateItem(store.state, soloIdx, (it) => ({
            ...it,
            animationTo: {
              left: (it.left ?? 0) + 10,
              top: it.top ?? 0,
              duration: 0.8,
            },
          })));
        }
      },
    },
    "---",
    {
      label: "Duplicate",
      shortcut: `${modKey}D`,
      disabled: !hasSel,
      onSelect: () => duplicate(store),
    },
    {
      label: "Copy",
      shortcut: `${modKey}C`,
      disabled: !hasSel,
      onSelect: () => copy(store),
    },
    {
      label: "Paste",
      shortcut: `${modKey}V`,
      disabled: !hasClipboard(),
      onSelect: () => paste(store),
    },
    "---",
    {
      label: "Delete",
      shortcut: "⌫",
      disabled: !hasSel,
      onSelect: () => store.commit(deleteSelection(store.state)),
    },
  ];
}

const RESIZE_DIRS: ResizeDir[] = ["nw", "n", "ne", "e", "se", "s", "sw", "w"];

function renderGroupOverlay(root: HTMLElement, items: LayoutItem[], idxs: number[]): void {
  const bounds = selectionBounds(items, idxs);
  if (!bounds || bounds.width <= 0 || bounds.height <= 0) return;

  const frame = document.createElement("div");
  frame.style.cssText = [
    "position: absolute",
    `left: ${bounds.left}%`,
    `top: ${bounds.top}%`,
    `width: ${bounds.width}%`,
    `height: ${bounds.height}%`,
    `outline: 1.5px dashed ${SELECTION_COLOR}`,
    "outline-offset: -1px",
    "pointer-events: none", // handles opt back in below
  ].join(";");

  // 8 resize handles, same visual language as the solo set but with
  // a distinct data-attr so the dispatcher routes to the group gesture.
  for (const dir of RESIZE_DIRS) {
    const h = document.createElement("div");
    h.dataset.cdGroupDir = dir;
    h.style.cssText = [
      "position: absolute",
      "width: 10px",
      "height: 10px",
      "background: #fff",
      `border: 1.5px solid ${SELECTION_COLOR}`,
      "border-radius: 2px",
      "box-sizing: border-box",
      "pointer-events: auto",
      `cursor: ${dir}-resize`,
      ...handlePosition(dir),
    ].join(";");
    frame.appendChild(h);
  }
  // Rotation tether + handle.
  const tether = document.createElement("div");
  tether.style.cssText = [
    "position: absolute",
    "left: 50%",
    "top: -22px",
    "width: 1px",
    "height: 16px",
    `background: ${SELECTION_COLOR}`,
    "pointer-events: none",
  ].join(";");
  const rot = document.createElement("div");
  rot.className = "cd-group-rotate";
  rot.style.cssText = [
    "position: absolute",
    "left: 50%",
    "top: -30px",
    "width: 14px",
    "height: 14px",
    "margin-left: -7px",
    "background: #fff",
    `border: 1.5px solid ${SELECTION_COLOR}`,
    "border-radius: 50%",
    "box-sizing: border-box",
    "pointer-events: auto",
    "cursor: grab",
  ].join(";");
  frame.append(tether, rot);
  root.appendChild(frame);
}

function renderHandles(hit: HTMLElement): void {
  // 8 resize handles around the frame.
  for (const dir of RESIZE_DIRS) {
    const h = document.createElement("div");
    h.dataset.cdDir = dir;
    h.style.cssText = [
      "position: absolute",
      "width: 10px",
      "height: 10px",
      "background: #fff",
      `border: 1.5px solid ${SELECTION_COLOR}`,
      "border-radius: 2px",
      "box-sizing: border-box",
      "pointer-events: auto",
      `cursor: ${dir}-resize`,
      ...handlePosition(dir),
    ].join(";");
    hit.appendChild(h);
  }
  // Rotate tether + handle above the frame.
  const tether = document.createElement("div");
  tether.style.cssText = [
    "position: absolute",
    "left: 50%",
    "top: -22px",
    "width: 1px",
    "height: 16px",
    `background: ${SELECTION_COLOR}`,
    "pointer-events: none",
  ].join(";");
  const rot = document.createElement("div");
  rot.className = "cd-rotate";
  rot.style.cssText = [
    "position: absolute",
    "left: 50%",
    "top: -30px",
    "width: 14px",
    "height: 14px",
    "margin-left: -7px",
    "background: #fff",
    `border: 1.5px solid ${SELECTION_COLOR}`,
    "border-radius: 50%",
    "box-sizing: border-box",
    "pointer-events: auto",
    "cursor: grab",
  ].join(";");
  hit.append(tether, rot);
}

function handlePosition(dir: ResizeDir): string[] {
  // Handles are 10px squares. Positioning offsets pull them half outside
  // the frame so the center sits on the edge/corner.
  const OUT = "-6px";
  const MID_X = "calc(50% - 5px)";
  const MID_Y = "calc(50% - 5px)";
  switch (dir) {
    case "nw": return [`top: ${OUT}`, `left: ${OUT}`];
    case "n":  return [`top: ${OUT}`, `left: ${MID_X}`];
    case "ne": return [`top: ${OUT}`, `right: ${OUT}`];
    case "e":  return [`top: ${MID_Y}`, `right: ${OUT}`];
    case "se": return [`bottom: ${OUT}`, `right: ${OUT}`];
    case "s":  return [`bottom: ${OUT}`, `left: ${MID_X}`];
    case "sw": return [`bottom: ${OUT}`, `left: ${OUT}`];
    case "w":  return [`top: ${MID_Y}`, `left: ${OUT}`];
  }
}

