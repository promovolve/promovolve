"""Revenue measurement for the test harness.

Queries the `tracking_events` projection table directly via `docker exec
... psql` to compute per-campaign revenue and clearing CPM over the
run's time window. Doing this in Python without a psycopg2 dependency
keeps the harness portable — anyone with the dev cluster running can
also query the DB.

The result is written to summary.json so the comparison script
(compare.py) can read it back without re-querying."""
from __future__ import annotations

import json
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Any


def query_revenue(start_utc: datetime, end_utc: datetime, site_id: str | None = None) -> dict[str, Any]:
    """Returns per-campaign and aggregate revenue/imps/avg-CPM for the
    run window. `start_utc` and `end_utc` are inclusive/exclusive
    timezone-aware datetimes. `site_id` optional — filters the events."""
    site_filter = f"AND site_id = '{site_id}'" if site_id else ""
    sql = f"""
SELECT campaign_id,
       count(*)                                  AS imps,
       round(avg(cpm)::numeric, 4)               AS avg_cpm,
       round(sum(cpm / 1000.0)::numeric, 6)      AS revenue_usd
FROM tracking_events
WHERE event_type = 'impression'
  AND event_time >= '{start_utc.isoformat()}'
  AND event_time <  '{end_utc.isoformat()}'
  {site_filter}
GROUP BY campaign_id
ORDER BY revenue_usd DESC;
""".strip()

    out = subprocess.run(
        ["docker", "exec", "promovolve-db",
         "psql", "-U", "promovolve", "-d", "promovolve",
         "-F", "\t", "-A", "-t", "-c", sql],
        capture_output=True, text=True, check=False,
    )
    if out.returncode != 0:
        return {"error": out.stderr.strip(), "per_campaign": [], "totals": {}}

    per_campaign: list[dict[str, Any]] = []
    total_imps = 0
    total_revenue = 0.0
    for line in out.stdout.strip().splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 4:
            continue
        cid, imps_s, avg_cpm_s, rev_s = parts[0], parts[1], parts[2], parts[3]
        imps = int(imps_s)
        revenue = float(rev_s)
        per_campaign.append({
            "campaign_id": cid,
            "impressions": imps,
            "avg_cpm":     float(avg_cpm_s),
            "revenue_usd": revenue,
        })
        total_imps += imps
        total_revenue += revenue

    return {
        "per_campaign":     per_campaign,
        "totals": {
            "impressions":  total_imps,
            "revenue_usd":  round(total_revenue, 6),
            "avg_cpm":      round(total_revenue * 1000.0 / total_imps, 4) if total_imps > 0 else 0.0,
        },
        "window": {
            "start": start_utc.isoformat(),
            "end":   end_utc.isoformat(),
        },
    }
