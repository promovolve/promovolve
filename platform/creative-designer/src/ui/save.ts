// Save flow. Mirrors the inline editor's hidden-form POST to
// /advertiser/creatives/save so the server handler is unchanged.
// Submitting a form (rather than fetch) lets the server drive the
// post-save redirect to the creatives list page.
//
// Two flavors:
//   - Save Draft — stores pagesJson with status=Draft, advertiser can
//     come back and keep editing. No banner render, no Gemini verify.
//   - Publish    — current behavior: flips status to Active, kicks off
//     CreativeProcessor (render + verify), makes it deliverable.

import { parseAspect } from "../math";
import { MODES } from "../modes";
import { fanoutStatus } from "../state/fanout";
import type { Store } from "../store";
import type { DesignerContext } from "../types";
import { tokens } from "./tokens";

export function mountSaveButtons(container: HTMLElement, store: Store, ctx: DesignerContext): HTMLElement {
  const group = document.createElement("div");
  group.style.cssText = "display:flex;gap:8px;";

  const draftBtn = document.createElement("button");
  draftBtn.type = "button";
  // Icon-only (floppy = save). Action names live on the tooltips.
  draftBtn.innerHTML = `<svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 2.5h8L13 4.5v9a.5.5 0 0 1-.5.5h-9a.5.5 0 0 1-.5-.5v-11a.5.5 0 0 1 .5-.5z" transform="translate(0,-0.5)"/><path d="M5 2v3.5h5V2"/><rect x="5" y="9" width="6" height="4.5"/></svg>`;
  draftBtn.title = "Save Draft — save a draft you can come back to. Not delivered.";
  draftBtn.setAttribute("aria-label", "Save Draft");
  draftBtn.style.cssText = [
    "display: inline-flex",
    "align-items: center",
    "justify-content: center",
    "width: 30px",
    "height: 28px",
    "padding: 0",
    "background: transparent",
    `color: ${tokens.ink100}`,
    `border: 1px solid ${tokens.ink500}`,
    "border-radius: 4px",
    "cursor: pointer",
    "font: inherit",
  ].join(";");
  draftBtn.addEventListener("click", () => submitSave(store, ctx, /*draft=*/ true));

  const publishBtn = document.createElement("button");
  publishBtn.type = "button";
  // Icon-only (paper plane = ship it); stays amber so the primary
  // action keeps its visual weight without the text.
  publishBtn.innerHTML = `<svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14.5 1.5 1.5 7l4.6 1.9L8 13.5l2.2-3.7 4.3-8.3z"/><path d="M14.5 1.5 6.1 8.9"/></svg>`;
  publishBtn.title = "Publish — finalize the creative and make it eligible for delivery.";
  publishBtn.setAttribute("aria-label", "Publish");
  publishBtn.style.cssText = [
    "display: inline-flex",
    "align-items: center",
    "justify-content: center",
    "width: 30px",
    "height: 28px",
    "padding: 0",
    `background: ${tokens.amber}`,
    "color: white",
    `border: 1px solid ${tokens.amber}`,
    "border-radius: 4px",
    "cursor: pointer",
    "font: inherit",
    "font-weight: 500",
  ].join(";");
  // Targeting is classified server-side by Gemini over the full IAB
  // taxonomy at publish; the campaign list shows an accurate "Untargeted"
  // badge after the fact, so there's no client-side pre-publish gate.
  //
  // We DO gate on completeness, though: an empty size (a fanout bucket
  // with no items) would deliver nothing in that slot — and now also
  // shows as a blank backdrop behind the expanded preview — so an empty
  // creative must never reach delivery. Block publish and point the
  // author at the unfilled sizes; Save Draft is unaffected.
  publishBtn.addEventListener("click", () => {
    const empties = collectEmptySizes(store);
    if (empties.length) {
      showEmptyBlock(group, empties);
      return;
    }
    submitSave(store, ctx, /*draft=*/ false);
  });

  group.append(draftBtn, publishBtn);
  container.appendChild(group);
  return group;
}

interface EmptySize {
  page: number; // 1-based, for display
  label: string;
}

/** Every (page, size) pair whose fanout bucket is empty. A non-empty
 *  result blocks publish. Exported for unit testing. */
export function collectEmptySizes(store: Store): EmptySize[] {
  const out: EmptySize[] = [];
  store.state.pages.forEach((page, i) => {
    for (const m of MODES) {
      if (fanoutStatus(page, m) === "empty") out.push({ page: i + 1, label: m.label });
    }
  });
  return out;
}

let openBlock: HTMLElement | null = null;

