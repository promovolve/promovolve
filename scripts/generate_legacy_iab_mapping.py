#!/usr/bin/env python3
"""Generate IAB Content Taxonomy 2.x → 3.0 ID lookup from the official TSVs.

Reads:
  modules/core/src/main/resources/iab_content_taxonomy/2_1.tsv
  modules/core/src/main/resources/iab_content_taxonomy/3_0.tsv

Writes:
  modules/core/src/main/resources/iab_content_taxonomy/legacy_2x_to_3_0.tsv

Resolution order per legacy id:
  1. id-preserved      — same numeric id appears in 3.0 with same label
  2. path              — same (label, tier1..tier3) appears in 3.0
  3. name / name+tier1 — single name match (optionally disambiguated by tier1)
  4. name+remap        — name match disambiguated by a manual tier1 remap
  5. descriptive-vector-dropped — legacy id is a Channel/Type/Media-Format/
                         Language/Source descriptor that 3.0 removed from
                         topical content. Maps to empty (drop on migrate).
  6. ancestor          — walk up the 2.x parent chain until something maps
  7. tier1-remap       — fallback: collapse to a manually-remapped tier1
"""
import re

TAXO_DIR = "modules/core/src/main/resources/iab_content_taxonomy"

DESCRIPTIVE_VECTOR_TIER1S = {
    "Content Channel", "Content Type", "Content Media Format",
    "Content Language", "Content Source", "Content Source Geo",
}

# 2.x tier-1 names whose entries collapsed under a different 3.0 tier-1
# (most often the new alphanumeric-id parents introduced in 3.0).
MANUAL_TIER1_REMAP = {
    "Music and Audio":        "JLBCU7",  # Entertainment
    "Events and Attractions": "8VZQHL",  # Events
    "News and Politics":      "386",     # Politics
    "Movies":                 "JLBCU7",  # Entertainment
    "Television":             "JLBCU7",  # Entertainment
}


def load_taxonomy(path):
    rows = []
    with open(path) as f:
        lines = f.readlines()
    for line in lines[2:]:  # skip 2-line header
        cols = line.rstrip("\n").split("\t")
        if len(cols) >= 3 and cols[0].strip():
            rows.append({
                "id":     cols[0].strip(),
                "parent": cols[1].strip() or None if len(cols) > 1 else None,
                "name":   cols[2].strip(),
                "tier1":  cols[3].strip() if len(cols) > 3 else "",
                "tier2":  cols[4].strip() if len(cols) > 4 else "",
                "tier3":  cols[5].strip() if len(cols) > 5 else "",
                "tier4":  cols[6].strip() if len(cols) > 6 else "",
            })
    return rows


def norm(s):
    return re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()


def main():
    v21 = load_taxonomy(f"{TAXO_DIR}/2_1.tsv")
    v30 = load_taxonomy(f"{TAXO_DIR}/3_0.tsv")
    v21_by_id = {r["id"]: r for r in v21}
    v30_by_id = {r["id"]: r for r in v30}

    v30_by_name = {}
    v30_by_name_path = {}
    for r in v30:
        v30_by_name.setdefault(norm(r["name"]), []).append(r)
        key = (norm(r["name"]), norm(r["tier1"]), norm(r["tier2"]), norm(r["tier3"]))
        v30_by_name_path[key] = r["id"]

    resolved = {}

    def try_direct(r21):
        rid = r21["id"]
        # 2.x and 3.0 share their numeric id space; if the same id exists in
        # 3.0 (post descriptive-vector filter) it's a stable carryover — even
        # when the label tightened (e.g. "Music and Audio" → "Music" under
        # the new Entertainment tier). Verified ~23 such renames are all the
        # same concept.
        if rid in v30_by_id:
            return (rid, "id-preserved")
        key = (norm(r21["name"]), norm(r21["tier1"]),
               norm(r21["tier2"]), norm(r21["tier3"]))
        if key in v30_by_name_path:
            return (v30_by_name_path[key], "path")
        cands = v30_by_name.get(norm(r21["name"]), [])
        if len(cands) == 1:
            return (cands[0]["id"], "name")
        if len(cands) > 1:
            target = MANUAL_TIER1_REMAP.get(r21["tier1"])
            if target:
                best = [c for c in cands if c["parent"] == target or c["id"] == target]
                if len(best) == 1:
                    return (best[0]["id"], "name+remap")
            same_t1 = [c for c in cands if norm(c["tier1"]) == norm(r21["tier1"])]
            if len(same_t1) == 1:
                return (same_t1[0]["id"], "name+tier1")
        return None

    # Pass 1: descriptive-vector drop + direct matches
    for r in v21:
        if r["tier1"] in DESCRIPTIVE_VECTOR_TIER1S:
            resolved[r["id"]] = ("", "descriptive-vector-dropped")
            continue
        m = try_direct(r)
        if m:
            resolved[r["id"]] = m

    # Pass 2: walk up 2.x parent chain for anything still unresolved
    def walk(r, depth=0):
        if r["id"] in resolved:
            return resolved[r["id"]]
        if depth > 6 or r["parent"] is None:
            return None
        p = v21_by_id.get(r["parent"])
        if p is None:
            return None
        res = walk(p, depth + 1)
        return (res[0], "ancestor") if res else None

    for r in v21:
        if r["id"] not in resolved:
            res = walk(r)
            if res:
                resolved[r["id"]] = res

    # Pass 3: tier1 remap as final fallback
    for r in v21:
        if r["id"] not in resolved and r["tier1"] in MANUAL_TIER1_REMAP:
            resolved[r["id"]] = (MANUAL_TIER1_REMAP[r["tier1"]], "tier1-remap")

    unmatched = [r for r in v21 if r["id"] not in resolved]

    # Stats
    counts = {}
    for v in resolved.values():
        counts[v[1]] = counts.get(v[1], 0) + 1
    print("Resolution counts:")
    for k, v in sorted(counts.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v}")
    print(f"unmatched: {len(unmatched)}")
    for u in unmatched[:20]:
        print(f"  {u['id']}\t{u['name']}\ttier1={u['tier1']}")

    # Write
    out_path = f"{TAXO_DIR}/legacy_2x_to_3_0.tsv"

    def sortkey(r):
        try:
            return (0, int(r["id"]))
        except ValueError:
            return (1, r["id"])

    with open(out_path, "w") as f:
        f.write("# Generated by scripts/generate_legacy_iab_mapping.py.\n")
        f.write("# Re-run if 2_1.tsv or 3_0.tsv change.\n")
        f.write("# Empty v30_id with match_type=descriptive-vector-dropped means the\n")
        f.write("# legacy id was a Channel/Type/Media-Format/Language/Source/Source-Geo\n")
        f.write("# descriptor that 3.0 removed from topical content. Drop on migrate.\n")
        f.write("legacy_id\tv30_id\tlabel\tmatch_type\n")
        for r in sorted(v21, key=sortkey):
            if r["id"] in resolved:
                v30_id, mtype = resolved[r["id"]]
                f.write(f"{r['id']}\t{v30_id}\t{r['name']}\t{mtype}\n")

    print(f"wrote {out_path}; coverage: {len(resolved)}/{len(v21)} = "
          f"{100 * len(resolved) / len(v21):.1f}%")


if __name__ == "__main__":
    main()
