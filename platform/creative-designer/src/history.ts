// Snapshot-based undo/redo history.
//
// Two tracks:
//   - `_committed` — the last committed state. `past` and `future`
//     reference states that were once committed.
//   - `_transient` — the live state during a gesture (drag, resize,
//     rotate, marquee). Null when no gesture is active.
//
// `current` returns whichever is fresher (transient ?? committed),
// so subscribers always see the latest value.
//
// Why the two tracks: a pre-fix implementation stored the transient
// state directly on `_present`. When a gesture ended and called
// commit(current), `Object.is(next, _present)` was true and the push
// silently dropped. Drags, resizes, and rotates never landed in
// history. Separating the tracks lets commit push the real pre-
// gesture state into `past` and promote the final state into
// `_committed`.

export class History<T> {
  private past: T[] = [];
  private _committed: T;
  private _transient: T | null = null;
  private future: T[] = [];
  private readonly limit: number;

  constructor(initial: T, limit = 100) {
    this._committed = initial;
    this.limit = limit;
  }

  get current(): T {
    return this._transient ?? this._committed;
  }

  // Record one undo step. The pre-commit state moves into `past`; the
  // caller's `next` becomes the new committed state. Any transient
  // state is dropped — the gesture that produced it is now reflected
  // in `next`. Pushing the same reference as the committed state is
  // a no-op (but still clears any transient state).
  push(next: T): void {
    if (Object.is(next, this._committed)) {
      this._transient = null;
      return;
    }
    this.past.push(this._committed);
    if (this.past.length > this.limit) this.past.shift();
    this._committed = next;
    this._transient = null;
    this.future = [];
  }

  // Update the transient state without recording. Used during
  // per-frame gestures where only the final post-pointerup state
  // should land in history.
  replace(next: T): void {
    this._transient = next;
  }

  // Promote the current state to the new baseline without growing
  // `past`. Used by auto-layout: presets are applied via replace()
  // (so they don't each become an undo step), then seed() makes the
  // populated layout the new starting point so the first real commit
  // doesn't bury the user back in a blank template.
  seed(): void {
    if (this._transient !== null) {
      this._committed = this._transient;
      this._transient = null;
    }
    this.past = [];
    this.future = [];
  }

  undo(): T | null {
    this._transient = null;
    const prev = this.past.pop();
    if (prev === undefined) return null;
    this.future.push(this._committed);
    this._committed = prev;
    return this._committed;
  }

  redo(): T | null {
    this._transient = null;
    const next = this.future.pop();
    if (next === undefined) return null;
    this.past.push(this._committed);
    this._committed = next;
    return this._committed;
  }

  canUndo(): boolean {
    return this.past.length > 0;
  }

  canRedo(): boolean {
    return this.future.length > 0;
  }
}
