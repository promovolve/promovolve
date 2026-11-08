// @vitest-environment jsdom
// Fold-out (gatefold) DOM: panel structure, tap vs drag, the close lifecycle,
// reduced motion. jsdom has no layout (root rect is all zeros) and no rAF
// clock, so frames are driven by hand against a fake performance.now().
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { openFoldOut, type FoldOutOptions } from "../src/fold-out";

let now = 0;
let nextId = 0;
let queue = new Map<number, FrameRequestCallback>();
const frame = (ms = 16): void => {
  now += ms;
  const cbs = [...queue.values()];
  queue.clear();
  cbs.forEach((cb) => cb(now));
};

beforeEach(() => {
  now = 0;
  queue = new Map();
  vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => {
    queue.set(++nextId, cb);
    return nextId;
  });
  vi.stubGlobal("cancelAnimationFrame", (h: number) => { queue.delete(h); });
  vi.spyOn(performance, "now").mockImplementation(() => now);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  document.body.replaceChildren();
});

const slot = { x: 40, y: 300, left: 40, top: 300, width: 300, height: 250, right: 340, bottom: 550 } as DOMRect;

function setup(over: Partial<FoldOutOptions> = {}) {
  const root = document.createElement("div");
  document.body.append(root);
  const o = {
    root,
    framed: false,
    face: vi.fn(() => document.createElement("div")),
    slotRect: () => slot,
    fitPct: 97,
    backBackground: "#eee",
    closeLabel: "Close",
    rtl: false,
    reducedMotion: false,
    onLift: vi.fn(),
    onTap: vi.fn(),
    onOpened: vi.fn(),
    onFoldStart: vi.fn(),
    onCloseCommit: vi.fn(),
    onClosed: vi.fn(),
  };
  return { o, root, h: openFoldOut({ ...o, ...over }) };
}

const settle = (until: () => boolean): void => {
  for (let i = 0; i < 600 && !until(); i++) frame();
};

const ptr = (el: Element, type: string, y: number, x = 50): void => {
  el.dispatchEvent(new PointerEvent(type, { bubbles: true, clientX: x, clientY: y, pointerId: 1, isPrimary: true }));
};

