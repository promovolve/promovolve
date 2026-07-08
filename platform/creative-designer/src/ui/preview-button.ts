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
  // Icon-only (eye); the action name lives on the tooltip, matching
  // the icon language of the rest of the chrome.
  btn.innerHTML = `<svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M1.5 8s2.4-4.2 6.5-4.2S14.5 8 14.5 8s-2.4 4.2-6.5 4.2S1.5 8 1.5 8z"/><circle cx="8" cy="8" r="2"/></svg>`;
  btn.title = "Preview";
  btn.setAttribute("aria-label", "Preview");
  btn.style.cssText = [
    "display: inline-flex",
    "align-items: center",
    "justify-content: center",
    "width: 30px",
    "height: 28px",
    "padding: 0",
    "background: transparent",
    `color: ${tokens.ink200}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "cursor: pointer",
    "font: inherit",
    "font-size: 12px",
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
    "background: rgba(0,0,0,0.78)",
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

  // Dedicated × to dismiss the whole modal. The magazine's own CLOSE now
  // collapses the creative to the 300x250 ad (keep-frame) and keeps the
  // preview open, so it can no longer double as the modal dismiss — this
  // button (plus scrim click / Esc) is how you leave the preview.
  const closeBtn = document.createElement("button");
  closeBtn.type = "button";
  closeBtn.setAttribute("aria-label", "Close preview");
  closeBtn.textContent = "×";
  closeBtn.style.cssText = [
    "position: fixed",
    "top: 16px",
    "right: 20px",
    "z-index: 510",
    "width: 40px",
    "height: 40px",
    "border: none",
    "border-radius: 50%",
    "background: rgba(0,0,0,0.55)",
    "color: #fff",
    "font: 28px/1 system-ui, sans-serif",
    "cursor: pointer",
    "display: flex",
    "align-items: center",
    "justify-content: center",
  ].join(";");
  closeBtn.addEventListener("click", hidePreview);

  previewEl = buildPreviewEl(store);
  overlay.append(closeBtn, previewEl);
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
  // Cap the floating preview's size on large screens / give it breathing
  // room on the dim backdrop without changing the component's framing.
  preview.style.cssText = "max-width: min(900px, 92vw); width: 100%;";
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
  if (previewEl) overlay.replaceChild(fresh, previewEl);
  else overlay.appendChild(fresh);
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
