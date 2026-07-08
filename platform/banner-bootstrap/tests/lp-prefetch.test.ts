// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { attachLpPrefetch } from "../src/lp-prefetch";

const LP_URL  = "https://advertiser.example/landing?utm_source=promovolve&utm_campaign=spring";
const LP_ORIGIN = "https://advertiser.example";

function makeBanner(): HTMLElement {
  const el = document.createElement("div");
  document.body.appendChild(el);
  return el;
}

function fireExpand(banner: HTMLElement, ctaUrl: string, totalPages = 3): void {
  banner.dispatchEvent(new CustomEvent("magazine-expand", {
    bubbles:  true,
    composed: true,
    detail:   { ctaUrl, pageIndex: 0, totalPages },
  }));
}

function fireCollapse(banner: HTMLElement): void {
  banner.dispatchEvent(new CustomEvent("magazine-collapse", { bubbles: true, composed: true }));
}

function firePageChanged(banner: HTMLElement, pageIndex: number, totalPages: number): void {
  banner.dispatchEvent(new CustomEvent("magazine-page-changed", {
    bubbles:  true,
    composed: true,
    detail:   { pageIndex, totalPages },
  }));
}

function findHint(rel: string, href: string): HTMLLinkElement | null {
  return document.head.querySelector<HTMLLinkElement>(`link[rel="${rel}"][href="${href}"]`);
}

beforeEach(() => {
  document.head.innerHTML = "";
  document.body.innerHTML = "";
});

afterEach(() => {
  // Restore any HTMLScriptElement.supports overrides between tests.
  delete (HTMLScriptElement as unknown as { supports?: unknown }).supports;
});

describe("attachLpPrefetch — Layer 1 (DNS + preconnect)", () => {
  it("injects dns-prefetch + preconnect for the LP origin on magazine-expand", () => {
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL);

    expect(findHint("dns-prefetch", LP_ORIGIN)).not.toBeNull();
    const preconnect = findHint("preconnect", LP_ORIGIN);
    expect(preconnect).not.toBeNull();
    expect(preconnect?.getAttribute("crossorigin")).toBe("");
  });

  it("uses the origin only, not the full URL with query string", () => {
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL);

    expect(findHint("dns-prefetch", LP_URL)).toBeNull();
    expect(findHint("dns-prefetch", LP_ORIGIN)).not.toBeNull();
  });

  it("skips injection for same-origin URLs (no point preconnecting to the publisher)", () => {
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, `${window.location.origin}/some/path`);

    expect(document.head.querySelectorAll('link[rel="dns-prefetch"]').length).toBe(0);
    expect(document.head.querySelectorAll('link[rel="preconnect"]').length).toBe(0);
    expect(document.head.querySelectorAll('link[rel="prefetch"]').length).toBe(0);
  });

  it("skips injection when the CTA URL is empty", () => {
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, "");

    expect(document.head.children.length).toBe(0);
  });

  it("skips injection for non-http(s) protocols", () => {
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, "javascript:alert(1)");

    expect(document.head.children.length).toBe(0);
  });
});

describe("attachLpPrefetch — Layer 2 (LP prefetch)", () => {
  it("injects rel=prefetch as=document with the exact CTA URL (UTMs included)", () => {
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL);

    const prefetch = findHint("prefetch", LP_URL);
    expect(prefetch).not.toBeNull();
    expect(prefetch?.getAttribute("as")).toBe("document");
  });

  it("does not duplicate the prefetch hint on a second magazine-expand event", () => {
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL);
    fireExpand(banner, LP_URL);

    expect(document.head.querySelectorAll(`link[rel="prefetch"][href="${LP_URL}"]`).length).toBe(1);
    expect(document.head.querySelectorAll(`link[rel="dns-prefetch"][href="${LP_ORIGIN}"]`).length).toBe(1);
  });

  it("emits the lp-prefetch-injected performance mark", () => {
    const banner = makeBanner();
    const markSpy = vi.spyOn(performance, "mark");
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL);

    expect(markSpy).toHaveBeenCalledWith("pv:lp-prefetch-injected");
  });
});

