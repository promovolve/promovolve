"""Responsive-bidder agent for the RL test harness.

For each campaign in scope, polls
  GET /v1/advertisers/{adv}/campaigns/{camp}/win-rate
on a schedule. If `impressionsToday` hasn't increased for `silenceWindows`
consecutive checks AND the campaign is still active, it issues
  PATCH /v1/advertisers/{adv}/campaigns/{camp}
with a new `maxCpm` (current + `raiseStep`). Mimics how a real advertiser
reacts to losing the auction: "I'm not winning, I'll pay more."

Hard cap at `ceiling` per campaign so a runaway scenario can't drive
maxCpm to infinity.

Each raise is written one-per-line to `bidder_events.jsonl`:
    {"ts": "...", "campaignId": "...", "from": "8.0", "to": "9.0",
     "reason": "silent_for_2_checks"}

Plot.py can read this file and overlay raise events on the floor plot."""
from __future__ import annotations

import json
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests


def utc_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def fetch_win_rate(api: str, adv: str, camp: str) -> dict[str, Any] | None:
    try:
        r = requests.get(f"{api}/v1/advertisers/{adv}/campaigns/{camp}/win-rate", timeout=5)
        r.raise_for_status()
        return r.json()
    except Exception:
        return None


def patch_max_cpm(api: str, adv: str, camp: str, new_max: float) -> bool:
    try:
        r = requests.patch(
            f"{api}/v1/advertisers/{adv}/campaigns/{camp}",
            json={"bidding": {"strategy": "fixed", "maxCpm": f"{new_max:.4f}"}},
            timeout=5,
        )
        r.raise_for_status()
        return True
    except Exception:
        return False


class BidderResponder(threading.Thread):
    """Polls each tracked campaign; raises maxCpm when the campaign goes
    silent (no new impressions across `silence_windows` consecutive
    check intervals). All raises capped at `ceiling`.

    Tracked campaigns are described as dicts:
        {advertiserId, campaignId, currentMaxCpm, ceiling, raiseStep}
    `currentMaxCpm` is updated in-place after each successful raise.
    """

    def __init__(
        self,
        api: str,
        campaigns: list[dict[str, Any]],
        check_interval_seconds: float,
        silence_windows: int,
        events_path: Path,
    ):
        super().__init__(daemon=True)
        self.api = api
        self.campaigns = campaigns
        self.check_interval = check_interval_seconds
        self.silence_windows = silence_windows
        self.events_path = events_path
        self.stop_event = threading.Event()
        # per campaignId: rolling history of recent impressionsToday values
        self.history: dict[str, list[int]] = {c["campaignId"]: [] for c in campaigns}
        self.raises = 0

    def stop(self) -> None:
        self.stop_event.set()

    def _log_event(self, body: dict[str, Any]) -> None:
        with open(self.events_path, "a") as f:
            f.write(json.dumps(body) + "\n")

    def run(self) -> None:
        # Initial poll to seed history without acting — first raise can
        # only fire after `silence_windows` real intervals.
        for c in self.campaigns:
            wr = fetch_win_rate(self.api, c["advertiserId"], c["campaignId"])
            self.history[c["campaignId"]].append(wr["impressionsToday"] if wr else 0)
        self.stop_event.wait(self.check_interval)

        while not self.stop_event.is_set():
            for c in self.campaigns:
                cid = c["campaignId"]
                wr = fetch_win_rate(self.api, c["advertiserId"], cid)
                imps = wr["impressionsToday"] if wr else 0
                hist = self.history[cid]
                hist.append(imps)
                # Keep only enough history to evaluate silence.
                if len(hist) > self.silence_windows + 1:
                    hist.pop(0)

                # Need silence_windows+1 samples to compute that many
                # deltas; bail if we don't have them yet.
                if len(hist) <= self.silence_windows:
                    continue

                # Silent iff every delta in the last silence_windows is 0.
                recent_deltas = [hist[i + 1] - hist[i] for i in range(-self.silence_windows - 1, -1)]
                if all(d == 0 for d in recent_deltas):
                    new_max = min(c["currentMaxCpm"] + c["raiseStep"], c["ceiling"])
                    if new_max > c["currentMaxCpm"]:
                        ok = patch_max_cpm(self.api, c["advertiserId"], cid, new_max)
                        if ok:
                            self._log_event({
                                "ts": utc_iso(),
                                "campaignId": cid,
                                "from": f"{c['currentMaxCpm']:.4f}",
                                "to":   f"{new_max:.4f}",
                                "reason": f"silent_for_{self.silence_windows}_checks",
                            })
                            c["currentMaxCpm"] = new_max
                            self.raises += 1
                            # Reset history so we don't immediately raise again
                            # — give the new bid a chance to land in the auction.
                            self.history[cid] = []
            self.stop_event.wait(self.check_interval)
