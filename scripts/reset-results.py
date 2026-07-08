#!/usr/bin/env python3
"""Reset PromoVolve runtime results (tracking events, stats, daily spend
counters) WITHOUT touching the test fixture (publishers, advertisers,
campaigns, creatives, approvals).

Two-phase clean:

  Phase 1 — DB tables (TRUNCATE via docker exec):
    - tracking_events                          (62K rows of impression history)
    - creative_stats, creative_stats_snapshot
    - campaign_stats, campaign_hourly_stats, campaign_daily_stats
    - advertiser_summary                       (rollup aggregates)
    - traffic_shape_snapshot
    - projection_offset_store                  (reset offset to 0)

  Phase 2 — Entity counters (HTTP via running cluster):
    - Discover all (publisher, site) pairs and all (advertiser, campaign)
      pairs by walking the public API.
    - POST /v1/publishers/{pub}/sites/{site}/pacing/reset for each site,
      passing every campaign that belongs to any advertiser. The
      `resetPacingLogic` handler then fans `ResetDayStart` to both the
      CampaignEntity and the parent AdvertiserEntity so the dashboard's
      `Spent Today` zeros alongside per-campaign `spent`.

What this script does NOT touch:
    - durable_state (entity state — publisher, site, advertiser, campaigns)
    - creative / approved_creative / pending_selection (creatives + approvals)
    - advertiser_email / publisher_email (login)
    - advertiser_asset / image_asset (creative blobs)

Requires the cluster to be UP on http://localhost:8080 and Docker
postgres named `promovolve-db` to be running.

Usage:
  scripts/reset-results.py
  scripts/reset-results.py --api http://localhost:8080
  scripts/reset-results.py --skip-db        # phase 2 only
  scripts/reset-results.py --skip-pacing    # phase 1 only
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.request
from typing import Any


# DB tables to TRUNCATE. `projection_offset_store` is reset (not truncated)
# so the projector keeps its row schema but re-reads from offset 0.
RESULT_TABLES = (
    "tracking_events",
    "creative_stats",
    "creative_stats_snapshot",
    "campaign_stats",
    "campaign_hourly_stats",
    "campaign_daily_stats",
    "advertiser_summary",
    "traffic_shape_snapshot",
)


def log(msg: str) -> None:
    print(f"[reset] {msg}", flush=True)


def truncate_db() -> None:
    sql = ";\n".join(f"TRUNCATE {t}" for t in RESULT_TABLES) + ";\n"
    sql += "UPDATE projection_offset_store SET current_offset = 0;\n"
    log(f"truncating {len(RESULT_TABLES)} tables + resetting projection offset")
    subprocess.run(
        ["docker", "exec", "-i", "promovolve-db", "psql", "-U", "promovolve", "-d", "promovolve", "-q"],
        input=sql.encode(),
        check=True,
    )


def http_json(url: str, method: str = "GET", body: dict[str, Any] | None = None) -> Any:
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"} if body else {},
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def fetch_publishers_via_db() -> list[str]:
    """The cluster has no `list publishers` endpoint by design (see
    EndpointRoutes.scala). Read the email-mapping table directly — it's the
    same source the dashboard uses to resolve logins."""
    out = subprocess.run(
        [
            "docker", "exec", "promovolve-db", "psql",
            "-U", "promovolve", "-d", "promovolve",
            "-At", "-c", "SELECT publisher_id FROM publisher_email;",
        ],
        capture_output=True, text=True, check=True,
    ).stdout
    return [line.strip() for line in out.splitlines() if line.strip()]


def fetch_advertisers_via_db() -> list[str]:
    out = subprocess.run(
        [
            "docker", "exec", "promovolve-db", "psql",
            "-U", "promovolve", "-d", "promovolve",
            "-At", "-c", "SELECT advertiser_id FROM advertiser_email;",
        ],
        capture_output=True, text=True, check=True,
    ).stdout
    return [line.strip() for line in out.splitlines() if line.strip()]


def fetch_sites(api: str, pub: str) -> list[str]:
    res = http_json(f"{api}/v1/publishers/{pub}/sites")
    return [s["id"] for s in res.get("data", [])]


def fetch_campaigns(api: str, advertiser_id: str) -> list[dict[str, str]]:
    res = http_json(f"{api}/v1/advertisers/{advertiser_id}/campaigns?limit=100")
    return [
        {"advertiserId": advertiser_id, "campaignId": c["id"]}
        for c in res.get("data", [])
    ]


def wait_for_cluster(api: str, timeout_s: int = 60) -> None:
    deadline = time.monotonic() + timeout_s
    last_err: Exception | None = None
    while time.monotonic() < deadline:
        try:
            urllib.request.urlopen(f"{api}/v1/publishers/__nonexistent/sites", timeout=2)
            return
        except urllib.error.HTTPError:
            # Any HTTP response = cluster is up
            return
        except Exception as e:
            last_err = e
            time.sleep(1)
    raise RuntimeError(f"cluster did not respond on {api} within {timeout_s}s: {last_err}")


def reset_pacing(api: str) -> None:
    log("discovering publishers + advertisers via DB email tables")
    publishers = fetch_publishers_via_db()
    advertisers = fetch_advertisers_via_db()
    log(f"  publishers={len(publishers)} advertisers={len(advertisers)}")

    # Build the full campaign list once — every campaign across every
    # advertiser. The pacing/reset endpoint accepts any (adv, camp) pair
    # regardless of which site is in the URL path; the fan-out targets
    # entities by ID, not by route.
    all_campaigns: list[dict[str, str]] = []
    for adv in advertisers:
        camps = fetch_campaigns(api, adv)
        all_campaigns.extend(camps)
        log(f"  advertiser {adv}: {len(camps)} campaigns")

    if not all_campaigns:
        log("no campaigns found — nothing to reset")
        return

    # Find at least one (pub, site) to address the reset call to.
    # The endpoint route requires both; the body is the actual reset list.
    addressed = False
    for pub in publishers:
        sites = fetch_sites(api, pub)
        for site in sites:
            log(f"POST pacing/reset on {pub}/{site} with {len(all_campaigns)} campaigns")
            res = http_json(
                f"{api}/v1/publishers/{pub}/sites/{site}/pacing/reset",
                method="POST",
                body={"campaigns": all_campaigns},
            )
            log(f"  resetCount={res.get('resetCount')}")
            addressed = True
            break  # one site is enough — advertiser-level fan-out is global
        if addressed:
            break

    if not addressed:
        log("no (publisher, site) found to address the reset — skipping pacing/reset")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--api", default="http://localhost:8080", help="Core API base URL")
    p.add_argument("--skip-db", action="store_true", help="Skip Phase 1 (DB truncate)")
    p.add_argument("--skip-pacing", action="store_true", help="Skip Phase 2 (entity counter reset)")
    args = p.parse_args()

    if not args.skip_db:
        truncate_db()
    else:
        log("Phase 1 skipped (--skip-db)")

    if not args.skip_pacing:
        log("waiting for cluster on " + args.api)
        wait_for_cluster(args.api)
        reset_pacing(args.api)
    else:
        log("Phase 2 skipped (--skip-pacing)")

    log("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
