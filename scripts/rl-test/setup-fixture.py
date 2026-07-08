#!/usr/bin/env python3
"""Deterministic fixture setup for the rl-test harness.

Creates (or recovers) a publisher + site + advertiser + 5 fixed-CPM
campaigns + creatives against a running cluster, and writes the
resulting IDs to a YAML the rl-test scenarios reference.

Idempotent:
  - publisher + advertiser IDs are derived from fixed emails through
    the /v1/publisher-login + /v1/login endpoints (same email →
    same ULID forever).
  - site is created once per (publisherId, siteId) pair; subsequent
    runs detect the existing site via GET and skip the POST.
  - campaigns are matched by name (`Fixture maxCpm=$N`); creatives
    are matched by name (`Fixture maxCpm=$N creative`). Missing rows
    get created, existing rows are reused.
  - classify + bulk-approve are idempotent on the server side
    (classify overwrites, bulk-approve no-ops when nothing pending).

Run from anywhere; output lands at scripts/rl-test/fixtures/dev.yaml
unless overridden.
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path
from typing import Any

import requests
import yaml

# ---------------------------------------------------------------- defaults

DEFAULT_PUBLISHER_EMAIL  = "publisher@publisher.com"
DEFAULT_ADVERTISER_EMAIL = "advertiser@advertiser.com"
# Site ID matches the value baked into `publisher-site-ja-localhost`'s
# script tag (`data-pub="localhost-8888"`) AND the simulate-traffic
# binary's `-pub` argument convention. Keeping these aligned means the
# fixture, the publisher HTML, the traffic generator, and the dashboard
# URLs (e.g. /publisher/sites/localhost-8888/observations) all match
# out of the box — no per-file edits needed.
DEFAULT_SITE_ID          = "localhost-8888"
DEFAULT_SITE_DOMAIN      = "localhost:8888"
DEFAULT_SEED_URL         = "http://localhost:8888/"
DEFAULT_SLOT_ID          = "slot-header"
DEFAULT_LANDING_URL      = "https://example.com/landing"
DEFAULT_AD_PRODUCT       = "1529"        # IAB Travel
DEFAULT_BUDGET_PER_CAMP  = "100"
DEFAULT_CPMS             = [2.0, 4.0, 6.0, 8.0, 10.0]
DEFAULT_CATEGORIES       = {"sports": 0.9, "tech": 0.8}


# ---------------------------------------------------------------- helpers

def log(msg: str) -> None:
    print(f"[setup-fixture] {msg}", flush=True)


def post(api: str, path: str, body: dict[str, Any] | None = None) -> requests.Response:
    r = requests.post(f"{api}{path}", json=body, timeout=15)
    return r


def get(api: str, path: str) -> requests.Response:
    return requests.get(f"{api}{path}", timeout=15)


def put(api: str, path: str, body: dict[str, Any]) -> requests.Response:
    return requests.put(f"{api}{path}", json=body, timeout=15)


def jraise(r: requests.Response, what: str) -> Any:
    if not r.ok:
        raise RuntimeError(f"{what} failed: HTTP {r.status_code} {r.text}")
    return r.json()


# ---------------------------------------------------------------- steps

def login_publisher(api: str, email: str) -> str:
    """POST /v1/publisher-login. Same email → same publisherId across runs."""
    body = jraise(post(api, "/v1/publisher-login", {"email": email}),
                  "publisher-login")
    pid = body["publisherId"]
    log(f"publisher: {pid} ({'new' if body.get('isNew') else 'existing'}, email={email})")
    return pid


def login_advertiser(api: str, email: str) -> str:
    """POST /v1/login. Same email → same advertiserId across runs."""
    body = jraise(post(api, "/v1/login", {"email": email}),
                  "login")
    aid = body["advertiserId"]
    log(f"advertiser: {aid} ({'new' if body.get('isNew') else 'existing'}, email={email})")
    return aid


def ensure_site(api: str, pid: str, sid: str, domain: str, seed_url: str, slot_id: str) -> None:
    """GET /v1/publishers/{pid}/sites/{sid} → create if 404. Idempotent.

    `maxDepth=2` reaches the seed plus every page linked from it. On the
    localhost example that's all 21 ad-bearing pages (`index.html` directly
    links to every section index + every article). depth=1 would reach
    only the seed (3 slots out of 56) — silent undercount."""
    r = get(api, f"/v1/publishers/{pid}/sites/{sid}")
    if r.status_code == 200:
        log(f"site: {sid} exists, skipping create")
        return
    # The API returns 400 with `{"code":"not_found",...}` for missing sites
    # rather than 404. Accept either as "needs creating"; treat anything
    # else as a real error.
    not_found = r.status_code == 404 or (
        r.status_code == 400 and '"not_found"' in r.text)
    if not not_found:
        raise RuntimeError(f"unexpected site GET: HTTP {r.status_code} {r.text}")

    body = {
        "id": sid,
        "domain": domain,
        "crawlConfig": {
            "seedUrl": seed_url,
            "cronSchedule": "0 0 * * *",
            "maxDepth": 2,
            "concurrency": 2,
            "hostRegex": ".*",
            "targetElements": [],
        },
        "slots": [{"slotId": slot_id, "width": 300, "height": 250}],
        "taxonomyIds": [],
        "minFloorCpm": "0.50",
    }
    jraise(post(api, f"/v1/publishers/{pid}/sites", body), "create-site")
    log(f"site: {sid} created")


def trigger_crawl(api: str, pid: str, sid: str) -> None:
    """POST /v1/publishers/{pid}/sites/{sid}/crawl — kicks off an on-demand
    crawl. Returns immediately; use wait_for_crawl() to block on completion."""
    res = jraise(post(api, f"/v1/publishers/{pid}/sites/{sid}/crawl"),
                 "trigger-crawl")
    log(f"crawl triggered: status={res.get('status')}")


def wait_for_crawl(api: str, pid: str, sid: str, timeout_s: int = 300,
                   poll_interval_s: float = 2.0) -> dict[str, Any]:
    """Poll /crawler-status until `running` flips back to False, or timeout.
    Returns the final status dict so the caller can log visitedCount /
    errorCount."""
    deadline = time.monotonic() + timeout_s
    last: dict[str, Any] = {}
    seen_running = False
    while time.monotonic() < deadline:
        r = get(api, f"/v1/publishers/{pid}/sites/{sid}/crawler-status")
        if r.ok:
            last = r.json()
            running = bool(last.get("running"))
            visited = int(last.get("visitedCount", 0))
            in_flight = int(last.get("inFlightCount", 0))
            errs = int(last.get("errorCount", 0))
            if running:
                seen_running = True
                log(f"  crawler running: visited={visited} inFlight={in_flight} errors={errs}")
            elif seen_running:
                # Started, then stopped — crawl complete.
                log(f"crawler idle (done): visited={visited} errors={errs}")
                return last
            # If we never saw `running=True`, the crawler may not have
            # picked the job up yet — keep polling until we do.
        time.sleep(poll_interval_s)
    raise RuntimeError(
        f"wait_for_crawl: timed out after {timeout_s}s. Last status: {last}")


def get_site_slot_count(api: str, pid: str, sid: str) -> int:
    """GET /v1/publishers/{pid}/sites/{sid} → return len(slots).
    Used as a post-crawl guardrail: we know how many slots the localhost
    example has (56) and can flag if the crawler discovered far fewer."""
    body = jraise(get(api, f"/v1/publishers/{pid}/sites/{sid}"), "get-site")
    return len(body.get("slots", []))


def force_verify(api: str, pid: str, sid: str, domain: str) -> None:
    """POST /v1/publishers/{pid}/sites/{sid}/force-verify — bypasses HTTP host check."""
    r = post(api, f"/v1/publishers/{pid}/sites/{sid}/force-verify?host={domain}")
    if not r.ok:
        raise RuntimeError(f"force-verify failed: HTTP {r.status_code} {r.text}")
    log(f"site: force-verified for host={domain}")


def list_campaigns(api: str, aid: str) -> list[dict[str, Any]]:
    """GET /v1/advertisers/{aid}/campaigns → list. The endpoint paginates;
    the fixture only ever creates 5 campaigns so default page is enough."""
    body = jraise(get(api, f"/v1/advertisers/{aid}/campaigns?limit=100&offset=0"),
                  "list-campaigns")
    return body.get("data", [])


def list_creatives(api: str, aid: str, cid: str) -> list[dict[str, Any]]:
    body = jraise(get(api, f"/v1/advertisers/{aid}/campaigns/{cid}/creatives?limit=100&offset=0"),
                  "list-creatives")
    return body.get("data", [])


def ensure_advertiser_budget(api: str, aid: str, daily: str) -> None:
    """PUT /v1/advertisers/{aid}/budget. Idempotent — re-setting to the same
    value is a no-op on the entity."""
    r = put(api, f"/v1/advertisers/{aid}/budget", {"dailyBudget": daily})
    if not r.ok:
        raise RuntimeError(f"set-advertiser-budget failed: HTTP {r.status_code} {r.text}")
    log(f"advertiser budget: ${daily}")


def ensure_campaign(api: str, aid: str, existing: list[dict[str, Any]],
                    max_cpm: float, ad_product: str, budget: str,
                    landing: str, categories: list[str]) -> str:
    """Find-or-create a campaign named `Fixture maxCpm=$N`. Returns campaignId."""
    name = f"Fixture maxCpm=${max_cpm:g}"
    for c in existing:
        if c.get("name") == name:
            log(f"campaign: '{name}' exists ({c['id']})")
            return c["id"]

    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    body = {
        "name": name,
        "budget":   {"daily": budget},
        "schedule": {"startAt": now},
        "adProductCategory": ad_product,
        "bidding":  {"strategy": "fixed", "maxCpm": f"{max_cpm}"},
        "landingUrl": landing,
        "targetCategories": categories,
    }
    res = jraise(post(api, f"/v1/advertisers/{aid}/campaigns", body),
                 f"create-campaign({name})")
    cid = res["id"]
    log(f"campaign: '{name}' created ({cid})")
    return cid


def ensure_creative(api: str, aid: str, cid: str, max_cpm: float, landing: str) -> str:
    """Find-or-create a creative named `Fixture maxCpm=$N creative`.

    Retries on the FK race in `createCreativeLogic` (EndpointRoutes.scala:2333-
    2357) — image_asset and creative rows are inserted in parallel and the
    creative row can hit the FK before image_asset lands. Small back-off
    after the first failure lets the image_asset insert finish."""
    name = f"Fixture maxCpm=${max_cpm:g} creative"
    for cr in list_creatives(api, aid, cid):
        if cr.get("name") == name:
            log(f"  creative: '{name}' exists ({cr['id']})")
            return cr["id"]

    body = {
        "name": name,
        "landingUrl": landing,
        "skipVerify": True,
        "pages": [{
            "tag": "FEATURE",
            "headline": name,
            "sub": "",
            "body": "",
            "accent": "#1a5276",
            "bg": "#ffffff",
            "imgEmoji": "",
            "caption": "",
            "banners": {},
        }],
    }
    for attempt in range(4):
        r = post(api, f"/v1/advertisers/{aid}/campaigns/{cid}/creatives", body)
        if r.ok:
            res = r.json()
            crid = res["id"]
            log(f"  creative: '{name}' created ({crid})"
                + (f" [retry {attempt}]" if attempt else ""))
            return crid
        if "creative_image_hash_fkey" in r.text and attempt < 3:
            wait = 0.2 * (attempt + 1)
            log(f"  creative: '{name}' FK race; retrying in {wait:.1f}s")
            time.sleep(wait)
            continue
        raise RuntimeError(f"create-creative({name}) failed: HTTP {r.status_code} {r.text}")
    raise RuntimeError(f"create-creative({name}) failed after retries")


def activate_campaign(api: str, aid: str, cid: str) -> None:
    """PUT /v1/advertisers/{aid}/campaigns/{cid}/status — idempotent."""
    r = put(api, f"/v1/advertisers/{aid}/campaigns/{cid}/status", {"status": "active"})
    if not r.ok:
        raise RuntimeError(f"activate-campaign failed: HTTP {r.status_code} {r.text}")


def classify(api: str, pid: str, sid: str, url: str,
             categories: dict[str, float], slot_id: str) -> None:
    """POST /v1/publishers/{pid}/sites/{sid}/classify — idempotent (overwrites)."""
    body = {
        "url": url,
        "categories": categories,
        "slots": [{"slotId": slot_id, "width": 300, "height": 250}],
    }
    jraise(post(api, f"/v1/publishers/{pid}/sites/{sid}/classify", body), "classify")
    log(f"classified {url} → categories={list(categories)}")


def approve_all(api: str, pid: str, sid: str, url: str, slot_id: str) -> tuple[int, int]:
    """POST /v1/publishers/{pid}/sites/{sid}/approval/bulk — idempotent
    (no-op when nothing pending)."""
    body = {"url": url, "slotId": slot_id}
    res = jraise(post(api, f"/v1/publishers/{pid}/sites/{sid}/approval/bulk", body),
                 "approval-bulk")
    approved, failed = int(res.get("approved", 0)), int(res.get("failed", 0))
    log(f"bulk-approve: approved={approved} failed={failed}")
    return approved, failed


def reset_pacing(api: str, pid: str, sid: str, advertiser_id: str,
                 campaign_ids: list[str]) -> None:
    """POST /v1/publishers/{pid}/sites/{sid}/pacing/reset.

    On a fresh cluster the PI controller in AdServer starts at ~99% throttle
    until it has spend history to lean on. Resetting day-starts to NOW gives
    the controller a clean baseline so serve/batch can actually pick a
    winner from t=0 instead of throttling everything.

    RunScenario does this implicitly at the end of setup; we have to do
    it explicitly."""
    body = {
        "campaigns": [
            {"advertiserId": advertiser_id, "campaignId": cid}
            for cid in campaign_ids
        ]
    }
    res = jraise(
        post(api, f"/v1/publishers/{pid}/sites/{sid}/pacing/reset", body),
        "pacing-reset")
    log(f"pacing reset: {res.get('resetCount', 0)} campaigns")


# ---------------------------------------------------------------- main

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--api",      default="http://localhost:8080",
                   help="Core API base URL")
    p.add_argument("--site-url", default="http://localhost:8888",
                   help="Publisher site base URL (for the simulate-traffic harness)")
    p.add_argument("--out",      type=Path,
                   default=Path(__file__).resolve().parent / "fixtures" / "dev.yaml",
                   help="Where to write the fixture YAML")
    p.add_argument("--publisher-email",  default=DEFAULT_PUBLISHER_EMAIL)
    p.add_argument("--advertiser-email", default=DEFAULT_ADVERTISER_EMAIL)
    p.add_argument("--site-id",          default=DEFAULT_SITE_ID)
    p.add_argument("--site-domain",      default=DEFAULT_SITE_DOMAIN)
    p.add_argument("--seed-url",         default=DEFAULT_SEED_URL)
    p.add_argument("--slot-id",          default=DEFAULT_SLOT_ID)
    p.add_argument("--ad-product",       default=DEFAULT_AD_PRODUCT)
    p.add_argument("--budget",           default=DEFAULT_BUDGET_PER_CAMP,
                   help="Daily budget per campaign (string, dollars)")
    p.add_argument("--landing-url",      default=DEFAULT_LANDING_URL)
    p.add_argument("--cpms", nargs="+", type=float, default=DEFAULT_CPMS,
                   help="Per-campaign maxCpm values (default: 2 4 6 8 10)")
    p.add_argument("--with-crawl", action="store_true",
                   help="Trigger a real crawl after site create and wait for "
                        "it to finish. Populates per-slot priors (qualityScore/"
                        "region/aboveFold) so the floor optimizer sees the same "
                        "per-slot multipliers production does. Requires the "
                        "seed-url to be reachable from the cluster (the local "
                        "publisher example must be running).")
    p.add_argument("--crawl-timeout", type=int, default=300,
                   help="Seconds to wait for the crawl to finish (default 300)")
    p.add_argument("--expect-slots", type=int, default=None,
                   help="If set, post-crawl the script asserts the site has "
                        "at least this many slots; useful for catching silent "
                        "discovery undercounts. localhost example has 56.")
    args = p.parse_args()

    api = args.api.rstrip("/")
    categories = DEFAULT_CATEGORIES

    log(f"target API: {api}")

    # 1. Publisher + advertiser (idempotent via email).
    pub_id = login_publisher(api, args.publisher_email)
    adv_id = login_advertiser(api, args.advertiser_email)

    # 2. Site + verify.
    ensure_site(api, pub_id, args.site_id, args.site_domain, args.seed_url, args.slot_id)
    force_verify(api, pub_id, args.site_id, args.site_domain)

    # 3. Advertiser budget — enough to cover all campaigns at their daily budget.
    total_adv_budget = float(args.budget) * len(args.cpms)
    ensure_advertiser_budget(api, adv_id, f"{total_adv_budget:g}")

    # 4. Campaigns + creatives.
    existing = list_campaigns(api, adv_id)
    log(f"existing campaigns under {adv_id}: {len(existing)}")
    campaigns_out: list[dict[str, Any]] = []
    target_categories = list(categories.keys())
    for max_cpm in args.cpms:
        cid = ensure_campaign(api, adv_id, existing, max_cpm,
                              args.ad_product, args.budget, args.landing_url,
                              target_categories)
        crid = ensure_creative(api, adv_id, cid, max_cpm, args.landing_url)
        campaigns_out.append({
            "maxCpm":     max_cpm,
            "id":         cid,
            "creativeId": crid,
        })

    # 5. Classify (so the auction has a page → category mapping).
    classify(api, pub_id, args.site_id, args.seed_url, categories, args.slot_id)

    # 6. Activate every campaign (idempotent — server treats setting to
    #    active when already active as a no-op).
    for c in campaigns_out:
        activate_campaign(api, adv_id, c["id"])

    # 7. Bulk-approve any newly-created creatives. We retry once after a
    #    short delay because the approval queue is fed by the auction
    #    triggered by classify; the entries can take a beat to land.
    time.sleep(1.5)
    approved, _ = approve_all(api, pub_id, args.site_id, args.seed_url, args.slot_id)
    if approved == 0:
        # All already-approved or queue hadn't filled yet — retry once.
        time.sleep(2.0)
        approve_all(api, pub_id, args.site_id, args.seed_url, args.slot_id)

    # 8. Reset pacing day-starts so AdServer's PI controller doesn't sit
    #    at ~99% throttle (its cold-start safety default). Without this,
    #    /serve/batch returns winner=null for every request until enough
    #    history accumulates — silent failure mode for rl-test runs.
    reset_pacing(api, pub_id, args.site_id, adv_id,
                 [c["id"] for c in campaigns_out])

    # 9. Optional crawl pass: populates AdSlotConfig.prior from real
    #    Playwright geometry. Skipped by default because it requires a
    #    reachable seed URL (the local publisher example must be served).
    discovered_slot_count: int | None = None
    if args.with_crawl:
        log("triggering crawl to populate per-slot priors...")
        trigger_crawl(api, pub_id, args.site_id)
        wait_for_crawl(api, pub_id, args.site_id, timeout_s=args.crawl_timeout)
        discovered_slot_count = get_site_slot_count(api, pub_id, args.site_id)
        log(f"post-crawl slot count: {discovered_slot_count}")
        if args.expect_slots is not None and discovered_slot_count < args.expect_slots:
            raise RuntimeError(
                f"expected at least {args.expect_slots} slots after crawl, "
                f"only got {discovered_slot_count}. Check maxDepth, seed-url "
                f"reachability, or the HTML/JS schema match.")

    # 10. Write fixture YAML.
    fixture = {
        "api":     api,
        "siteUrl": args.site_url,
        "publisher": {
            "email": args.publisher_email,
            "id":    pub_id,
        },
        "site": {
            "id":      args.site_id,
            "domain":  args.site_domain,
            "seedUrl": args.seed_url,
            "slotId":  args.slot_id,
            "classification": categories,
        },
        "advertiser": {
            "email": args.advertiser_email,
            "id":    adv_id,
        },
        "campaigns": campaigns_out,
        "adProductCategory": args.ad_product,
        "budgetPerCampaign": args.budget,
        "landingUrl":        args.landing_url,
        # Discovered-slot count from a real crawl. None when --with-crawl
        # wasn't passed; in that case every slot's prior is also absent
        # (effectiveFloor falls through to siteFloor flat) — fine for
        # site-level optimizer scenarios, but per-slot scenarios will be
        # mis-testing without a crawl pass.
        "crawlDiscoveredSlots": discovered_slot_count,
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(yaml.safe_dump(fixture, sort_keys=False))
    log(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
