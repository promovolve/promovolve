// Dog-ear serve-response handling, extracted from bootstrap.ts so the
// client half of the pin-cleanup contract is unit-testable (the owed
// IDB-cleanup-on-revoke verification, closed 2026-07-13). Behavior is
// verbatim from the bootstrap: pin-honored serves get their beacons
// flagged dogeared=1 ($0 re-encounter, excluded from primary metrics);
// a `creative_removed` signal — which the server only emits for true
// revocations, never transient pool misses — clears the IndexedDB pin
// and the fold-count dedup record; every other reason retains the pin
// (the campaign may resume).

import { clearCounted, clearPin, touchPin, type Pin } from "./dogear-storage.js";

export interface DogearSignal {
  honored: boolean;
  reason?: string;
}

/** The one condition that may clear a pin from a serve response. */
export function isCreativeRemoved(dogear: DogearSignal | undefined | null): boolean {
  return !!dogear && dogear.honored === false && dogear.reason === "creative_removed";
}

export function withDogearedFlag(impUrl: string): string {
  if (impUrl.indexOf("dogeared=") >= 0) return impUrl;
  const sep = impUrl.indexOf("?") >= 0 ? "&" : "?";
  return `${impUrl}${sep}dogeared=1`;
}

/** Clear the pin + its fold-count dedup record for a slot whose serve
 *  response signalled creative_removed. No-op for any other signal. */
export async function clearRemovedPin(
  slotId: string,
  dogear: DogearSignal | undefined | null,
  pin: Pin | undefined,
): Promise<boolean> {
  if (!isCreativeRemoved(dogear)) return false;
  await clearPin(slotId);
  if (pin) await clearCounted(pin.creativeId);
  return true;
}

/** Process the dogear field on a serve response. Returns the (possibly
 *  modified) beacon URLs and the pinned-page index to forward to the
 *  banner — null if there was no pin or if the pin was cleared.
 *
 *  Side effect: clears the IndexedDB pin if the response signals the
 *  creative is no longer servable (`reason === "creative_removed"`,
 *  per the v1 server-side simplification). Other reasons retain the
 *  pin (campaign may resume).
 */
export async function processDogearResponse(
  slotId: string,
  winner: { impUrl: string; clickUrl: string; ctaUrl: string; dogear?: DogearSignal },
  pin: Pin | undefined,
): Promise<{ impUrl: string; clickUrl: string; ctaUrl: string; pinnedPage: number | null }> {
  let impUrl = winner.impUrl;
  let clickUrl = winner.clickUrl;
  let ctaUrl = winner.ctaUrl;
  let pinnedPage: number | null = null;

  const dogear = winner.dogear;
  if (dogear) {
    if (dogear.honored) {
      // Pin honored — flag every beacon URL as a re-encounter so the
      // server suppresses metrics for this serve (the user already
      // saw the creative when they folded it; this is bookmark
      // fulfillment, not a billable re-impression).
      impUrl = withDogearedFlag(impUrl);
      clickUrl = withDogearedFlag(clickUrl);
      ctaUrl = withDogearedFlag(ctaUrl);
      if (pin) pinnedPage = pin.page;
      // Reader visited the pinned creative again — bump lastSeenAt so
      // the idle-sweep timer resets. Without this, a reader who keeps
      // re-encountering the pin would still lose it after 24h because
      // the field never refreshes.
      void touchPin(slotId);
    } else {
      await clearRemovedPin(slotId, dogear, pin);
    }
  }

  return { impUrl, clickUrl, ctaUrl, pinnedPage };
}
