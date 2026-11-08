import { describe, expect, it } from "vitest";
import { fitSizedFieldBoxes, initialState } from "../src/state";
import type { LayoutItem, Page } from "../src/types";

// The bucket counterpart of fitReaderFieldBoxes: after a synced field
// edit, every IAB bucket's same-field box is re-fit to the text it now
// renders. The DOM measuring lives in render/canvas; here the callback
// is stubbed, and the contract under test is the STAMPING — which items
// are touched, which axis anchors hold, and referential no-ops.

const text = (over: Record<string, unknown> = {}): LayoutItem =>
  ({ type: "text", field: "headline", left: 10, top: 5, width: 50, height: 40, ...over } as LayoutItem);

const mk = (buckets: Record<string, LayoutItem[]>): Page[] => [
  { headline: "COPY", layout: [text()], banners: { "mobile-expanded": [text()], ...buckets } } as Page,
];

const bucketItem = (s: ReturnType<typeof fitSizedFieldBoxes>, key: string): Record<string, unknown> =>
  s.pages[0].banners![key]![0] as unknown as Record<string, unknown>;

describe("fitSizedFieldBoxes", () => {
  it("stamps width+height on a horizontal bucket item, keeping left/top", () => {
    const st = initialState(mk({ "300x250": [text()] }), "mobile");
    const r = fitSizedFieldBoxes(st, "headline", () => ({ width: 30, height: 12 }));
    const it = bucketItem(r, "300x250");
    expect(it.width).toBe(30);
    expect(it.height).toBe(12);
    expect(it.left).toBe(10);
    expect(it.top).toBe(5);
  });

  it("keeps the RIGHT edge fixed for vertical-rl (left derives from the packed width)", () => {
    const st = initialState(mk({ "300x600": [text({ writingMode: "vertical-rl" })] }), "mobile");
    const r = fitSizedFieldBoxes(st, "headline", () => ({ width: 15, height: 20 }));
    const it = bucketItem(r, "300x600");
    // right edge was 10 + 50 = 60; packed width 15 → left 45
    expect(it.width).toBe(15);
    expect(it.left).toBe(45);
    expect(it.top).toBe(5);
    expect(it.height).toBe(20);
  });

  it("leaves the mobile-expanded reader surface alone (fitReaderFieldBoxes owns it)", () => {
    const st = initialState(mk({ "300x250": [text()] }), "mobile");
    const r = fitSizedFieldBoxes(st, "headline", () => ({ width: 30, height: 12 }));
    expect(r.pages[0].banners!["mobile-expanded"]).toBe(st.pages[0].banners!["mobile-expanded"]);
  });

  it("packs mobile-expanded only when the caller targets it (the Fold-out poster)", () => {
    const st = initialState(mk({ "300x250": [text()] }), "mobile");
    const r = fitSizedFieldBoxes(st, "headline", () => ({ width: 30, height: 12 }), "mobile-expanded");
    expect(bucketItem(r, "mobile-expanded").height).toBe(12);
    expect(r.pages[0].banners!["300x250"]).toBe(st.pages[0].banners!["300x250"]);
  });

  it("field null fits UNBOUND literal text only, and stamps fontSize only on _generated items", () => {
    const lit = (over: Record<string, unknown>) =>
      ({ type: "text", left: 6, top: 40, width: 88, height: 13, fontSize: 8, ...over } as LayoutItem);
    const st = initialState([{ headline: "COPY", layout: [text()], banners: {
      "mobile-expanded": [text({ _generated: true }), lit({ text: "MACHINE", _generated: true }), lit({ text: "AUTHORED" })],
    } } as Page], "mobile");
    const seen: string[] = [];
    const r = fitSizedFieldBoxes(st, null, (t) => { seen.push(t); return { width: 70, height: 9, fontSize: 4.9 }; }, "mobile-expanded");
    expect(seen).toEqual(["MACHINE", "AUTHORED"]); // the field-bound headline is not unbound text
    const [bound, machine, authored] = r.pages[0].banners!["mobile-expanded"] as unknown as Record<string, unknown>[];
    expect(bound).toBe(st.pages[0].banners!["mobile-expanded"][0]);
    expect(machine).toMatchObject({ width: 70, height: 9, fontSize: 4.9 });
    expect(authored).toMatchObject({ width: 70, height: 9, fontSize: 8 }); // author's number untouched
  });

  it("measures a detached override against its OWN text", () => {
    const st = initialState(mk({ "300x250": [text({ text: "LOCAL" })] }), "mobile");
    const seen: string[] = [];
    fitSizedFieldBoxes(st, "headline", (t) => { seen.push(t); return null; });
    expect(seen).toEqual(["LOCAL"]);
  });

  it("returns the SAME state when every measure is a no-op", () => {
    const st = initialState(mk({ "300x250": [text()] }), "mobile");
    // measure returns the current extents → nothing to stamp
    expect(fitSizedFieldBoxes(st, "headline", () => ({ width: 50, height: 40 }))).toBe(st);
    // …and when the callback declines to measure
    expect(fitSizedFieldBoxes(st, "headline", () => null)).toBe(st);
  });

  it("only touches items bound to the edited field", () => {
    const st = initialState(
      mk({ "300x250": [text(), text({ field: "body", width: 33 })] }), "mobile");
    const r = fitSizedFieldBoxes(st, "headline", () => ({ width: 30, height: 12 }));
    const body = r.pages[0].banners!["300x250"]![1] as unknown as Record<string, unknown>;
    expect(body.width).toBe(33);
    expect(body.height).toBe(40);
  });

  // Font correction: machine-set sizes are the machine's to correct;
  // authored sizes are untouchable. The guard lives HERE (the stamping
  // site), so no measurer bug can ever reach an authored fontSize.
  it("stamps a corrected fontSize ONLY on _generated items", () => {
    const st = initialState(mk({
      "300x250": [text({ _generated: true, fontSize: 20 }), text({ field: "body", fontSize: 20 })],
    }), "mobile");
    const r = fitSizedFieldBoxes(st, "headline", () => ({ width: 30, height: 12, fontSize: 6 }));
    const gen = r.pages[0].banners!["300x250"]![0] as unknown as Record<string, unknown>;
    expect(gen.fontSize).toBe(6);
    expect(gen._generated).toBe(true); // the fit must not mark it authored
    // Same measure result against a NON-generated item: font untouched.
    const r2 = fitSizedFieldBoxes(st, "body", () => ({ width: 30, height: 12, fontSize: 6 }));
    const authored = r2.pages[0].banners!["300x250"]![1] as unknown as Record<string, unknown>;
    expect(authored.fontSize).toBe(20);
  });

  it("onlyGenerated skips items the author has touched", () => {
    const st = initialState(mk({
      "300x250": [text({ fontSize: 20 })], // no _generated: authored
    }), "mobile");
    const seen: string[] = [];
    const r = fitSizedFieldBoxes(st, "headline", (t) => { seen.push(t); return { width: 30, height: 12 }; },
      undefined, true);
    expect(seen).toEqual([]); // never even measured
    expect(r).toBe(st);
  });

  // Generation-time packing (applyLayout) scopes the fit to the mode it
  // just generated: re-generating one bucket must never rewrite a box the
  // author hand-tuned in another. Synced edits keep the everywhere fit.
  it("onlySizeKey restricts the fit to that bucket, leaving others untouched", () => {
    const st = initialState(
      mk({ "300x600": [text({ writingMode: "vertical-rl" })], "300x250": [text()] }), "mobile");
    const seen: string[] = [];
    const r = fitSizedFieldBoxes(st, "headline", (_t, _i, sizeKey) => {
      seen.push(sizeKey);
      return { width: 15, height: 20 };
    }, "300x600");
    expect(seen).toEqual(["300x600"]); // the other bucket was never measured
    expect(bucketItem(r, "300x600").width).toBe(15);
    // untouched bucket keeps its object identity, not just its values
    expect(r.pages[0].banners!["300x250"]).toBe(st.pages[0].banners!["300x250"]);
  });
});