describe("attachLpPrefetch — Layer 3 (Speculation Rules prerender)", () => {
  function withSpeculationRulesSupport(supported: boolean): void {
    (HTMLScriptElement as unknown as { supports: (t: string) => boolean }).supports =
      (type: string) => supported && type === "speculationrules";
  }

  it("injects a speculationrules script when last page is reached and feature is supported", () => {
    withSpeculationRulesSupport(true);
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL, 3);
    firePageChanged(banner, 2, 3);

    const script = document.head.querySelector<HTMLScriptElement>(
      'script[type="speculationrules"]',
    );
    expect(script).not.toBeNull();
    const rules = JSON.parse(script!.textContent ?? "{}");
    expect(rules.prerender[0].urls).toEqual([LP_URL]);
  });

  it("does NOT inject prerender on intermediate pages", () => {
    withSpeculationRulesSupport(true);
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL, 3);
    firePageChanged(banner, 1, 3);

    expect(document.head.querySelector('script[type="speculationrules"]')).toBeNull();
  });

  it("does NOT inject prerender when feature detection fails (no .supports)", () => {
    // No override → HTMLScriptElement has no `supports` method in happy-dom.
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL, 3);
    firePageChanged(banner, 2, 3);

    expect(document.head.querySelector('script[type="speculationrules"]')).toBeNull();
  });

  it("does NOT inject prerender when .supports returns false (Firefox/Safari)", () => {
    withSpeculationRulesSupport(false);
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL, 3);
    firePageChanged(banner, 2, 3);

    expect(document.head.querySelector('script[type="speculationrules"]')).toBeNull();
  });

  it("does not duplicate the prerender script on repeated last-page events", () => {
    withSpeculationRulesSupport(true);
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL, 3);
    firePageChanged(banner, 2, 3);
    firePageChanged(banner, 1, 3); // back to page 2
    firePageChanged(banner, 2, 3); // forward to page 3 again

    expect(document.head.querySelectorAll('script[type="speculationrules"]').length).toBe(1);
  });

  it("emits the lp-prerender-injected performance mark", () => {
    withSpeculationRulesSupport(true);
    const markSpy = vi.spyOn(performance, "mark");
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL, 3);
    firePageChanged(banner, 2, 3);

    expect(markSpy).toHaveBeenCalledWith("pv:page-3-reached");
    expect(markSpy).toHaveBeenCalledWith("pv:lp-prerender-injected");
  });
});

describe("attachLpPrefetch — cleanup on collapse", () => {
  it("removes all injected hints on magazine-collapse", () => {
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL);

    expect(findHint("prefetch",     LP_URL))    .not.toBeNull();
    expect(findHint("preconnect",   LP_ORIGIN)) .not.toBeNull();
    expect(findHint("dns-prefetch", LP_ORIGIN)) .not.toBeNull();

    fireCollapse(banner);

    expect(findHint("prefetch",     LP_URL))    .toBeNull();
    expect(findHint("preconnect",   LP_ORIGIN)) .toBeNull();
    expect(findHint("dns-prefetch", LP_ORIGIN)) .toBeNull();
  });

  it("removes the speculationrules script too on collapse", () => {
    (HTMLScriptElement as unknown as { supports: (t: string) => boolean }).supports = () => true;
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL, 3);
    firePageChanged(banner, 2, 3);
    expect(document.head.querySelector('script[type="speculationrules"]')).not.toBeNull();

    fireCollapse(banner);
    expect(document.head.querySelector('script[type="speculationrules"]')).toBeNull();
  });

  it("re-injects on the next expand cycle (state is per-session, not banner-lifetime)", () => {
    const banner = makeBanner();
    attachLpPrefetch(banner);
    fireExpand(banner, LP_URL);
    fireCollapse(banner);
    fireExpand(banner, LP_URL);

    expect(findHint("prefetch", LP_URL)).not.toBeNull();
  });
});
