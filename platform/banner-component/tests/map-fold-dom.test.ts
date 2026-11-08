// @vitest-environment jsdom
// Map (gatefold) DOM: panel structure, tap vs drag, the close lifecycle,
// reduced motion. jsdom has no layout (root rect is all zeros) and no rAF
// clock, so frames are driven by hand against a fake performance.now().
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { openMapFold, type MapFoldOptions } from "../src/map-fold";

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

function setup(over: Partial<MapFoldOptions> = {}) {
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
    onClosed: vi.fn(),
  };
  return { o, root, h: openMapFold({ ...o, ...over }) };
}

const ptr = (el: Element, type: string, y: number, x = 50): void => {
  el.dispatchEvent(new PointerEvent(type, { bubbles: true, clientX: x, clientY: y, pointerId: 1 }));
};

describe("map-fold DOM", () => {
  it("builds 3 panels", () => {
    const { o, root } = setup();
    expect(root.querySelectorAll(".map-panel")).toHaveLength(3);
    expect(root.querySelectorAll(".map-face")).toHaveLength(3);
    expect(o.face).toHaveBeenCalledTimes(3);
  });

  it("tap on poster calls onTap, drag does not", () => {
    const { o, root } = setup();
    const face = root.querySelector(".map-face")!;
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
    const hinges = [...root.querySelectorAll<HTMLElement>(".map-panel")]
      .map((p) => p.style.transform)
      .filter((t) => t);
    expect(hinges.length).toBeGreaterThan(0);
    for (const t of hinges) expect(t).toBe("rotateX(0deg)");
  });
});
