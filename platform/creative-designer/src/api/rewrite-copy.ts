// Wraps POST /advertiser/creatives/rewrite-copy. Companion to
// generate-layout: the Regenerate button calls rewrite-copy first to
// produce a fresh phrasing, then generate-layout to compose it.
//
// lpContext is the original LP-extraction text snapshot (DesignerContext
// .lpTextSnapshot) — passed through so Gemini stays anchored on the
// source of truth and doesn't invent claims the LP doesn't support.
// Empty for direct-upload creatives; the server falls back to "rephrase
// faithfully" without a source anchor.

import type { Page } from "../types";

interface RewriteRequest {
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
  lpContext: string;
}

export interface RewrittenCopy {
  tag: string;
  headline: string;
  sub: string;
  body: string;
}

export async function rewriteCopy(page: Page, lpContext: string): Promise<RewrittenCopy> {
  const body: RewriteRequest = {
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
    lpContext,
  };
  const resp = await fetch("/advertiser/creatives/rewrite-copy", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!resp.ok) {
    throw new Error(`rewrite-copy failed: HTTP ${resp.status}`);
  }
  const data = (await resp.json()) as Partial<RewrittenCopy>;
  return {
    tag:      typeof data.tag      === "string" ? data.tag      : "",
    headline: typeof data.headline === "string" ? data.headline : "",
    sub:      typeof data.sub      === "string" ? data.sub      : "",
    body:     typeof data.body     === "string" ? data.body     : "",
  };
}
