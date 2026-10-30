// Preview — the publisher-approval preview, in the designer: a dim
// overlay with a PC / Mobile toggle, both variants rendered inside
// CONTAINED frames (preview-frame="1" keeps the expanded magazine in
// the box instead of taking over the viewport — PC in a 16:9 card,
// Mobile in a phone bezel). It opens COLLAPSED, showing the creative
// embedded in a faux newspaper page as a real in-page ad placement;
// clicking the ad expands the reader over the dimmed page, simulating
// production delivery.
//
// The overlay is built ONCE at designer startup and parked invisible —
// opacity:0 (NOT display:none, which would strip its layout and break
// the collapsed render's container-relative sizing) + pointer-events:none
// so clicks fall through to the designer. It's kept current by rebuilding
// (while hidden, debounced) whenever the creative changes, so Preview is
// always a flash-free fade-in of the latest creative.

import type { Store } from "../store";
import { tokens } from "./tokens";

// The shared <magazine-preview> custom element exposes replayEntrance()
// so the host can play the creative's open effect when it fades in.
type PreviewElement = HTMLElement & { replayEntrance?: () => void };

let overlay: HTMLDivElement | null = null;
let previewEl: PreviewElement | null = null;
let storeRef: Store | null = null;
let isOpen = false;
let refreshTimer: ReturnType<typeof setTimeout> | null = null;

export function mountPreviewButton(
  container: HTMLElement,
  store: Store,
): HTMLElement {
  storeRef = store;
  const btn = document.createElement("button");
  // Eye plus "Preview"; the word drops when the top bar runs short.
  btn.innerHTML = `<svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M1.5 8s2.4-4.2 6.5-4.2S14.5 8 14.5 8s-2.4 4.2-6.5 4.2S1.5 8 1.5 8z"/><circle cx="8" cy="8" r="2"/></svg><span data-cd-compact="4">Preview</span>`;
  btn.title = "Preview";
  btn.setAttribute("aria-label", "Preview");
  btn.style.cssText = [
    "display: inline-flex",
    "align-items: center",
    "justify-content: center",
    "gap: 5px",
    "height: 26px",
    "padding: 0 9px",
    "background: transparent",
    `color: ${tokens.ink200}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 2px",
    "cursor: pointer",
    "font: inherit",
    "font-size: 11px",
    "transition: background .12s, color .12s, border-color .12s",
  ].join(";");
  btn.addEventListener("mouseenter", () => {
    btn.style.background = tokens.ink700;
    btn.style.color = tokens.ink100;
  });
  btn.addEventListener("mouseleave", () => {
    btn.style.background = "transparent";
    btn.style.color = tokens.ink200;
  });
  btn.addEventListener("click", showPreview);
  container.appendChild(btn);

  // Build the warm, invisible preview now (designer start) and keep it
  // in sync with edits so it's ready the instant Preview is clicked.
  buildPersistentPreview(store);
  store.subscribe(() => {
    // Edits only land while the modal is closed (it covers the canvas),
    // so rebuild the hidden preview on a short debounce — its collapsed
    // render lands invisibly, so by click time it shows the latest
    // creative embedded in the page and opening is a flash-free fade-in.
    if (isOpen) return;
    if (refreshTimer) clearTimeout(refreshTimer);
    refreshTimer = setTimeout(() => {
      refreshTimer = null;
      refreshPreview();
    }, 300);
  });

  return btn;
}

function buildPersistentPreview(store: Store): void {
  // The preview is the SHARED <magazine-preview> component — the exact
  // same element the publisher approval page renders (defined in the
  // banner bundle the designer already loads). We only supply the modal
  // chrome (dim backdrop + close button) it floats in.
  overlay = document.createElement("div");
  overlay.style.cssText = [
    "position: fixed",
    "inset: 0",
    "z-index: 500",
    "background: rgba(0,0,0,0.88)",
    "display: flex",
    "align-items: center",
    "justify-content: center",
    // Parked invisible but laid out — see the file header. Fades in on
    // Preview click; clicks fall through to the designer until then.
    "opacity: 0",
    "pointer-events: none",
    "transition: opacity .15s ease",
  ].join(";");
  // inert (not just pointer-events) so the invisible-but-laid-out overlay
  // is out of tab order and the screen-reader tree while parked. inert
  // doesn't affect layout, so the collapsed render still gets its
  // container size while parked; we clear it on show.
  overlay.inert = true;

  overlay.addEventListener("click", (e) => {
    if (e.target === overlay) hidePreview();
  });

  // The preview sits in a card like the designer's other modals, with
  // a labelled Close: the magazine's own CLOSE collapses the creative to
  // the in-page ad (keep-frame) and keeps the preview open, so it can't
  // double as the modal dismiss. Scrim click and Esc also close it.
  const card = document.createElement("div");
  card.style.cssText = [
    "display: flex",
    "flex-direction: column",
    "gap: 12px",
    "width: min(932px, 94vw)",
    "max-height: calc(100vh - 32px)",
    "overflow: auto",
    "box-sizing: border-box",
    "padding: 16px",
    `background: ${tokens.ink800}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 2px",
    `color: ${tokens.ink100}`,
    `font-family: ${tokens.sans}`,
    "font-size: 12px",
  ].join(";");

  const header = document.createElement("div");
  header.style.cssText = "display:flex;align-items:center;gap:8px;";
  const title = document.createElement("div");
  title.textContent = "Preview";
  title.style.cssText = "flex:1;font-weight:600;font-size:13px;";
  const closeBtn = document.createElement("button");
  closeBtn.type = "button";
  closeBtn.textContent = "Close";
  closeBtn.setAttribute("aria-label", "Close preview");
  closeBtn.style.cssText = [
    "padding: 5px 12px",
    "border-radius: 2px",
    "font: inherit",
    "font-size: 12px",
    "cursor: pointer",
    "background: transparent",
    `color: ${tokens.ink200}`,
    `border: 1px solid ${tokens.ink500}`,
  ].join(";");
  closeBtn.addEventListener("click", hidePreview);
  header.append(title, closeBtn);

  previewEl = buildPreviewEl(store);
  card.append(header, previewEl);
  overlay.appendChild(card);
  document.body.appendChild(overlay);
}

