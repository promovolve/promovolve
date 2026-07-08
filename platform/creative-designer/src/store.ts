// Single-owner state store wrapping History. Subscribers re-render
// whenever state changes; interaction handlers mutate via commit()
// (undoable) or replace() (transient, during a drag).
//
// Callers import this from ./store rather than constructing a History
// directly, so we have one place to add dev tooling later (logging,
// time-travel, persistence).

import { History } from "./history";
import type { DesignerState } from "./types";

export type Listener = (state: DesignerState) => void;

export class Store {
  private readonly history: History<DesignerState>;
  private listeners: Listener[] = [];

  constructor(initial: DesignerState) {
    this.history = new History(initial);
  }

  get state(): DesignerState {
    return this.history.current;
  }

  // Record one undo step. Use when an interaction finishes (pointerup
  // after a drag, button click, keyboard shortcut).
  commit(next: DesignerState): void {
    this.history.push(next);
    this.notify();
  }

  // Update transient state without recording. Use during a continuous
  // gesture (pointermove frames). The caller should commit(final) when
  // the gesture ends so one gesture = one undo step.
  replace(next: DesignerState): void {
    this.history.replace(next);
    this.notify();
  }

  // Promote the current (transient) state to the baseline. Clears
  // undo history. See History.seed — used by auto-layout after the
  // synchronous preset fanout so the initial populated layout is the
  // zero-point for undo, not the pre-preset blank.
  seed(): void {
    this.history.seed();
    this.notify();
  }

  undo(): void {
    if (this.history.undo() !== null) this.notify();
  }

  redo(): void {
    if (this.history.redo() !== null) this.notify();
  }

  canUndo(): boolean {
    return this.history.canUndo();
  }

  canRedo(): boolean {
    return this.history.canRedo();
  }

  subscribe(fn: Listener): () => void {
    this.listeners.push(fn);
    return () => {
      this.listeners = this.listeners.filter((f) => f !== fn);
    };
  }

  private notify(): void {
    for (const fn of this.listeners) fn(this.state);
  }
}