/** Anchored popover listing the empty sizes that are blocking publish.
 *  Dismisses on the next click anywhere (so it never sticks). */
function showEmptyBlock(anchor: HTMLElement, empties: EmptySize[]): void {
  openBlock?.remove();
  const multiPage = new Set(empties.map((e) => e.page)).size > 1;

  const pop = document.createElement("div");
  pop.style.cssText = [
    "position: absolute",
    "right: 0",
    "top: calc(100% + 8px)",
    "z-index: 600",
    "min-width: 220px",
    "max-width: 320px",
    "padding: 12px 14px",
    `background: ${tokens.ink800 ?? "#1c1c22"}`,
    `border: 1px solid ${tokens.amber}`,
    "border-radius: 6px",
    "box-shadow: 0 8px 24px rgba(0,0,0,0.4)",
    `color: ${tokens.ink100 ?? "#f0f0f2"}`,
    "font: inherit",
    "font-size: 12px",
    "line-height: 1.45",
  ].join(";");

  const title = document.createElement("div");
  title.textContent = "Can’t publish — fill these sizes first";
  title.style.cssText = `font-weight:600;margin-bottom:6px;color:${tokens.amber};`;
  pop.appendChild(title);

  const list = document.createElement("ul");
  list.style.cssText = "margin:0;padding:0;list-style:none;";
  // Cap the visible rows so a fresh creative (every size empty across
  // many pages) doesn't produce a wall of text.
  const shown = empties.slice(0, 6);
  for (const e of shown) {
    const li = document.createElement("li");
    li.style.cssText = "padding:1px 0;color:" + (tokens.ink200 ?? "#c8c8cf") + ";";
    li.textContent = multiPage ? `${e.label} · page ${e.page}` : e.label;
    list.appendChild(li);
  }
  if (empties.length > shown.length) {
    const more = document.createElement("li");
    more.style.cssText = `padding:3px 0 0;color:${tokens.ink400 ?? "#85858f"};`;
    more.textContent = `+${empties.length - shown.length} more`;
    list.appendChild(more);
  }
  pop.appendChild(list);

  // Anchor relative to the button group.
  anchor.style.position = anchor.style.position || "relative";
  anchor.appendChild(pop);
  openBlock = pop;

  // Dismiss on the next click outside the popover (deferred so the click
  // that opened it doesn't immediately close it).
  const dismiss = (e: MouseEvent): void => {
    if (pop.contains(e.target as Node)) return;
    pop.remove();
    if (openBlock === pop) openBlock = null;
    document.removeEventListener("click", dismiss, true);
  };
  setTimeout(() => document.addEventListener("click", dismiss, true), 0);
}

// Back-compat export for any caller still importing the old single-button API.
export const mountSaveButton = mountSaveButtons;

function submitSave(store: Store, ctx: DesignerContext, draft: boolean): void {
  const { w, h } = bannerDimensions(ctx.bannerSize);
  const form = document.createElement("form");
  form.method = "POST";
  form.action = "/advertiser/creatives/save";
  form.style.display = "none";

  const add = (name: string, value: string): void => {
    const input = document.createElement("input");
    input.type = "hidden";
    input.name = name;
    input.value = value;
    form.appendChild(input);
  };

  add("campaignId", ctx.campaignId);
  add("name", ctx.creativeName || "Creative");
  add("landingUrl", ctx.landingUrl);
  add("pagesJson", JSON.stringify(store.state.pages));
  add("bannerConfigJson", JSON.stringify(store.state.bannerConfig));
  add("width", String(w));
  add("height", String(h));
  if (draft) add("draft", "1");
  // Preserve the creativeId across repeated draft saves (and draft →
  // publish) so we overwrite the same row instead of creating orphans.
  if (ctx.creativeId) add("creativeId", ctx.creativeId);
  // Carry the extracted LP text through the save so core can feed it
  // to Gemini category verification instead of the rendered banner
  // image. Without this the verifier falls back to the image path.
  if (ctx.lpTextSnapshot) add("lpTextSnapshot", ctx.lpTextSnapshot);

  document.body.appendChild(form);
  form.submit();
}

function bannerDimensions(bannerSize: string): { w: number; h: number } {
  // bannerSize is "300x250" / "728x90" / etc. Fall back via parseAspect
  // (which accepts "w/h"); if even that fails, the existing server
  // handler defaults to 300x250.
  try {
    const m = /^(\d+)x(\d+)$/.exec(bannerSize);
    if (m) return { w: Number(m[1]), h: Number(m[2]) };
    const a = parseAspect(bannerSize.replace("x", "/"));
    return { w: a.w, h: a.h };
  } catch {
    return { w: 300, h: 250 };
  }
}
