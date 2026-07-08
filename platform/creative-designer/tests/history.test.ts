import { describe, expect, it } from "vitest";
import { History } from "../src/history";

describe("History", () => {
  it("current returns the initial value", () => {
    const h = new History(0);
    expect(h.current).toBe(0);
    expect(h.canUndo()).toBe(false);
    expect(h.canRedo()).toBe(false);
  });

  it("push adds to past and updates current", () => {
    const h = new History(0);
    h.push(1);
    h.push(2);
    expect(h.current).toBe(2);
    expect(h.canUndo()).toBe(true);
  });

  it("undo walks back one step at a time", () => {
    const h = new History(0);
    h.push(1);
    h.push(2);
    expect(h.undo()).toBe(1);
    expect(h.current).toBe(1);
    expect(h.undo()).toBe(0);
    expect(h.undo()).toBeNull();
  });

  it("redo walks forward after undo", () => {
    const h = new History(0);
    h.push(1);
    h.push(2);
    h.undo();
    h.undo();
    expect(h.redo()).toBe(1);
    expect(h.redo()).toBe(2);
    expect(h.redo()).toBeNull();
  });

  it("a fresh push clears future", () => {
    const h = new History(0);
    h.push(1);
    h.push(2);
    h.undo();             // current = 1, future = [2]
    h.push(99);           // should drop future
    expect(h.canRedo()).toBe(false);
    expect(h.current).toBe(99);
  });

  it("push of the same reference is a no-op", () => {
    const obj = { a: 1 };
    const h = new History(obj);
    h.push(obj);
    expect(h.canUndo()).toBe(false);
  });

  it("respects the history limit", () => {
    const h = new History(0, 3);
    for (let i = 1; i <= 10; i++) h.push(i);
    // 10 pushes but limit=3, so only the 3 most-recent-past are retained.
    // current=10, past=[7,8,9]
    expect(h.undo()).toBe(9);
    expect(h.undo()).toBe(8);
    expect(h.undo()).toBe(7);
    expect(h.undo()).toBeNull();
  });

  it("replace doesn't touch history", () => {
    const h = new History("a");
    h.push("b");
    h.replace("b2");
    expect(h.current).toBe("b2");
    expect(h.undo()).toBe("a"); // skips the replace
  });

  // Regression: replace followed by commit used to be a silent no-op
  // because commit compared its argument against the transient _present.
  // The fix separates transient from committed tracks.
  it("commit lands in history even after replace calls", () => {
    const h = new History("initial");
    h.replace("drag-frame-1");
    h.replace("drag-frame-2");
    h.push("final");
    expect(h.canUndo()).toBe(true);
    expect(h.current).toBe("final");
    expect(h.undo()).toBe("initial");
  });

  it("undo drops any active transient state", () => {
    const h = new History("a");
    h.push("b");
    h.replace("b-transient");
    expect(h.current).toBe("b-transient");
    expect(h.undo()).toBe("a");
    expect(h.current).toBe("a");
  });
});
