// Reach reporting (GH #238). On a winner's viewable impression, report at
// most once per campaign per day: the day this browser last saw the
// campaign on this site (or "never") and a one-time freshness value. The
// server counts reports per days-since; unique browsers for any date range
// fall out of that (Chrome updater protocol's client-regulated counting).
// No identifier is created or sent.
import { takeReachReport } from "./dogear-storage.js";

/** Per-winner reach info from the serve response (ServeRes.reach). */
export interface ReachWire {
  campaignId: string;
  day: number; // advertiser-local epoch day, signed into url
  url: string; // signed beacon URL; we append prev + fresh
}

export async function reportReach(r: ReachWire): Promise<void> {
  const report = await takeReachReport(r.campaignId, r.day);
  if (!report) return; // already reported today, or no storage
  // 1×1 pixel, the same transport as the impression beacon it follows.
  const px = new Image();
  px.src = `${r.url}&prev=${report.prev ?? "never"}&fresh=${report.fresh}`;
}
