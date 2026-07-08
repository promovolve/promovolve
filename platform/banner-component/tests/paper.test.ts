// @vitest-environment jsdom
//
// The paper module carries the magazine identity of the expanded
// reader: grain overlay, sheet/stack styling, and the turn.js-style
// corner-peel page turn. The peel is the risky part — fold geometry
// computed per frame, plus DOM state (visibility, z-order, the flap
// element) that EVERY exit path (frame completion, failsafe timeout,
// finish-now) must restore, or a page ends up invisible/unclickable
// after navigation.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PAPER_CSS, animatePageTurn, buildGrainOverlay, cornerAt, foldFrame } from "../src/paper";

describe("PAPER_CSS", () => {
  it("defines the stack, sheet, flap, and grain", () => {
    expect(PAPER_CSS).toContain(".paper-stack");
    expect(PAPER_CSS).toContain(".paper-sheet");
    expect(PAPER_CSS).toContain(".paper-flap");
    expect(PAPER_CSS).toContain(".paper-grain");
  });

  it("has no stacked-leaf pseudos (the pile was dropped by design)", () => {
    // The offset "stack of sheets" behind the page read as incoherent
    // with the peel (pages reveal from beneath, not from a pile) and
    // was removed — guard against it creeping back as decoration.
    expect(PAPER_CSS).not.toContain(".paper-stack::");
    expect(PAPER_CSS).not.toContain(".paper-sheet::");
    // During a peel the turning page's box must get out of the way
    // so the lifting sheet reveals the real page beneath.
    expect(PAPER_CSS).toContain(".page-peeling .paper-stack");
  });

  it("flap reflection matrix assumes a top-left transform-origin", () => {
    // CSS defaults to center origin; the reflection is derived about
    // (0,0) — without this the flap renders far off the page.
    const flapRule = PAPER_CSS.slice(PAPER_CSS.indexOf(".paper-flap"));
    expect(flapRule).toContain("transform-origin: 0 0");
  });

  it("keeps the grain transparent to pointer events (CTA taps pass through)", () => {
    const grainRule = PAPER_CSS.slice(PAPER_CSS.indexOf(".paper-grain"));
    expect(grainRule).toContain("pointer-events: none");
  });
});

describe("buildGrainOverlay", () => {
  it("returns a .paper-grain div", () => {
    expect(buildGrainOverlay().className).toBe("paper-grain");
  });
});

describe("foldFrame geometry", () => {
  const W = 960, H = 540;

  it("rests at t=0: no clip, no flap, fully opaque", () => {
    const f = foldFrame(0, W, H);
    expect(f.keptClip).toBeNull();
    expect(f.cutClip).toBeNull();
    expect(f.fade).toBe(1);
  });

  it("mid-turn: both regions exist and the sheet stays opaque", () => {
    const f = foldFrame(0.5, W, H);
    expect(f.keptClip).toContain("polygon(");
    expect(f.cutClip).toContain("polygon(");
    expect(f.flapTransform).toContain("matrix(");
    expect(f.fade).toBe(1);
  });

  it("melts away over the final 20% (no left page to receive the sheet)", () => {
    expect(foldFrame(0.79, W, H).fade).toBe(1);
    expect(foldFrame(0.8, W, H).fade).toBeCloseTo(1, 6);
    expect(foldFrame(0.9, W, H).fade).toBeCloseTo(0.5, 5);
    expect(foldFrame(1, W, H).fade).toBe(0);
  });

  it("a dog-ear notch carves the curling back, never the front", () => {
    // The folded-away corner is ABSENT from the leaf, so the curl
    // (cut region, pre-mirror) must be clipped along the crease from
    // (w-notch, 0) to (w, notch); the front's clip is unchanged (the
    // punch rides the stage separately).
    const t = 0.75; // late enough that the cut region spans the top-right corner
    const plain = foldFrame(t, W, H);
    const notched = foldFrame(t, W, H, 120);
    expect(notched.keptClip).toBe(plain.keptClip);
    expect(notched.cutClip).not.toBe(plain.cutClip);
    // The original page corner (W, 0) sits inside the plain cut region
    // but must be outside the notched one — spot-check via vertices:
    // the notched polygon must not contain the bare corner point.
    expect(plain.cutClip).toContain(`${W.toFixed(2)}px 0.00px`);
    expect(notched.cutClip).not.toContain(`${W.toFixed(2)}px 0.00px`);
  });

  it("the reflection maps the grabbed corner onto its traveled position", () => {
    // The whole construction hinges on this: reflecting the page
    // across the fold line must send the bottom-right corner exactly
    // to cornerAt(t) — that's what makes the flap read as the same
    // sheet folded over rather than an arbitrary mirrored shape.
    for (const t of [0.2, 0.5, 0.75]) {
      const f = foldFrame(t, W, H);
      const m = f.flapTransform.match(/matrix\(([^)]+)\)/);
      expect(m).not.toBeNull();
      const [a, b, c, d, e, fy] = m![1].split(",").map(Number);
      const mapped = { x: a * W + c * H + e, y: b * W + d * H + fy };
      const target = cornerAt(t, W, H);
      expect(mapped.x).toBeCloseTo(target.x, 6);
      expect(mapped.y).toBeCloseTo(target.y, 6);
    }
  });
});

