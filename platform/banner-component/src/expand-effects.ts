// Keyframes + CSS rules for the open/close transitions. Injected once
// into the shadow DOM at expand time (renderOverlay in banner.ts).
//
// The open effect is not a choice anymore: the reader always plays the
// kawaraban deal ("stack"). "fade" stays for two internal jobs — the
// prefers-reduced-motion override and the wrapper's post-flight close
// envelope. (The CRT effect and its pseudoelement circus lived here
// until 2026-07-16; git has it if a retro mode ever wants it back.)
//
// Every wrapper-level animation name starts with `expand-effect-` so
// the animationend handler in renderOverlay can filter; per-page
// keyframes (the deal) deliberately use a different prefix.
//
// All effect rules read --expand-duration from the wrapper. The
// fallback inside var() is the effect's natural timing.

export const EXPAND_EFFECT_CSS = `
  @keyframes expand-effect-fade-anim {
    from { opacity: 0; }
    to   { opacity: 1; }
  }
  /* Distinct close keyframes so the browser sees a new animation
   * (rather than treating "reverse direction on the same keyframe
   * name" as a no-op modification of the already-finished open
   * animation, which leaves the wrapper static until the safety
   * timeout removes it). */
  @keyframes expand-effect-fade-close-anim {
    from { opacity: 1; }
    to   { opacity: 0; }
  }


  /* ── stack: the kawaraban deal ─────────────────────────────────
   * The reader is a pile of loose sheets; opening it DEALS them in —
   * each sheet flies in from up-left (the reverse of the lift-and-
   * slide fly-away) and lands on the pile, bottom sheet first, top
   * sheet last. Closing reverses: top sheet leaves first. Per-page
   * keyframe names deliberately do NOT start with "expand-effect-" —
   * they bubble to the wrapper's animationend filter, and only the
   * wrapper-level animation may signal open/close completion. */
  @keyframes stack-deal-in-anim {
    from { transform: translate(-85%, -30%) rotate(-6deg); opacity: 0; }
    30%  { opacity: 1; }
    to   { transform: none; opacity: 1; }
  }
  /* Wrapper animation exists to own the completion event; its duration
   * covers the full stagger so teardown never truncates a sheet. */
  @keyframes expand-effect-stack-anim {
    from { opacity: 0; }
    20%  { opacity: 1; }
    to   { opacity: 1; }
  }
  @keyframes expand-effect-stack-close-anim {
    from { opacity: 1; }
    to   { opacity: 0; }
  }
  .expand-wrapper.expand-effect-stack {
    animation: expand-effect-stack-anim var(--expand-duration, 620ms) ease both;
  }
  .expand-wrapper.expand-effect-stack [class^="page-"],
  .expand-wrapper.expand-effect-stack [class*=" page-"] {
    /* --deal-ms / --deal-stagger come from the paperWeight tempo (set
     * inline by renderOverlay): heavy stock arrives slower. */
    animation: stack-deal-in-anim var(--deal-ms, 360ms) cubic-bezier(0.22, 0.9, 0.3, 1) backwards;
  }
  /* Deal order: bottom sheet first, top (page-0, the current) last. */
  .expand-wrapper.expand-effect-stack .page-2 { animation-delay: 0ms; }
  .expand-wrapper.expand-effect-stack .page-1 { animation-delay: var(--deal-stagger, 90ms); }
  .expand-wrapper.expand-effect-stack .page-0 { animation-delay: calc(var(--deal-stagger, 90ms) * 2); }
  /* Close: a plain quick fade — dealing the sheets OUT read as
   * ceremony; leaving should just get out of the reader's way. */
  .expand-wrapper.expand-effect-stack-closing {
    animation: expand-effect-stack-close-anim var(--expand-duration, 300ms) ease both;
  }

  .expand-wrapper.expand-effect-fade {
    animation: expand-effect-fade-anim var(--expand-duration, 400ms) ease both;
  }

  /* Close-side rules — applied by _collapse() swapping the
   * 'expand-effect-NAME' class to 'expand-effect-NAME-closing'
   * so the wrapper picks up a closing animation. */
  /* Each closing rule also matches .overlay: on mobile the close
   * animation runs on the overlay (the fixed, viewport-sized scroll
   * container showing the reader's current page) rather than the
   * wrapper, so a close after scrolling to the last page doesn't flash
   * page 1. See _collapse() in banner.ts. */
  .expand-wrapper.expand-effect-fade-closing,
  .overlay.expand-effect-fade-closing {
    animation: expand-effect-fade-close-anim var(--expand-duration, 400ms) ease forwards;
  }

  @media (prefers-reduced-motion: reduce) {
    .expand-wrapper {
      animation: expand-effect-fade-anim 200ms ease both !important;
    }
    .expand-wrapper [class^="page-"],
    .expand-wrapper [class*=" page-"] {
      animation: none !important;
    }
    .expand-wrapper[class*="-closing"],
    .overlay[class*="-closing"] {
      animation: expand-effect-fade-close-anim 200ms ease forwards !important;
    }
  }
`;
