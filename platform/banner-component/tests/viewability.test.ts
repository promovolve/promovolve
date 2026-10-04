import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createViewableGate, viewableThreshold } from "../src/viewability";

describe("viewableThreshold (MRC large-ad rule)", () => {
  it("is 30% from 970×250 up and 50% below", () => {
    expect(viewableThreshold(970, 250)).toBe(0.3);
    expect(viewableThreshold(300, 600)).toBe(0.5); // 180,000 px
    expect(viewableThreshold(300, 250)).toBe(0.5);
  });
});

describe("createViewableGate (1 continuous second)", () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it("does not fire before 1 second, fires at 1 second, once", () => {
    const fired = vi.fn();
    const g = createViewableGate(fired, true);
    g.setInView(true);
    vi.advanceTimersByTime(999);
    expect(fired).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(fired).toHaveBeenCalledTimes(1);
    g.setInView(false);
    g.setInView(true);
    vi.advanceTimersByTime(5000);
    expect(fired).toHaveBeenCalledTimes(1);
  });

  it("is continuous, not cumulative: a dip restarts the clock", () => {
    const fired = vi.fn();
    const g = createViewableGate(fired, true);
    g.setInView(true);
    vi.advanceTimersByTime(600);
    g.setInView(false);
    g.setInView(true);
    vi.advanceTimersByTime(600); // 1.2s total in view, but not continuous
    expect(fired).not.toHaveBeenCalled();
    vi.advanceTimersByTime(400);
    expect(fired).toHaveBeenCalledTimes(1);
  });

  it("does not count while the tab is hidden, and restarts when it returns", () => {
    const fired = vi.fn();
    const g = createViewableGate(fired, false);
    g.setInView(true);
    vi.advanceTimersByTime(3000);
    expect(fired).not.toHaveBeenCalled();
    g.setTabVisible(true);
    vi.advanceTimersByTime(500);
    g.setTabVisible(false);
    vi.advanceTimersByTime(3000);
    expect(fired).not.toHaveBeenCalled();
    g.setTabVisible(true);
    vi.advanceTimersByTime(1000);
    expect(fired).toHaveBeenCalledTimes(1);
  });

  it("fires at once on a click, and not again after", () => {
    const fired = vi.fn();
    const g = createViewableGate(fired, true);
    g.setInView(true);
    vi.advanceTimersByTime(200);
    g.fireNow();
    expect(fired).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(2000);
    expect(fired).toHaveBeenCalledTimes(1);
  });

  it("never fires after dispose", () => {
    const fired = vi.fn();
    const g = createViewableGate(fired, true);
    g.setInView(true);
    g.dispose();
    vi.advanceTimersByTime(2000);
    g.fireNow();
    expect(fired).not.toHaveBeenCalled();
  });
});
