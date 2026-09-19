#!/usr/bin/env python3
"""Analyze CBO gate logs: RunScenario output for the cbo-two-campaign* scenarios (GH #38, #95).

Single log
    scripts/cbo_gate_analyze.py LOG [--scenario scripts/scenarios/NAME.json]

    Per-tick table, day-by-day tap-throughs per 1,000 spent, day-end walls,
    then a verdict block: spend against the budget-implied maximum,
    throughput against the scenario's expectation, and whether the campaign
    with the higher true tap-through rate ended the last day with the larger
    wall (the wall-direction diagnostic).

Summary over a gate run
    scripts/cbo_gate_analyze.py --summary DIR

    DIR holds seed*/ subdirectories written by scripts/cbo-gate.sh. Pairs each
    optimized log with its back-to-back control, applies the validity gate,
    and prints the per-seed table with median and range of the gain.

Rules encoded here (why: #38 comment on gate runs 5-7):
  * A pair is VALID only if its CONTROL spent >= 90% of the budget-implied
    maximum and ran at >= 85% of the expected impressions/sec. The fixed
    control has no allocator, so under-spend there is the environment (a
    loaded host starves the throughput-bound ample scenario). The optimized
    arm under-spends by design when the strong campaign is capped by what it
    can win, so its own spend is informational.
  * Symmetric has no control: it is budget-capped like scarce, so its own
    spend judges validity and its metric is drift from an even split.
  * Wall direction: the higher-true-rate campaign (max ctaRates) must end the
    last day with >= 55% of the forward budget. <= 45% is INVERTED.
"""
import argparse
import glob
import json
import os
import re
import statistics
import sys

REPORT = re.compile(r"─── Report @ (\d+) requests \(([\d.]+)s elapsed\)")
ROLL = re.compile(r"DAY ROLLOVER: Completed simulated day (\d+)")
CAMP = re.compile(
    r"^\s+(\S+)\s+(01[0-9A-Z]{24})\s+budget=\$([\d.]+)\s+(\d+) imps\s+(\d+) clicks\s+(\d+) tap-throughs\s+spend=\$([\d.]+)"
)
CAMP_LEGACY = re.compile(
    r"^\s+(\S+)\s+budget=\$([\d.]+)\s+(\d+) imps\s+(\d+) clicks\s+(\d+) tap-throughs\s+spend=\$([\d.]+)"
)
TOTAL = re.compile(r"Tap-through:\s+(\d+) \(([\d.]+)% of clicks, ([\d.]+) per 1,000 spent\)")
IMPS = re.compile(r"Impressions:\s+(\d+)\s+\(avg: ([\d.]+)/sec")
STOP = re.compile(r"STOPPING: Reached (\d+) simulated days")

SCENARIO_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scenarios")

CONTROL_SPEND_MIN = 0.90
CONTROL_THROUGHPUT_MIN = 0.85
DIRECTION_MARGIN = 0.55


def parse(path):
    """Reports as dicts: elapsed, day, camps {id: (budget, imps, clicks, ctas, spend)}, total."""
    reports, day, cur = [], 1, None
    imps, rate, stopped_days = 0, 0.0, None
    for line in open(path, encoding="utf-8", errors="replace"):
        m = ROLL.search(line)
        if m:
            day = int(m.group(1)) + 1
            continue
        m = STOP.search(line)
        if m:
            stopped_days = int(m.group(1))
            continue
        m = REPORT.search(line)
        if m:
            cur = {"elapsed": float(m.group(2)), "day": day, "camps": {}, "total": None}
            reports.append(cur)
            continue
        if cur is None:
            continue
        m = TOTAL.search(line)
        if m:
            cur["total"] = (int(m.group(1)), float(m.group(3)))
            continue
        m = IMPS.search(line)
        if m:
            imps, rate = int(m.group(1)), float(m.group(2))
            continue
        m = CAMP.match(line)
        if m:
            cur["camps"][m.group(2)] = (
                float(m.group(3)), int(m.group(4)), int(m.group(5)), int(m.group(6)), float(m.group(7))
            )
            continue
        m = CAMP_LEGACY.match(line)
        if m:
            cur["camps"][m.group(1)] = (
                float(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5)), float(m.group(6))
            )
    return {"reports": reports, "imps": imps, "rate": rate, "days": stopped_days}


