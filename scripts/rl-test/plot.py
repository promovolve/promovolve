#!/usr/bin/env python3
"""Render plots for one RL test run.

Reads observations.jsonl + scenario.yaml from a run directory, writes
PNGs into <run>/plots/. Designed to be re-runnable: existing PNGs are
overwritten.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

import matplotlib.pyplot as plt
import yaml


def parse_ts(s: str) -> datetime:
    # API returns e.g. "2026-05-18T12:16:56.911782Z"
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def load_observations(jsonl_path: Path) -> list[dict]:
    out = []
    with open(jsonl_path) as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    # Newest-first or chronological? Runner writes chronological — but
    # sort defensively so the plot is correct either way.
    out.sort(key=lambda o: o["ts"])
    return out


def plot_floor(obs: list[dict], out: Path, events: list[dict] | None = None,
               bidder_events: list[dict] | None = None) -> None:
    ts0 = parse_ts(obs[0]["ts"])
    xs = [(parse_ts(o["ts"]) - ts0).total_seconds() for o in obs]
    floor_after = [float(o["floorAfter"]) for o in obs]

    fig, ax = plt.subplots(figsize=(11, 4))
    ax.step(xs, floor_after, where="post", lw=1.2, label="floor")
    ax.set_xlabel("seconds since first observation")
    ax.set_ylabel("floor CPM ($)")
    ax.set_title("Floor decisions over time")
    ax.grid(True, alpha=0.3)

    for ev in events or []:
        ax.axvline(ev["atSeconds"], color="red", alpha=0.4, lw=1, ls="--")
        ax.text(ev["atSeconds"], ax.get_ylim()[1], ev["action"],
                rotation=90, va="top", ha="right", fontsize=8, color="red")

    # Bidder-raise events as scatter points at the new bid level. Different
    # campaigns get different colours so we can see who's climbing.
    if bidder_events:
        by_camp: dict[str, list[tuple[float, float]]] = {}
        for be in bidder_events:
            t = (parse_ts(be["ts"]) - ts0).total_seconds()
            by_camp.setdefault(be["campaignId"], []).append((t, float(be["to"])))
        for cid, pts in by_camp.items():
            xs_, ys_ = zip(*pts)
            ax.scatter(xs_, ys_, s=24, marker="^", alpha=0.7,
                       label=f"bid↑ {cid[-6:]}")
        ax.legend(loc="upper left", fontsize=8, ncol=2)

    fig.tight_layout()
    fig.savefig(out, dpi=120)
    plt.close(fig)


def plot_eps_loss(obs: list[dict], out: Path) -> None:
    ts0 = parse_ts(obs[0]["ts"])
    xs = [(parse_ts(o["ts"]) - ts0).total_seconds() for o in obs]
    eps = [o["epsilon"] for o in obs]
    # `trainingLoss` is omitted for windows that fired before the agent
    # had any data to train on (right after a reset, or during warmup).
    # Match the x-axis to the windows that *did* train so the plot
    # doesn't gap-fill with zeros.
    loss_pairs = [(x, o["trainingLoss"]) for x, o in zip(xs, obs) if "trainingLoss" in o]
    loss_xs   = [p[0] for p in loss_pairs]
    loss_vals = [p[1] for p in loss_pairs]

    fig, ax1 = plt.subplots(figsize=(11, 4))
    ax1.plot(xs, eps, color="tab:blue", lw=1.2, label="epsilon")
    ax1.set_xlabel("seconds since first observation")
    ax1.set_ylabel("epsilon", color="tab:blue")
    ax1.tick_params(axis="y", labelcolor="tab:blue")
    ax1.grid(True, alpha=0.3)

    ax2 = ax1.twinx()
    ax2.plot(loss_xs, loss_vals, color="tab:orange", lw=0.8, alpha=0.7, label="trainingLoss")
    ax2.set_ylabel("trainingLoss", color="tab:orange")
    ax2.tick_params(axis="y", labelcolor="tab:orange")

    ax1.set_title("Exploration epsilon (left) vs training loss (right)")
    fig.tight_layout()
    fig.savefig(out, dpi=120)
    plt.close(fig)


def plot_floor_histogram(obs: list[dict], out: Path) -> None:
    floors = [float(o["floorAfter"]) for o in obs]
    fig, ax = plt.subplots(figsize=(7, 4))
    ax.hist(floors, bins=40, edgecolor="black", lw=0.5)
    ax.set_xlabel("floor CPM ($)")
    ax.set_ylabel("observation count")
    ax.set_title(f"Floor distribution (n={len(floors)})")
    ax.grid(True, alpha=0.3, axis="y")
    fig.tight_layout()
    fig.savefig(out, dpi=120)
    plt.close(fig)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("run_dir", type=Path, help="A runs/<name>/<timestamp>/ directory")
    args = p.parse_args()

    obs_path = args.run_dir / "observations.jsonl"
    if not obs_path.exists():
        print(f"no observations.jsonl in {args.run_dir}", file=sys.stderr)
        return 1

    obs = load_observations(obs_path)
    if not obs:
        print("observations.jsonl is empty", file=sys.stderr)
        return 1

    scenario_path = args.run_dir / "scenario.yaml"
    events = []
    if scenario_path.exists():
        scenario = yaml.safe_load(scenario_path.read_text())
        events = scenario.get("events") or []

    # Bidder raise events, if the responder was running. Optional.
    bidder_events: list[dict] = []
    be_path = args.run_dir / "bidder_events.jsonl"
    if be_path.exists():
        with open(be_path) as f:
            bidder_events = [json.loads(l) for l in f if l.strip()]

    plots_dir = args.run_dir / "plots"
    plots_dir.mkdir(exist_ok=True)
    plot_floor(obs,          plots_dir / "floor.png",     events, bidder_events)
    plot_eps_loss(obs,       plots_dir / "eps_loss.png")
    plot_floor_histogram(obs, plots_dir / "floor_hist.png")
    print(f"wrote 3 plots to {plots_dir} (bidder events: {len(bidder_events)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