describe("animatePageTurn", () => {
  let outgoing: HTMLElement;
  let incoming: HTMLElement;

  // Mirror the production structure: container > stack > sheet > stage.
  function buildPage(): { el: HTMLElement; stack: HTMLElement; sheet: HTMLElement; stage: HTMLElement } {
    const el = document.createElement("div");
    const stack = document.createElement("div");
    stack.className = "paper-stack";
    const sheet = document.createElement("div");
    sheet.className = "paper-sheet";
    const stage = document.createElement("div");
    stage.className = "paper-stage";
    sheet.appendChild(stage);
    stack.appendChild(sheet);
    el.appendChild(stack);
    el.style.transition = "opacity 0.5s ease";
    document.body.appendChild(el);
    return { el, stack, sheet, stage };
  }

  beforeEach(() => {
    outgoing = buildPage().el;
    incoming = buildPage().el;
  });

  afterEach(() => {
    outgoing.remove();
    incoming.remove();
  });

  it("next: the OUTGOING page peels on top; flap mounted in its stack", () => {
    const finish = animatePageTurn({ outgoing, incoming, direction: "next", onDone: () => {} });
    expect(outgoing.classList.contains("page-peeling")).toBe(true);
    expect(outgoing.style.zIndex).toBe("30");
    expect(incoming.style.zIndex).toBe("20");
    expect(outgoing.querySelector(".paper-flap")).not.toBeNull();
    expect(outgoing.style.transition).toBe("none");
    finish();
  });

  it("prev: the INCOMING page un-peels on top", () => {
    const finish = animatePageTurn({ outgoing, incoming, direction: "prev", onDone: () => {} });
    expect(incoming.classList.contains("page-peeling")).toBe(true);
    expect(incoming.style.zIndex).toBe("30");
    expect(outgoing.style.zIndex).toBe("20");
    expect(incoming.querySelector(".paper-flap")).not.toBeNull();
    finish();
  });

  it("finish-now settles immediately, idempotently, and restores DOM state", () => {
    const onDone = vi.fn();
    const finish = animatePageTurn({ outgoing, incoming, direction: "next", onDone });
    finish();
    finish();
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(outgoing.classList.contains("page-peeling")).toBe(false);
    expect(outgoing.querySelector(".paper-flap")).toBeNull();
    expect(outgoing.style.zIndex).toBe("");
    expect(incoming.style.zIndex).toBe("");
  });

  it("settles on its own via frames/failsafe when left to run", async () => {
    const onDone = vi.fn();
    animatePageTurn({ outgoing, incoming, direction: "next", durationMs: 40, onDone });
    await new Promise(r => setTimeout(r, 40 + 200 + 80));
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(outgoing.querySelector(".paper-flap")).toBeNull();
  });

  it("a dog-eared page's punch clip travels with the peel and returns on settle", () => {
    // The fold's punch lives on the static pageBox; during a peel it
    // must ride the sheet (via the stage) or the notch stays glued to
    // the page frame and bites the curling back at a fixed position.
    const pg = buildPage();
    const punch = "polygon(0px 0px, 10px 0px, 10px 10px)";
    pg.stack.style.clipPath = punch;
    const finish = animatePageTurn({ outgoing: pg.el, incoming, direction: "next", onDone: () => {} });
    expect(pg.stage.style.clipPath).toBe(punch);
    expect(pg.stack.style.clipPath).toBe("");
    expect(pg.sheet.style.background).toBe("transparent");
    finish();
    expect(pg.stage.style.clipPath).toBe("");
    expect(pg.stack.style.clipPath).toBe(punch);
    expect(pg.sheet.style.background).toBe("");
    pg.el.remove();
  });

  it("a page with no .paper-sheet (placeholder) still settles via the failsafe", async () => {
    const bare = document.createElement("div");
    document.body.appendChild(bare);
    const onDone = vi.fn();
    animatePageTurn({ outgoing: bare, incoming, direction: "next", durationMs: 30, onDone });
    await new Promise(r => setTimeout(r, 30 + 200 + 80));
    expect(onDone).toHaveBeenCalledTimes(1);
    bare.remove();
  });
});