def load_scenario(log_path, explicit):
    """The scenario JSON: explicit path, else scripts/scenarios/<log basename>.json."""
    path = explicit
    if not path:
        cand = os.path.join(SCENARIO_DIR, os.path.splitext(os.path.basename(log_path))[0] + ".json")
        if os.path.exists(cand):
            path = cand
    if not path:
        return None
    with open(path) as f:
        return json.load(f)


def expectations(sc):
    """(account daily budget, days, expected imps/sec, better campaign index or None, optimized?)."""
    if not sc:
        return None
    setup, traffic, pacing = sc.get("setup", {}), sc.get("traffic", {}), sc.get("pacing", {})
    account = float(setup.get("advertiserBudget", 0.0))
    days = int(traffic.get("stopAfterDays", 0))
    cpm = float(setup.get("cpm", 0.0))
    day_len = float(pacing.get("dayDurationSeconds", 0.0))
    per_sec = (account / cpm * 1000.0 / day_len) if cpm > 0 and day_len > 0 else 0.0
    rates = setup.get("ctaRates") or []
    better = None
    if rates and max(rates) > min(rates):
        better = rates.index(max(rates))
    optimized = setup.get("budgetMode") == "optimized"
    return {"account": account, "days": days, "per_sec": per_sec, "better": better, "optimized": optimized,
            "symmetric": bool(rates) and better is None}


def day_rows(reports):
    """Last report of each day -> (day, ctas delta, spend delta, per1k, walls dict, forward shares dict)."""
    last_by_day = {}
    for r in reports:
        last_by_day[r["day"]] = r
    rows, prev = [], (0, 0.0)
    for d in sorted(last_by_day):
        r = last_by_day[d]
        ctas = sum(v[3] for v in r["camps"].values())
        spend = sum(v[4] for v in r["camps"].values())
        dc, ds = ctas - prev[0], spend - prev[1]
        per1k = dc / ds * 1000 if ds > 0 else 0.0
        walls = {c: r["camps"][c][0] for c in r["camps"]}
        fwd = {c: max(0.0, r["camps"][c][0] - r["camps"][c][4]) for c in r["camps"]}
        ftot = sum(fwd.values())
        shares = {c: (fwd[c] / ftot if ftot > 0 else None) for c in fwd}
        rows.append((d, dc, ds, per1k, walls, shares, ctas, spend))
        prev = (ctas, spend)
    return rows


def direction(rows, days, better):
    """('correct'|'INVERTED'|'unseparated'|'n/a', better share) from the last complete day's walls."""
    complete = [r for r in rows if r[0] <= (days or r[0])]
    if better is None or not complete:
        return "n/a", None
    walls = complete[-1][4]
    ids = sorted(walls)  # ULIDs are time-ordered: creation order = scenario campaign index
    if len(ids) < 2 or better >= len(ids):
        return "n/a", None
    total = sum(walls.values())
    share = walls[ids[better]] / total if total > 0 else 0.5
    if share >= DIRECTION_MARGIN:
        return "correct", share
    if share <= 1 - DIRECTION_MARGIN:
        return "INVERTED", share
    return "unseparated", share


