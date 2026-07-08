// Wraps POST /advertiser/creatives/generate-layout. Caller supplies
// the page's flat fields + the target mode; server returns a fresh
// AI-generated layout. Errors bubble up as thrown Errors so the
// caller can surface them in the UI (the inline editor silently
// swallowed them — we want to do better).

import type { LayoutItem, Page } from "../types";
import { isMultiPage, type Mode } from "../modes";

interface GenerateRequest {
  page: {
    tag: string;
    headline: string;
    sub: string;
    body: string;
    accent: string;
    bg: string;
    imgEmoji: string;
    caption: string;
    img: string | null;
  };
  aspect: string;
  // "expanded" covers both PC (16:9) and Mobile (9:16) — both are
  // multi-page magazine surfaces and Gemini distinguishes them by
  // `aspect`. "banner" is reserved for IAB sized slots, but those
  // don't reach this endpoint at all (the designer fills them from
  // hand-crafted presets).
  mode: "expanded" | "banner";
  // Pre-generation choices forwarded from window.__DESIGNER__. Server
  // resolves brandKitJson → palette and templateId → slot spec, then
  // injects both into the Gemini prompt. Empty strings = no choice.
  brandKitJson?: string;
  templateId?: string;
}

interface GenerateResponse {
  layout?: LayoutItem[];
}

export async function generateLayout(page: Page, mode: Mode): Promise<LayoutItem[]> {
  const ctx = window.__DESIGNER__;
  const body: GenerateRequest = {
    page: {
      tag: page.tag ?? "",
      headline: page.headline ?? "",
      sub: page.sub ?? "",
      body: page.body ?? "",
      accent: page.accent ?? "#c4a35a",
      bg: page.bg ?? "#1a1a1a",
      imgEmoji: page.imgEmoji ?? "",
      caption: page.caption ?? "",
      img: page.img ?? null,
    },
    aspect: mode.aspect,
    // Both PC ("expanded") and Mobile ("mobile") are expanded
    // multi-page surfaces; the server differentiates by `aspect`.
    // Anything else is an IAB sized slot — but those go through the
    // hand-crafted preset path and never hit this endpoint.
    mode: isMultiPage(mode) ? "expanded" : "banner",
    brandKitJson: ctx?.brandKitJson ?? "",
    templateId: ctx?.templateId ?? "",
  };

  const resp = await fetch("/advertiser/creatives/generate-layout", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!resp.ok) {
    throw new Error(`generate-layout failed: HTTP ${resp.status}`);
  }
  const data = (await resp.json()) as GenerateResponse;
  return Array.isArray(data.layout) ? data.layout : [];
}
