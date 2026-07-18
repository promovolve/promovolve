import { beforeEach, describe, expect, it, vi } from "vitest";

// Stage/ok derivation for the mount heartbeat — the semantics the
// publisher health panel depends on. The load-bearing distinction:
// "serve answered with zero winners" is a HEALTHY integration
// (no_fill, economics), while "served > 0 but nothing painted" and
// "serve unreachable" are broken ones.

async function load(): Promise<typeof import("../src/heartbeat")> {
  vi.resetModules(); // module-global state → fresh per test
  return import("../src/heartbeat");
}

describe("hbCompose stage/ok derivation", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("returns null without a pub (unattributable beacons are dropped)", async () => {
    const hb = await load();
    expect(hb.hbCompose()).toBeNull();
  });

  it("script stage + no_slots when no slot divs were found", async () => {
    const hb = await load();
    hb.hbInit("site-a", "https://api.example.com");
    hb.hbSlots(0);
    expect(hb.hbCompose()).toMatchObject({
      stage: "script", ok: false, reason: "no_slots", slots: 0,
    });
  });

  it("slot stage + serve_unreachable when the batch never answered", async () => {
    const hb = await load();
    hb.hbInit("site-a", "https://api.example.com");
    hb.hbSlots(2);
    hb.hbServe(false, 0, "timeout");
    expect(hb.hbCompose()).toMatchObject({
      stage: "slot", ok: false, reason: "timeout", slots: 2,
    });
  });

  it("serve stage + ok when the batch answered with zero winners (no-fill is healthy)", async () => {
    const hb = await load();
    hb.hbInit("site-a", "https://api.example.com");
    hb.hbSlots(2);
    hb.hbServe(true, 0);
    expect(hb.hbCompose()).toMatchObject({
      stage: "serve", ok: true, reason: "no_fill", served: 0,
    });
  });

  it("serve stage + mount_failed when winners came back but nothing painted", async () => {
    const hb = await load();
    hb.hbInit("site-a", "https://api.example.com");
    hb.hbSlots(2);
    hb.hbServe(true, 2);
    hb.hbMounted(0);
    expect(hb.hbCompose()).toMatchObject({
      stage: "serve", ok: false, reason: "mount_failed", served: 2, mounted: 0,
    });
  });

  it("render stage + ok when at least one winner painted", async () => {
    const hb = await load();
    hb.hbInit("site-a", "https://api.example.com");
    hb.hbSlots(2);
    hb.hbServe(true, 2);
    hb.hbMounted(2);
    expect(hb.hbCompose()).toMatchObject({
      stage: "render", ok: true, slots: 2, served: 2, mounted: 2,
    });
  });
});

describe("hbSend transport", () => {
  it("POSTs once, keepalive, no credentials — and never twice", async () => {
    const hb = await load();
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    hb.hbInit("site-a", "https://api.example.com");
    hb.hbSlots(1);
    hb.hbServe(true, 1);
    hb.hbMounted(1);
    hb.hbSend();
    hb.hbSend(); // pagehide flush after settle-path send must no-op
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.example.com/v1/beacon/mount");
    expect(init.keepalive).toBe(true);
    expect(init.credentials).toBe("omit");
    expect(JSON.parse(init.body as string)).toMatchObject({
      v: 1, pub: "site-a", stage: "render", ok: true,
    });
    vi.unstubAllGlobals();
  });

  it("drops the beacon when apiBase never resolved (would hit the publisher origin)", async () => {
    const hb = await load();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    hb.hbInit("site-a", "");
    hb.hbSlots(1);
    hb.hbServe(true, 1);
    hb.hbMounted(1);
    hb.hbSend();
    expect(fetchMock).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });
});