def analyze(log_path, scenario_path=None, quiet=False):
    p = parse(log_path)
    reports = p["reports"]
    if not reports:
        if not quiet:
            print(f"{log_path}: no reports found")
        return None
    sc = expectations(load_scenario(log_path, scenario_path))
    names = sorted({c for r in reports for c in r["camps"]})
    rows = day_rows(reports)
    days = p["days"] or (sc["days"] if sc else None)
    complete = [r for r in rows if days is None or r[0] <= days]
    total_ctas = complete[-1][6] if complete else 0
    total_spend = complete[-1][7] if complete else 0.0
    per1k = total_ctas / total_spend * 1000 if total_spend > 0 else 0.0
    dirn, share = direction(rows, days, sc["better"] if sc else None)
    out = {
        "log": log_path, "ctas": total_ctas, "spend": total_spend, "per1k": per1k,
        "imps": p["imps"], "rate": p["rate"], "direction": dirn, "better_share": share,
        "walls": complete[-1][4] if complete else {}, "days": days,
        "optimized": sc["optimized"] if sc else None, "symmetric": sc["symmetric"] if sc else False,
    }
    if sc and sc["account"] > 0 and days:
        out["spend_fraction"] = total_spend / (sc["account"] * days)
    if sc and sc["per_sec"] > 0:
        out["rate_fraction"] = p["rate"] / sc["per_sec"]
        out["expected_rate"] = sc["per_sec"]
    if quiet:
        return out

    print(f"{'elapsed':>8} {'day':>3} " + " ".join(f"{n[-8:]:>30}" for n in names))
    print(f"{'':>8} {'':>3} " + " ".join(f"{'budget  imps  ctas  spend':>30}" for _ in names))
    for r in reports:
        cells = []
        for n in names:
            b, imps, clicks, ctas, spend = r["camps"].get(n, (0, 0, 0, 0, 0.0))
            cells.append(f"{b:8.2f} {imps:5d} {ctas:5d} {spend:8.2f}")
        print(f"{r['elapsed']:8.0f} {r['day']:>3} " + " ".join(f"{c:>30}" for c in cells))
    print()
    print(f"{'day':>3} {'ctas':>6} {'spend':>8} {'ctas/1000':>10}   walls at day end (creation order)")
    for d, dc, ds, rate, walls, shares, _, _ in rows:
        w = "  ".join(f"{walls[c]:.2f}" for c in sorted(walls))
        print(f"{d:>3} {dc:6d} {ds:8.2f} {rate:10.2f}   {w}")
    print()
    print(f"final: {total_ctas} tap-throughs, {total_spend:.2f} spent, {per1k:.2f} per 1,000 (days 1-{days})")
    verdict(out)
    return out


def verdict(o):
    """The validity and direction lines for one log."""
    sf = o.get("spend_fraction")
    rf = o.get("rate_fraction")
    spend_s = f"spend {sf:.0%} of budget-implied" if sf is not None else "spend fraction unknown (no scenario)"
    rate_s = (f"throughput {o['rate']:.1f}/s = {rf:.0%} of expected {o['expected_rate']:.1f}/s"
              if rf is not None else f"throughput {o['rate']:.1f}/s")
    print(f"validity:  {spend_s}; {rate_s}")
    if o["optimized"] and not o["symmetric"]:
        print("           optimized arm: judged by its paired control (it under-spends by design when capped)")
    elif sf is not None:
        ok = sf >= CONTROL_SPEND_MIN and (rf is None or rf >= CONTROL_THROUGHPUT_MIN)
        print(f"           {'VALID' if ok else 'INVALID'} "
              f"(needs spend >= {CONTROL_SPEND_MIN:.0%}, throughput >= {CONTROL_THROUGHPUT_MIN:.0%})")
    if o["optimized"] is False and not o["symmetric"]:
        print("direction: n/a (fixed walls)")
    elif o["symmetric"] and o["walls"]:
        ids = sorted(o["walls"])
        tot = sum(o["walls"].values())
        sh = o["walls"][ids[0]] / tot if tot > 0 else 0.5
        print(f"direction: symmetric, camp1 share {sh:.3f} (drift {abs(sh - 0.5):.3f}; bar 0.15 at day end)")
    elif o["direction"] != "n/a":
        print(f"direction: {o['direction']} (better campaign holds {o['better_share']:.2f} of the walls; "
              f">= {DIRECTION_MARGIN:.2f} correct, <= {1 - DIRECTION_MARGIN:.2f} inverted)")


PAIRS = [
    ("scarce", "cbo-two-campaign-scarce", "cbo-two-campaign-scarce-fixed"),
    ("ample", "cbo-two-campaign", "cbo-two-campaign-fixed"),
]
SYMMETRIC = "cbo-two-campaign-symmetric"


def control_valid(c):
    sf, rf = c.get("spend_fraction"), c.get("rate_fraction")
    if sf is None:
        return None
    return sf >= CONTROL_SPEND_MIN and (rf is None or rf >= CONTROL_THROUGHPUT_MIN)


