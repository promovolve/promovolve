// Mount heartbeat — the tag reporting its own lifecycle so integration
// breakage on real publisher pages becomes visible instead of silent.
//
// One beacon per pageview, after the display pipeline settles (or on
// pagehide if it never did). Four checkpoints:
//
//   script  — the bootstrap executed at all
//   slot    — at least one [data-promovolve-slot] div was found
//   serve   — /v1/serve/batch answered (0 winners still counts: no-fill
//             is economics, not an integration failure)
//   render  — at least one winner painted its shadow DOM
//
// `stage` is the furthest checkpoint reached; `ok` means the
// INTEGRATION is healthy (render reached, or serve answered with
// nothing to render). `reason` is a terse machine token for the panel.
//
// Privacy: carries NOTHING about the reader — no URL, no id, no
// fingerprint. It is the ad instrumenting itself: site id + booleans.
// Same design principle as the dogear event (no credentials).

export interface HeartbeatReport {
  v: 1;
  pub: string;
  stage: "script" | "slot" | "serve" | "render";
  ok: boolean;
  slots: number;
  served: number;
  mounted: number;
  reason?: string;
}

interface HeartbeatState {
  pub: string;
  slots: number;
  serveAnswered: boolean;
  serveReason?: string; // "http_503", "timeout", "network"
  served: number;
  mounted: number;
  sent: boolean;
  endpoint: string;
}

const state: HeartbeatState = {
  pub: "", slots: 0, serveAnswered: false, served: 0, mounted: 0,
  sent: false, endpoint: "",
};

export function hbInit(pub: string, apiBase: string): void {
  state.pub = pub;
  state.endpoint = `${apiBase}/v1/beacon/mount`;
}
export function hbSlots(n: number): void { state.slots = n; }
export function hbServe(answered: boolean, served: number, reason?: string): void {
  state.serveAnswered = answered;
  state.served = served;
  state.serveReason = reason;
}
export function hbMounted(n: number): void { state.mounted = n; }

/** Exported for tests; production callers use hbSend(). */
export function hbCompose(): HeartbeatReport | null {
  if (!state.pub) return null; // unattributable — misconfigured embeds skip
  let stage: HeartbeatReport["stage"] = "script";
  let ok = false;
  let reason: string | undefined;
  if (state.slots > 0) stage = "slot";
  else reason = "no_slots";
  if (stage === "slot") {
    if (state.serveAnswered) stage = "serve";
    else reason = state.serveReason ?? "serve_unreachable";
  }
  if (stage === "serve") {
    if (state.mounted > 0) { stage = "render"; ok = true; }
    else if (state.served === 0) { ok = true; reason = "no_fill"; }
    else reason = "mount_failed";
  }
  return { v: 1, pub: state.pub, stage, ok, slots: state.slots, served: state.served, mounted: state.mounted, reason };
}

/** Fire the beacon once. keepalive fetch first (clean CORS), sendBeacon
 *  fallback — the dogear-event transport, same reasoning. */
export function hbSend(): void {
  if (state.sent) return;
  // apiBase unresolved would target the PUBLISHER's origin — drop.
  if (!/^https?:\/\//.test(state.endpoint)) return;
  const report = hbCompose();
  if (!report) return;
  state.sent = true;
  const payload = JSON.stringify(report);
  try {
    void fetch(state.endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: payload,
      keepalive: true,
      credentials: "omit",
    });
    return;
  } catch { /* fall through */ }
  try {
    if (typeof navigator !== "undefined" && typeof navigator.sendBeacon === "function") {
      navigator.sendBeacon(state.endpoint, new Blob([payload], { type: "application/json" }));
    }
  } catch { /* best-effort — a lost heartbeat is just a lost datapoint */ }
}

/** Flush on pagehide if the settle path never fired (early navigation,
 *  hung serve). Registered once from the bootstrap. */
export function hbArmFlush(): void {
  try {
    addEventListener("pagehide", () => hbSend(), { once: true });
  } catch { /* older engines: settle-path send only */ }
}
