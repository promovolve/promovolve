#!/usr/bin/env python3
"""RL floor-agent cluster test driver.

Reads a scenario YAML, configures the running cluster, drives traffic via
the existing Go `simulate-traffic` binary, polls `/floor-observations`
into a JSONL file, fires scheduled events, then stops cleanly.

Doesn't create advertisers/campaigns — they must already exist on the
target publisher's site. Scenarios that reference `campaignId` fields
must use real IDs from the running cluster.
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests
import yaml

from bidder_response import BidderResponder, patch_max_cpm
from floor_strategy import FixedFloorEnforcer
from revenue import query_revenue


# ---------------------------------------------------------------- helpers

def utc_now_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%S")


def log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def patch_site(api: str, pub: str, site: str, body: dict[str, Any]) -> None:
    r = requests.patch(f"{api}/v1/publishers/{pub}/sites/{site}", json=body, timeout=10)
    r.raise_for_status()


def put_pacing(api: str, pub: str, site: str, day_duration: int) -> None:
    r = requests.put(
        f"{api}/v1/publishers/{pub}/sites/{site}/pacing",
        json={"dayDurationSeconds": day_duration},
        timeout=10,
    )
    r.raise_for_status()


def set_campaign_status(api: str, adv: str, camp: str, status: str) -> None:
    r = requests.put(
        f"{api}/v1/advertisers/{adv}/campaigns/{camp}/status",
        json={"status": status},
        timeout=10,
    )
    r.raise_for_status()


def reset_floor_agent(api: str, pub: str, site: str) -> None:
    """Wipe the persisted floor-sweep snapshot and rebuild the optimizer
    fresh (phase counters reset, no carried-over candidate history). Use
    when the optimizer has drifted and you want a clean start for a run."""
    r = requests.post(
        f"{api}/v1/publishers/{pub}/sites/{site}/reset-floor-agent",
        timeout=10,
    )
    r.raise_for_status()


# ---------------------------------------------------------------- fixture

def load_fixture(path: Path) -> dict[str, Any]:
    """Load the YAML produced by setup-fixture.py."""
    fx = yaml.safe_load(path.read_text())
    # Minimal shape check — fail loudly if a stale or hand-edited fixture
    # is missing a required field, since the alternative is a confusing
    # KeyError deep inside the scenario.
    for k in ("publisher", "site", "advertiser", "campaigns"):
        if k not in fx:
            raise ValueError(f"fixture {path} missing top-level field: {k}")
    return fx


def preflight_serve_batch(api: str, site_id: str, seed_url: str, slot_id: str) -> None:
    """Hit /v1/serve/batch once before traffic starts. A winner-less
    response means the cluster verified the site but has no live
    candidates — empty ServeIndex, no approved creatives, classify
    never reached the auctioneer, etc. Better to abort here with a
    clear message than to discover it 15 minutes into a run.

    Lesson from 2026-05-20: `verificationStatus: verified` is a host
    check, not a serve check, so the only reliable signal is an actual
    serve poll."""
    body = {
        "pub": site_id,
        "url": seed_url,
        "imp": [{"id": slot_id, "w": 300, "h": 250}],
    }
    r = requests.post(f"{api}/v1/serve/batch", json=body, timeout=10,
                      headers={"User-Agent": "rl-test-runner/preflight"})
    if r.status_code == 204:
        raise RuntimeError(
            f"preflight: /serve/batch returned 204 (batch content too old) — "
            f"cluster appears uninitialized for site={site_id} url={seed_url}")
    if not r.ok:
        raise RuntimeError(
            f"preflight: /serve/batch failed: HTTP {r.status_code} {r.text}")
    payload = r.json()
    seatbids = payload.get("seatbid") or []
    winners = [s.get("winner") for s in seatbids if s.get("winner")]
    if not winners:
        raise RuntimeError(
            f"preflight: /serve/batch returned no winners for site={site_id} "
            f"url={seed_url} slot={slot_id} — ServeIndex empty or campaigns "
            f"excluded. Re-run scripts/rl-test/setup-fixture.py and try again.")
    log(f"preflight ok: /serve/batch returned {len(winners)} winner(s) "
        f"(first cpm=${winners[0].get('cpm', 0):.2f})")


def resolve_bidder_campaigns(scenario_campaigns: list[dict[str, Any]],
                             fixture: dict[str, Any] | None) -> list[dict[str, Any]]:
    """Each entry in `bidderResponse.campaigns` may be either:
      (a) fully explicit:  {advertiserId, campaignId, currentMaxCpm, ...}
      (b) fixture-handle:  {maxCpm, raiseStep, ceiling}  (no IDs)

    Form (b) is resolved against the loaded fixture by matching `maxCpm`
    to a fixture campaign and filling in advertiserId+campaignId+
    currentMaxCpm. The two forms can be mixed in the same scenario.
    """
    resolved: list[dict[str, Any]] = []
    fx_campaigns = (fixture or {}).get("campaigns", [])
    fx_adv_id = (fixture or {}).get("advertiser", {}).get("id")
    by_cpm = {float(c["maxCpm"]): c for c in fx_campaigns}

    for entry in scenario_campaigns:
        out = dict(entry)
        if "campaignId" not in out:
            if fixture is None:
                raise ValueError(
                    "bidderResponse entry has no campaignId and no --fixture was passed: "
                    f"{entry}")
            cpm_key = float(entry["maxCpm"])
            fx = by_cpm.get(cpm_key)
            if fx is None:
                raise ValueError(
                    f"fixture has no campaign with maxCpm={cpm_key}; "
                    f"available={sorted(by_cpm)}")
            out["advertiserId"]  = fx_adv_id
            out["campaignId"]    = fx["id"]
            out["currentMaxCpm"] = cpm_key
        resolved.append(out)
    return resolved


# ---------------------------------------------------------------- traffic

def start_traffic(scenario: dict, site_id: str, site_url: str, api: str, log_path: Path) -> subprocess.Popen:
    """Spawn the Go simulate-traffic binary in the background.

    We invoke `go run` so callers don't need a pre-built binary; the
    first call compiles once and then re-uses the cache.

    The `-pub` flag is named confusingly: simulate-traffic sends it as
    the `Pub` field on /serve/batch, which the server treats as the
    site ID. Pass site_id here, not publisher ID."""
    t = scenario["traffic"]
    cmd = [
        "go", "run", "./cmd/simulate-traffic",
        "-pub", site_id,
        "-api", f"{api}/v1",
        "-site", site_url,
        "-workers", str(t["workers"]),
        "-interval", f"{t['intervalSeconds']}s",
        "-ctr", str(t["ctr"]),
    ]
    log(f"starting traffic: {' '.join(cmd)}")
    f = open(log_path, "w")
    # Run from the platform/ dir so `./cmd/simulate-traffic` resolves.
    platform_dir = Path(__file__).resolve().parents[2] / "platform"
    return subprocess.Popen(cmd, stdout=f, stderr=subprocess.STDOUT, cwd=platform_dir)


# ---------------------------------------------------------------- polling

class ObservationPoller(threading.Thread):
    """Polls /floor-observations on a schedule, dedupes by `ts`, appends
    new rows to a JSONL file. Runs as a daemon thread."""

    def __init__(self, api: str, pub: str, site: str, jsonl_path: Path, interval: float):
        super().__init__(daemon=True)
        self.api = api
        self.pub = pub
        self.site = site
        self.jsonl_path = jsonl_path
        self.interval = interval
        self.stop_event = threading.Event()
        self.seen_ts: set[str] = set()
        self.written = 0

    def stop(self) -> None:
        self.stop_event.set()

    def run(self) -> None:
        url = (
            f"{self.api}/v1/publishers/{self.pub}/sites/{self.site}"
            "/floor-observations?limit=100"
        )
        with open(self.jsonl_path, "a") as out:
            while not self.stop_event.is_set():
                try:
                    r = requests.get(url, timeout=10)
                    r.raise_for_status()
                    obs = r.json().get("observations", [])
                    # API returns newest first — reverse so JSONL stays
                    # in chronological order across appends.
                    fresh = [o for o in reversed(obs) if o["ts"] not in self.seen_ts]
                    for o in fresh:
                        out.write(json.dumps(o) + "\n")
                        self.seen_ts.add(o["ts"])
                        self.written += 1
                    if fresh:
                        out.flush()
                except Exception as e:
                    log(f"poll error: {e}")
                self.stop_event.wait(self.interval)


# ---------------------------------------------------------------- events

def fire_event(api: str, ev: dict) -> None:
    action = ev["action"]
    if action == "pauseCampaign":
        log(f"event: pause campaign {ev['campaignId']}")
        set_campaign_status(api, ev["advertiserId"], ev["campaignId"], "paused")
    elif action == "resumeCampaign":
        log(f"event: resume campaign {ev['campaignId']}")
        set_campaign_status(api, ev["advertiserId"], ev["campaignId"], "active")
    elif action == "setCampaignMaxCpm":
        # Simulates a bidder upping their maxCpm — useful for testing
        # "a new high bidder appeared" without having to provision an
        # extra campaign. PATCHes bidding.maxCpm directly.
        new_max = float(ev["maxCpm"])
        log(f"event: set campaign {ev['campaignId']} maxCpm -> ${new_max:.2f}")
        patch_max_cpm(api, ev["advertiserId"], ev["campaignId"], new_max)
    else:
        log(f"event: UNKNOWN action {action} — skipping")


# ---------------------------------------------------------------- main

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("scenario", type=Path, help="Path to scenario YAML")
    p.add_argument("--api",      default=None, help="Core API base (overrides fixture)")
    p.add_argument("--site-url", default=None, help="Publisher site base URL for traffic gen (overrides fixture)")
    p.add_argument("--fixture",  type=Path,
                   default=Path(__file__).resolve().parent / "fixtures" / "dev.yaml",
                   help="Path to fixture YAML (output of setup-fixture.py). "
                        "Provides publisher/site/advertiser/campaign IDs.")
    p.add_argument("--publisher", default=None, help="Publisher ID (overrides fixture)")
    p.add_argument("--site",      default=None, help="Site ID (overrides fixture)")
    p.add_argument("--out-root", type=Path, default=Path(__file__).resolve().parent / "runs")
    p.add_argument("--plot", action="store_true", help="Run plot.py after the scenario finishes")
    p.add_argument("--skip-preflight", action="store_true",
                   help="Skip the /serve/batch preflight check (use only when "
                        "intentionally probing an uninitialized cluster).")
    args = p.parse_args()

    # --- resolve fixture / overrides ------------------------------------
    fixture: dict[str, Any] | None = None
    if args.fixture and args.fixture.exists():
        fixture = load_fixture(args.fixture)
        log(f"loaded fixture {args.fixture}")
    elif args.publisher and args.site:
        log("no fixture loaded; running from explicit --publisher/--site flags")
    else:
        p.error(f"fixture not found at {args.fixture} and --publisher/--site "
                f"not provided. Run scripts/rl-test/setup-fixture.py first, "
                f"or pass --publisher and --site explicitly.")

    api       = args.api       or (fixture and fixture.get("api"))      or "http://localhost:8080"
    site_url  = args.site_url  or (fixture and fixture.get("siteUrl"))  or "http://localhost:8888"
    publisher = args.publisher or (fixture and fixture["publisher"]["id"])
    site_id   = args.site      or (fixture and fixture["site"]["id"])
    if not publisher or not site_id:
        p.error("publisher/site IDs unresolved — fixture missing fields and "
                "flags not provided.")

    scenario = yaml.safe_load(args.scenario.read_text())
    name = scenario["name"]
    run_dir = args.out_root / name / utc_now_stamp()
    run_dir.mkdir(parents=True, exist_ok=True)
    log(f"run dir: {run_dir}")
    log(f"target: api={api} site_url={site_url} publisher={publisher} site={site_id}")

    # Persist the resolved scenario alongside outputs for reproducibility.
    (run_dir / "scenario.yaml").write_text(args.scenario.read_text())

    # --- configure the site --------------------------------------------
    s = scenario["site"]
    site_patch = {
        "floorCpm":    str(s["initialFloorCpm"]),
        "minFloorCpm": str(s["minFloorCpm"]),
        "bidWeight":   str(s["bidWeight"]),
    }
    log(f"configuring site: {site_patch}")
    patch_site(api, publisher, site_id, site_patch)
    put_pacing(api, publisher, site_id, s["pacingDayDurationSeconds"])

    # Optional one-shot reset BEFORE traffic starts. Use when the sweep
    # optimizer has drifted and you want a clean start (phase counters
    # and candidate history wiped) for this run.
    if s.get("resetFloorAgent"):
        log("resetting floor optimizer (wipes sweep snapshot, fresh start)")
        reset_floor_agent(api, publisher, site_id)

    # Preflight: make sure /serve/batch actually returns a winner before
    # we start 90 minutes of traffic. Skipped if no fixture (we don't
    # know the seed URL) or if the user explicitly opts out.
    if fixture and not args.skip_preflight:
        seed_url = fixture["site"]["seedUrl"]
        slot_id  = fixture["site"]["slotId"]
        preflight_serve_batch(api, site_id, seed_url, slot_id)

    # Capture the run-window start so the end-of-run revenue query
    # only counts impressions cleared during this scenario.
    run_started_utc = datetime.now(timezone.utc)

    # --- start traffic --------------------------------------------------
    traffic_log = run_dir / "traffic.log"
    traffic_proc = start_traffic(scenario, site_id, site_url, api, traffic_log)

    # --- start observation poller --------------------------------------
    poller = ObservationPoller(
        api=api, pub=publisher, site=site_id,
        jsonl_path=run_dir / "observations.jsonl",
        interval=scenario.get("pollIntervalSeconds", 5),
    )
    poller.start()

    # --- optional fixed-floor enforcer ---------------------------------
    # Lets us run "what if the floor were pinned at X" as a counterfactual
    # to the RL strategy. The agent still runs but its decisions are
    # silently overwritten by this thread re-PATCHing every few seconds.
    floor_enforcer = None
    fs = scenario.get("floorStrategy") or {"kind": "rl"}
    if fs.get("kind") == "fixed":
        target = float(fs["value"])
        floor_enforcer = FixedFloorEnforcer(
            api=api, pub=publisher, site=site_id,
            target=target,
            interval_seconds=fs.get("intervalSeconds", 3.0),
        )
        log(f"floor strategy: FIXED at ${target:.2f} (enforcer patches every {floor_enforcer.interval}s)")
        floor_enforcer.start()
    else:
        # Any non-`fixed` kind ("rl", "sweep", ...) means the in-cluster
        # optimizer drives the floor. The runner is passive — it just
        # observes. Which optimizer is actually active depends on the
        # cluster's `promovolve.floor-optimizer.mode` config, not on
        # anything in the scenario file.
        log(f"floor strategy: {fs.get('kind', 'rl')} (optimizer in control)")

    # --- optional responsive-bidder agent ------------------------------
    # When the scenario declares `bidderResponse`, spawn the thread that
    # watches each campaign's win-rate and raises maxCpm under silence.
    # This is what models real advertiser behaviour and lets us test the
    # endogenous-response claim ("RL forces bidders to raise").
    responder = None
    br = scenario.get("bidderResponse")
    if br:
        resolved = resolve_bidder_campaigns(br["campaigns"], fixture)
        responder = BidderResponder(
            api=api,
            campaigns=resolved,
            check_interval_seconds=br.get("checkIntervalSeconds", 30),
            silence_windows=br.get("silenceWindows", 2),
            events_path=run_dir / "bidder_events.jsonl",
        )
        log(f"bidder responder enabled for {len(resolved)} campaigns "
            f"(check every {responder.check_interval}s, silence threshold {responder.silence_windows} windows)")
        responder.start()

    # --- schedule events ------------------------------------------------
    events = sorted(scenario.get("events") or [], key=lambda e: e["atSeconds"])
    duration = float(scenario["durationSeconds"])
    start = time.monotonic()

    try:
        for ev in events:
            wait = ev["atSeconds"] - (time.monotonic() - start)
            if wait > 0:
                time.sleep(wait)
            fire_event(api, ev)

        remaining = duration - (time.monotonic() - start)
        if remaining > 0:
            log(f"events done, waiting {remaining:.0f}s more for duration to elapse")
            time.sleep(remaining)
    except KeyboardInterrupt:
        log("interrupted, shutting down")
    finally:
        log("stopping poller and traffic")
        poller.stop()
        poller.join(timeout=10)
        if responder is not None:
            responder.stop()
            responder.join(timeout=10)
            log(f"responder did {responder.raises} bid raises across the run")
        if floor_enforcer is not None:
            floor_enforcer.stop()
            floor_enforcer.join(timeout=10)
            log(f"floor enforcer issued {floor_enforcer.patches} patches across the run")
        try:
            traffic_proc.send_signal(signal.SIGINT)
            traffic_proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            traffic_proc.kill()

    # Capture the run-window end. Used by the revenue query below — we
    # want to count impressions that landed in the auction during this
    # scenario, not earlier setup or later residual traffic.
    run_ended_utc = datetime.now(timezone.utc)
    log(f"querying tracking_events for revenue between {run_started_utc.isoformat()} and {run_ended_utc.isoformat()}")
    revenue = query_revenue(run_started_utc, run_ended_utc, site_id=site_id)
    log(f"revenue: ${revenue['totals'].get('revenue_usd', 0):.4f}  "
        f"imps={revenue['totals'].get('impressions', 0)}  "
        f"avg_cpm=${revenue['totals'].get('avg_cpm', 0):.4f}")

    # --- summary -------------------------------------------------------
    summary = {
        "scenario": name,
        "publisher": publisher,
        "site": site_id,
        "duration_seconds": duration,
        "observations_written": poller.written,
        "bidder_raises": responder.raises if responder else 0,
        "floor_strategy": fs,
        "floor_patches": floor_enforcer.patches if floor_enforcer else 0,
        "revenue": revenue,
        "started_at": run_dir.name,
        "finished_at": utc_now_stamp(),
    }
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2))
    log(f"wrote {poller.written} observations → {run_dir / 'observations.jsonl'}")

    if args.plot:
        log("rendering plots")
        plot_script = Path(__file__).resolve().parent / "plot.py"
        subprocess.run([sys.executable, str(plot_script), str(run_dir)], check=False)

    log(f"done: {run_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