def summary(root):
    dirs = sorted(glob.glob(os.path.join(root, "seed*")))
    if os.path.exists(os.path.join(root, "cbo-two-campaign-scarce.log")) or \
            os.path.exists(os.path.join(root, "cbo-two-campaign.log")):
        dirs.insert(0, root)
    if not dirs:
        print(f"no seed*/ directories under {root}")
        return 1
    print(f"{'seed':<8} {'pair':<7} {'opt/1k':>8} {'ctl/1k':>8} {'gain':>7} {'opt CTAs':>8} {'ctl CTAs':>8} "
          f"{'direction':<12} {'ctl spend':>9} {'ctl imps/s':>10} valid")
    gains = {"scarce": [], "ample": []}
    dirs_ok = {"scarce": [0, 0, 0], "ample": [0, 0, 0]}  # correct, inverted, unseparated (valid pairs only)
    sym = []
    for d in dirs:
        seed = os.path.basename(d.rstrip("/")) if d != root else "-"
        for pair, opt_name, ctl_name in PAIRS:
            ol, cl = os.path.join(d, opt_name + ".log"), os.path.join(d, ctl_name + ".log")
            if not (os.path.exists(ol) and os.path.exists(cl)):
                continue
            o, c = analyze(ol, quiet=True), analyze(cl, quiet=True)
            if not o or not c:
                continue
            valid = control_valid(c)
            gain = (o["per1k"] / c["per1k"] - 1) if c["per1k"] > 0 else float("nan")
            sf = c.get("spend_fraction")
            sf_s = f"{sf:.0%}" if sf is not None else "?"
            valid_s = "yes" if valid else ("?" if valid is None else "NO")
            print(f"{seed:<8} {pair:<7} {o['per1k']:8.0f} {c['per1k']:8.0f} {gain:+7.1%} {o['ctas']:8d} {c['ctas']:8d} "
                  f"{o['direction']:<12} {sf_s:>9} {c['rate']:10.1f} {valid_s}")
            if valid:
                gains[pair].append(gain)
                idx = {"correct": 0, "INVERTED": 1}.get(o["direction"], 2)
                dirs_ok[pair][idx] += 1
        sl = os.path.join(d, SYMMETRIC + ".log")
        if os.path.exists(sl):
            s = analyze(sl, quiet=True)
            if s and s["walls"]:
                ids = sorted(s["walls"])
                tot = sum(s["walls"].values())
                sh = s["walls"][ids[0]] / tot if tot > 0 else 0.5
                ok = control_valid(s)
                sym.append((seed, sh, ok))
                drift = f"drift {abs(sh - 0.5):.3f}"
                sf = s.get("spend_fraction")
                sf_s = f"{sf:.0%}" if sf is not None else "?"
                valid_s = "yes" if ok else ("?" if ok is None else "NO")
                print(f"{seed:<8} {'sym':<7} {'':>8} {'':>8} {'':>7} {s['ctas']:8d} {'':>8} "
                      f"{drift:<12} {sf_s:>9} {s['rate']:10.1f} {valid_s}")
    print()
    for pair in ("scarce", "ample"):
        g = [x for x in gains[pair] if x == x]
        if g:
            c, i, u = dirs_ok[pair]
            print(f"{pair}: {len(g)} valid pair(s); gain median {statistics.median(g):+.1%}, "
                  f"range {min(g):+.1%} .. {max(g):+.1%}; direction correct {c}, inverted {i}, unseparated {u}")
        else:
            print(f"{pair}: no valid pairs")
    if sym:
        drifts = [abs(sh - 0.5) for _, sh, ok in sym if ok]
        if drifts:
            print(f"symmetric: {len(drifts)} valid run(s); day-end drift median {statistics.median(drifts):.3f}, "
                  f"max {max(drifts):.3f} (bar 0.15)")
    print()
    print("read: a single pair is inside the noise of a +-30% criterion (~65 tap-throughs per arm, controls swing "
          "+-12%). Judge the median over >= 4 valid pairs and the direction count, not one row.")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log", nargs="?", help="one RunScenario log")
    ap.add_argument("--scenario", help="scenario JSON (default: scripts/scenarios/<log basename>.json)")
    ap.add_argument("--summary", metavar="DIR", help="summarize a gate run directory (seed*/ subdirs)")
    a = ap.parse_args()
    if a.summary:
        sys.exit(summary(a.summary))
    if not a.log:
        ap.error("LOG or --summary DIR required")
    sys.exit(0 if analyze(a.log, a.scenario) else 1)


if __name__ == "__main__":
    main()
