"""Floor pricing strategies for comparison tests.

`rl`     — the default. Reset the floor agent, let it learn, take whatever
           floor it picks each window. This is what every prior test ran.
`fixed`  — pin the floor at a given value for the whole run by PATCHing it
           back every few seconds. The RL agent's actions get silently
           overwritten, so a fixed-floor scenario produces a clean
           counterfactual revenue number without disabling the agent.

A future "pick_second_highest_historical" strategy would maintain a
rolling window of observed clearing CPMs and PATCH the floor to that
value. Not implemented yet — separate research question.

Together these power the "is the publisher earning optimally?" test
suite: run the same traffic + bidder market under each strategy back to
back, query tracking_events for revenue per run, compare."""
from __future__ import annotations

import threading
from typing import Any

import requests


def patch_site_floor(api: str, pub: str, site: str, floor_cpm: float) -> bool:
    try:
        r = requests.patch(
            f"{api}/v1/publishers/{pub}/sites/{site}",
            json={"floorCpm": f"{floor_cpm:.4f}"},
            timeout=5,
        )
        r.raise_for_status()
        return True
    except Exception:
        return False


class FixedFloorEnforcer(threading.Thread):
    """PATCHes site floorCpm back to `target` every `interval_seconds`,
    silently undoing anything the RL agent did. Use with `minFloorCpm`
    set to the same value so the agent can't drift below either.

    Doesn't disable the agent — the agent still observes, still trains
    its weights — its decisions just never reach the auction. This
    keeps the test plumbing simple."""

    def __init__(self, api: str, pub: str, site: str, target: float, interval_seconds: float = 3.0):
        super().__init__(daemon=True)
        self.api = api
        self.pub = pub
        self.site = site
        self.target = target
        self.interval = interval_seconds
        self.stop_event = threading.Event()
        self.patches = 0

    def stop(self) -> None:
        self.stop_event.set()

    def run(self) -> None:
        while not self.stop_event.is_set():
            if patch_site_floor(self.api, self.pub, self.site, self.target):
                self.patches += 1
            self.stop_event.wait(self.interval)
