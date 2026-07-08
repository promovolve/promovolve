#!/usr/bin/env python3
"""Cross-run comparison: takes N run directories, plots cumulative
revenue and floor trajectories side by side. The "is the publisher
earning optimally?" answer becomes: which line ends highest.

Usage:
  python3 compare.py runs/baseline/X runs/floor_fixed_5/Y runs/floor_fixed_8/Z

Each run's summary.json must include the `revenue` block written by
runner.py. Output goes to ./compare-<UTC-timestamp>/.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import matplotlib.pyplot as plt


def load_run(run_dir: Path) -> dict:
    summary = json.loads((run_dir / "summary.json").read_text())
    obs = []
    obs_path = run_dir / "observations.jsonl"
    if obs_path.exists():
        with open(obs_path) as f:
            for line in f:
                if line.strip():
                    obs.append(json.loads(line))
    obs.sort(key=lambda o: o["ts"])
    return {
        "summary":      summary,
        "observations": obs,
        "label":        summary.get("scenario", run_dir.name),
        "strategy":     summary.get("floor_strategy", {}).get("kind", "?"),
    }


def parse_ts(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def plot_floor_overlay(runs: list[dict], out: Path) -> None:
    fig, ax = plt.subplots(figsize=(12, 5))
    for r in runs:
        obs = r["observations"]
        if not obs:
            continue
        ts0 = parse_ts(obs[0]["ts"])
        xs = [(parse_ts(o["ts"]) - ts0).total_seconds() for o in obs]
        ys = [float(o["floorAfter"]) for o in obs]
        ax.step(xs, ys, where="post", lw=1.2,
                label=f"{r['label']} ({r['strategy']})")
    ax.set_xlabel("seconds since run start")
    ax.set_ylabel("floor CPM ($)")
    ax.set_title("Floor trajectories — strategy comparison")
    ax.grid(True, alpha=0.3)
    ax.legend(fontsize=9, loc="best")
    fig.tight_layout()
    fig.savefig(out, dpi=120)
    plt.close(fig)


def plot_revenue_bar(runs: list[dict], out: Path) -> None:
    labels  = [f"{r['label']}\n({r['strategy']})" for r in runs]
    revenue = [r["summary"]["revenue"]["totals"].get("revenue_usd", 0.0) for r in runs]
    imps    = [r["summary"]["revenue"]["totals"].get("impressions", 0)    for r in runs]
    cpms    = [r["summary"]["revenue"]["totals"].get("avg_cpm", 0.0)      for r in runs]

    fig, axes = plt.subplots(1, 3, figsize=(13, 4))
    axes[0].bar(range(len(runs)), revenue, color="tab:green")
    axes[0].set_xticks(range(len(runs)))
    axes[0].set_xticklabels(labels, fontsize=8)
    axes[0].set_ylabel("$ revenue")
    axes[0].set_title("Total publisher revenue")
    for i, v in enumerate(revenue):
        axes[0].text(i, v, f"${v:.2f}", ha="center", va="bottom", fontsize=9)

    axes[1].bar(range(len(runs)), imps, color="tab:blue")
    axes[1].set_xticks(range(len(runs)))
    axes[1].set_xticklabels(labels, fontsize=8)
    axes[1].set_ylabel("impressions cleared")
    axes[1].set_title("Fill (impressions cleared)")
    for i, v in enumerate(imps):
        axes[1].text(i, v, str(v), ha="center", va="bottom", fontsize=9)

    axes[2].bar(range(len(runs)), cpms, color="tab:orange")
    axes[2].set_xticks(range(len(runs)))
    axes[2].set_xticklabels(labels, fontsize=8)
    axes[2].set_ylabel("avg clearing CPM ($)")
    axes[2].set_title("Avg clearing CPM")
    for i, v in enumerate(cpms):
        axes[2].text(i, v, f"${v:.2f}", ha="center", va="bottom", fontsize=9)

    fig.tight_layout()
    fig.savefig(out, dpi=120)
    plt.close(fig)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("runs", type=Path, nargs="+", help="Run directories to compare")
    p.add_argument("--out-root", type=Path, default=Path(__file__).resolve().parent / "compare-out")
    args = p.parse_args()

    runs = [load_run(rd) for rd in args.runs]
    out_dir = args.out_root / datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%S")
    out_dir.mkdir(parents=True, exist_ok=True)

    plot_floor_overlay(runs, out_dir / "floor_overlay.png")
    plot_revenue_bar(runs, out_dir / "revenue_bars.png")

    # Console summary so the answer is visible without opening images.
    print(f"\nComparison written to {out_dir}\n")
    print(f"{'strategy':<10}  {'scenario':<25}  {'revenue':>10}  {'imps':>6}  {'avg CPM':>9}")
    print("-" * 70)
    for r in runs:
        t = r["summary"]["revenue"]["totals"]
        print(f"{r['strategy']:<10}  {r['label']:<25}  "
              f"${t.get('revenue_usd', 0):>9.4f}  "
              f"{t.get('impressions', 0):>6}  "
              f"${t.get('avg_cpm', 0):>8.4f}")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
