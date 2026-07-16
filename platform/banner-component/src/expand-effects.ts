// Per-effect keyframes + CSS rules for the open/close transitions.
// Injected once into the shadow DOM at expand time (renderOverlay
// in banner.ts). The CSS is its own module so banner.ts stays
// focused on the lifecycle/render logic instead of carrying a 140-
// line CSS block in a template literal.
//
// Adding a new effect is mechanical:
//   1. Add a new entry to EXPAND_EFFECTS in types.ts.
//   2. Add @keyframes here.
//   3. Add `.expand-wrapper.expand-effect-NAME { animation: ... }`
//      and `.expand-wrapper.expand-effect-NAME-closing { ... }` rules.
// No banner.ts edit needed — the wrapper class is computed from the
// config field name.
//
// Every animation name starts with `expand-effect-` so the
// animationend handler in renderOverlay can filter — pseudoelement
// keyframes use different prefixes (crt-flash, crt-scanlines) to
// avoid being mistaken for the wrapper-level finish event.
//
// All effect rules read --expand-duration from the wrapper. The
// fallback inside var() is the effect's natural timing. Setting
// --expand-duration inline on the wrapper overrides every linked
// animation (including CRT pseudoelements) at once, so synced
// effects stay synced when the user dials duration up or down.

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
  @keyframes expand-effect-crt-power-on-anim {
    0%   { transform: scaleY(0.005); filter: brightness(1.4); }
    12%  { transform: scaleY(0.005); filter: brightness(2.2) blur(0.5px); }
    22%  { transform: scaleY(0.005); filter: brightness(2.0); }
    77%  { transform: scaleY(1);     filter: brightness(1.4); }
    100% { transform: scaleY(1);     filter: brightness(1.0); }
  }
  /* Power-off: brief flare, snap to a horizontal line at vertical
   * center, dim the line, then fade to black. Mirrors the on-effect
   * symbolically (line ↔ full frame) but isn't its literal reverse —
   * an actual reverse would start with a flash that feels wrong as a
   * close gesture. */
  @keyframes expand-effect-crt-power-off-anim {
    0%   { transform: scaleY(1);     filter: brightness(1.0); opacity: 1; }
    18%  { transform: scaleY(1);     filter: brightness(1.6) blur(0.4px); opacity: 1; }
    35%  { transform: scaleY(0.005); filter: brightness(2.0); opacity: 1; }
    72%  { transform: scaleY(0.005); filter: brightness(0.6); opacity: 1; }
    100% { transform: scaleY(0);     filter: brightness(0);   opacity: 0; }
  }
  @keyframes crt-rgb-split {
    0%, 22% { opacity: 0; transform: translateX(0); }
    30%     { opacity: 0.6; }
    77%     { opacity: 0; }
    100%    { opacity: 0; }
  }
  @keyframes crt-flash {
    0%, 8%  { opacity: 0; }
    14%     { opacity: 0.6; }
    22%     { opacity: 0; }
    100%    { opacity: 0; }
  }
  @keyframes crt-scanlines {
    0%, 77% { opacity: 0; }
    85%     { opacity: 0.18; }
    100%    { opacity: 0; }
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
  .expand-wrapper.expand-effect-crt-power-on {
    animation: expand-effect-crt-power-on-anim var(--expand-duration, 650ms) cubic-bezier(0.2,0.8,0.2,1) both;
  }
  .expand-wrapper.expand-effect-crt-power-on::before,
  .expand-wrapper.expand-effect-crt-power-on::after {
    content: "";
    position: absolute;
    inset: 0;
    pointer-events: none;
    z-index: 50;
  }
  /* RGB split + scanline overlay (red channel + scanlines) */
  .expand-wrapper.expand-effect-crt-power-on::before {
    background: repeating-linear-gradient(
      to bottom,
      rgba(255,255,255,0) 0px,
      rgba(255,255,255,0) 2px,
      rgba(0,0,0,0.4) 2px,
      rgba(0,0,0,0.4) 3px
    );
    mix-blend-mode: multiply;
    animation: crt-scanlines var(--expand-duration, 650ms) cubic-bezier(0.2,0.8,0.2,1) both;
  }
  /* White-flash overlay for the "tube hits voltage" moment */
  .expand-wrapper.expand-effect-crt-power-on::after {
    background: rgba(255,250,235,1);
    animation: crt-flash var(--expand-duration, 650ms) cubic-bezier(0.2,0.8,0.2,1) both;
  }

  /* Close-side rules — applied by _collapse() swapping the
   * 'expand-effect-NAME' class to 'expand-effect-NAME-closing'
   * so the wrapper picks up a closing animation. fade reverses its
   * open keyframe; CRT uses a dedicated power-off keyframe rather than
   * a literal reverse (a flash at the start of close would feel wrong). */
  /* Each closing rule also matches .overlay: on mobile the close
   * animation runs on the overlay (the fixed, viewport-sized scroll
   * container showing the reader's current page) rather than the
   * wrapper, so a close after scrolling to the last page doesn't flash
   * page 1. See _collapse() in banner.ts. */
  .expand-wrapper.expand-effect-fade-closing,
  .overlay.expand-effect-fade-closing {
    animation: expand-effect-fade-close-anim var(--expand-duration, 400ms) ease forwards;
  }
  .expand-wrapper.expand-effect-crt-power-on-closing,
  .overlay.expand-effect-crt-power-on-closing {
    animation: expand-effect-crt-power-off-anim var(--expand-duration, 650ms) cubic-bezier(0.4,0,0.6,1) forwards;
  }
  .expand-wrapper.expand-effect-crt-power-on-closing::before,
  .expand-wrapper.expand-effect-crt-power-on-closing::after,
  .overlay.expand-effect-crt-power-on-closing::before,
  .overlay.expand-effect-crt-power-on-closing::after {
    /* Reuse the power-on pseudoelements during close too — the
     * scanlines and flash flash again as the screen snaps off. */
    content: "";
    position: absolute;
    inset: 0;
    pointer-events: none;
    z-index: 50;
  }
  .expand-wrapper.expand-effect-crt-power-on-closing::before,
  .overlay.expand-effect-crt-power-on-closing::before {
    background: repeating-linear-gradient(
      to bottom,
      rgba(255,255,255,0) 0px,
      rgba(255,255,255,0) 2px,
      rgba(0,0,0,0.4) 2px,
      rgba(0,0,0,0.4) 3px
    );
    mix-blend-mode: multiply;
    animation: crt-scanlines var(--expand-duration, 650ms) cubic-bezier(0.4,0,0.6,1) reverse forwards;
  }
  .expand-wrapper.expand-effect-crt-power-on-closing::after,
  .overlay.expand-effect-crt-power-on-closing::after {
    background: rgba(255,250,235,1);
    animation: crt-flash var(--expand-duration, 650ms) cubic-bezier(0.4,0,0.6,1) reverse forwards;
  }

  @media (prefers-reduced-motion: reduce) {
    .expand-wrapper {
      animation: expand-effect-fade-anim 200ms ease both !important;
    }
    .expand-wrapper[class*="-closing"],
    .overlay[class*="-closing"] {
      animation: expand-effect-fade-close-anim 200ms ease forwards !important;
    }
    .expand-wrapper.expand-effect-crt-power-on::before,
    .expand-wrapper.expand-effect-crt-power-on::after,
    .expand-wrapper.expand-effect-crt-power-on-closing::before,
    .expand-wrapper.expand-effect-crt-power-on-closing::after,
    .overlay.expand-effect-crt-power-on-closing::before,
    .overlay.expand-effect-crt-power-on-closing::after {
      display: none !important;
    }
  }
`;