function buildPreviewEl(store: Store): HTMLElement {
  const { pages, mode, bannerConfig } = store.state;
  const landingUrl = window.__DESIGNER__?.landingUrl ?? "";

  const preview = document.createElement("magazine-preview");
  preview.setAttribute("pages", JSON.stringify(pages));
  preview.setAttribute("config", JSON.stringify(bannerConfig));
  if (landingUrl) preview.setAttribute("landing-url", landingUrl);
  // Default to the device the author is currently editing.
  preview.setAttribute("default-mode", mode.key === "mobile" ? "mobile" : "pc");
  preview.style.cssText = [
    "width: 100%",
    // The shared element's PC / Mobile toggle, in the designer's ink:
    // the selected device is an ink-filled segment.
    "--mp-toggle-radius: 2px",
    `--mp-toggle-border: ${tokens.ink500}`,
    `--mp-toggle-fg: ${tokens.ink200}`,
    `--mp-toggle-active-border: ${tokens.ink100}`,
    `--mp-toggle-active-bg: ${tokens.ink100}`,
    `--mp-toggle-active-fg: ${tokens.ink900}`,
  ].join(";");
  // start-collapsed: open showing the creative EMBEDDED in the faux
  // newspaper page (a real in-page ad placement) rather than auto-expanding
  // — the preview simulates production delivery. Click the ad to expand the
  // reader over the dimmed page.
  preview.setAttribute("start-collapsed", "1");
  // keep-frame: the magazine's CLOSE collapses the creative back to the
  // embedded on-page ad (same as the publisher approval pane) and the
  // preview STAYS open — click the ad to re-expand. So we do NOT wire
  // magazine-collapse to hidePreview anymore. The whole modal is dismissed
  // via the scrim click or Esc (both still wired in showPreview/onKey).
  preview.setAttribute("keep-frame", "1");
  return preview;
}

/** Replace the (hidden) preview with a fresh one off the current store —
 *  re-renders its collapsed (embedded-ad) state. No-op while open (the
 *  modal covers the canvas, so nothing changes under it). */
function refreshPreview(): void {
  if (!overlay || isOpen) return;
  const fresh = buildPreviewEl(storeRef!);
  previewEl?.replaceWith(fresh);
  previewEl = fresh;
}

function showPreview(): void {
  if (!overlay) return;
  // Pure fade-in — the parked copy is already built (kept current by the
  // debounced/deferred refresh while closed) and rests COLLAPSED, showing
  // the creative embedded in the faux newspaper page. The author clicks the
  // embedded ad to expand the reader, exactly like a live page. Drop any
  // pending background refresh first.
  if (refreshTimer) {
    clearTimeout(refreshTimer);
    refreshTimer = null;
  }
  isOpen = true;
  overlay.inert = false;
  overlay.style.opacity = "1";
  overlay.style.pointerEvents = "auto";
  window.addEventListener("keydown", onKey);
}

function hidePreview(): void {
  if (!overlay) return;
  isOpen = false;
  overlay.inert = true;
  overlay.style.opacity = "0";
  overlay.style.pointerEvents = "none";
  window.removeEventListener("keydown", onKey);
  // Rebuild fresh for next time AFTER the fade-out finishes. Rebuilding
  // now would swap a new render into the still-visible overlay — a flash
  // "left behind" during close. Deferring past the .15s fade keeps it
  // invisible. (If the author expanded the reader before closing, the
  // rebuild also resets it back to the embedded-ad resting state.)
  if (refreshTimer) clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => {
    refreshTimer = null;
    refreshPreview();
  }, 200);
}

function onKey(e: KeyboardEvent): void {
  if (e.key === "Escape") {
    e.preventDefault();
    hidePreview();
  }
}