describe("fold-out DOM", () => {
  it("builds 3 panels", () => {
    const { o, root } = setup();
    expect(root.querySelectorAll(".foldout-panel")).toHaveLength(3);
    expect(root.querySelectorAll(".foldout-face")).toHaveLength(3);
    expect(o.face).toHaveBeenCalledTimes(3);
  });

  it("tap on poster calls onTap, drag does not", () => {
    const { o, root } = setup();
    settle(() => o.onOpened.mock.calls.length > 0);
    const face = root.querySelector(".foldout-face")!;
    ptr(face, "pointerdown", 100);
    ptr(face, "pointerup", 100);
    expect(o.onTap).toHaveBeenCalledTimes(1);
    o.onTap.mockClear();
    ptr(face, "pointerdown", 100);
    ptr(face, "pointermove", 40);
    ptr(face, "pointerup", 40);
    expect(o.onTap).toHaveBeenCalledTimes(0);
  });

  it("close-mid-open", () => {
    const { o, root, h } = setup();
    for (let i = 0; i < 5; i++) frame();
    h.close();
    for (let i = 0; i < 600 && !o.onClosed.mock.calls.length; i++) frame();
    expect(o.onClosed).toHaveBeenCalledTimes(1);
    expect(root.childElementCount).toBe(0);
  });

  it("reduced motion", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { o, root } = setup({ reducedMotion: true });
    vi.advanceTimersByTime(250);
    expect(o.onOpened).toHaveBeenCalledTimes(1);
    const hinges = [...root.querySelectorAll<HTMLElement>(".foldout-panel")]
      .map((p) => p.style.transform)
      .filter((t) => t);
    expect(hinges.length).toBeGreaterThan(0);
    for (const t of hinges) expect(t).toBe("rotateX(0deg)");
  });

  it("a tap during opening does not call onTap", () => {
    const { o, root } = setup();
    for (let i = 0; i < 3; i++) frame();
    const face = root.querySelector(".foldout-face")!;
    ptr(face, "pointerdown", 100);
    ptr(face, "pointerup", 100);
    expect(o.onTap).not.toHaveBeenCalled();
    settle(() => o.onOpened.mock.calls.length > 0);
    expect(o.onOpened).toHaveBeenCalledTimes(1); // the unfold resumed
  });

  it("a grab during close stays committed", () => {
    const { o, root, h } = setup();
    settle(() => o.onOpened.mock.calls.length > 0);
    h.close();
    for (let i = 0; i < 5; i++) frame();
    const face = root.querySelector(".foldout-face")!;
    ptr(face, "pointerdown", 100);
    ptr(face, "pointermove", 300);
    ptr(face, "pointerup", 300);
    ptr(face, "pointerdown", 100);
    ptr(face, "pointerup", 100);
    settle(() => o.onClosed.mock.calls.length > 0);
    for (let i = 0; i < 60; i++) frame();
    expect(o.onClosed).toHaveBeenCalledTimes(1);
    expect(o.onOpened).toHaveBeenCalledTimes(1);
    expect(o.onTap).not.toHaveBeenCalled();
    expect(root.childElementCount).toBe(0);
  });

  it("a grab during a reduced-motion fade-out stays committed", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const { o, root, h } = setup({ reducedMotion: true });
    vi.advanceTimersByTime(250);
    h.close();
    vi.advanceTimersByTime(100);
    const face = root.querySelector(".foldout-face")!;
    ptr(face, "pointerdown", 100);
    ptr(face, "pointerup", 100);
    vi.advanceTimersByTime(250);
    expect(o.onClosed).toHaveBeenCalledTimes(1);
    expect(o.onTap).not.toHaveBeenCalled();
    expect(root.childElementCount).toBe(0);
  });

  it("a backdrop tap closes", () => {
    const { o, root } = setup();
    settle(() => o.onOpened.mock.calls.length > 0);
    ptr(root, "pointerdown", 5, 5);
    ptr(root, "pointerup", 5, 5);
    settle(() => o.onClosed.mock.calls.length > 0);
    expect(o.onCloseCommit).toHaveBeenCalledTimes(1);
    expect(o.onClosed).toHaveBeenCalledTimes(1);
    expect(o.onTap).not.toHaveBeenCalled();
  });

  it("a right-click (button 2) neither taps the poster nor closes from the backdrop", () => {
    const { o, root } = setup();
    settle(() => o.onOpened.mock.calls.length > 0);
    const rc = (el: Element, type: string, y: number, x = 50) =>
      el.dispatchEvent(new PointerEvent(type, { bubbles: true, clientX: x, clientY: y, pointerId: 1, button: 2, isPrimary: true }));
    const face = root.querySelector(".foldout-face")!;
    rc(face, "pointerdown", 100);
    rc(face, "pointerup", 100);
    rc(root, "pointerdown", 5, 5);
    rc(root, "pointerup", 5, 5);
    for (let i = 0; i < 60; i++) frame();
    expect(o.onTap).not.toHaveBeenCalled();
    expect(o.onCloseCommit).not.toHaveBeenCalled();
    expect(o.onClosed).not.toHaveBeenCalled();
  });

  it("onCloseCommit fires once, before onClosed", () => {
    const { o, h } = setup();
    settle(() => o.onOpened.mock.calls.length > 0);
    h.close();
    expect(o.onCloseCommit).toHaveBeenCalledTimes(1);
    expect(o.onClosed).not.toHaveBeenCalled();
    h.close();
    settle(() => o.onClosed.mock.calls.length > 0);
    expect(o.onCloseCommit).toHaveBeenCalledTimes(1);
    expect(o.onCloseCommit.mock.invocationCallOrder[0]).toBeLessThan(o.onClosed.mock.invocationCallOrder[0]);
  });

  it("destroy() does not call onClosed", () => {
    const { o, root, h } = setup();
    for (let i = 0; i < 5; i++) frame();
    h.destroy();
    for (let i = 0; i < 120; i++) frame();
    expect(o.onClosed).not.toHaveBeenCalled();
    expect(o.onCloseCommit).not.toHaveBeenCalled();
    expect(root.childElementCount).toBe(0);
  });
});
