// IndexedDB persistence for the dog-ear feature. Two stores:
//
//   `pins`        — keyed by slotId. The bookmark itself: which creative
//                   the user folded on which slot, and the page within
//                   the magazine. Read on every page load by the
//                   bootstrap so the server can honor the pin.
//
//   `ts_counted`  — keyed by creativeId. "Server has been told about
//                   this fold." Lets the bootstrap skip duplicate
//                   /v1/dogear-event POSTs when the user folds → unfolds
//                   → re-folds the same creative inside the dedup
//                   window. Without it, every refold tap inflates the
//                   server-side fold posterior with the same intent.
//
// Both stores live on the publisher origin (IndexedDB is same-origin)
// and never leave the browser. Both records carry `expiresAt` for
// read-time + sweep-time cleanup.
//
// Expiry policy: pins (and the matching ts_counted records) live until
// the campaign's endAt, OR forever (sentinel = +Infinity) when the
// server doesn't provide an endAt. The previous 7-day default and
// 90-day hard cap were dropped — bookmark intent doesn't have an
// arbitrary fade-out date, only the campaign's own end-of-life.
// Cleanup happens on creative_removed (server signal) or unfold
// (user action), not by the clock.
//
// Per spec design principles: NO personal identifier, NO cross-origin
// storage, NO sync, NO targeting. The pin is the user's own bookmark
// on their own browser.

const DB_NAME = "promovolve-dogear";
// Bumped to 2 to add the `ts_counted` object store. Existing browsers
// running the v1 schema will get an upgradeneeded event and have the
// new store created on next page load — no migration needed because
// `ts_counted` is purely additive (deduping fold POSTs that were
// previously always sent).
// 3 adds the `impressions` store (frequency capping — see recordImpression).
// 4 adds the `reach` store (reach counting — see takeReachReport).
const DB_VERSION = 4;
const STORE = "pins";
const COUNTED_STORE = "ts_counted";
// Frequency capping (docs/design/FREQUENCY_CAPPING.md): one row per BILLED
// impression of an auction winner, with the campaign's cap policy as it
// stood at that moment. Read before every batch to compute the campaigns
// this browser declines; never leaves the browser except as that list.
const IMPRESSIONS_STORE = "impressions";
// Reach (GH #238): per campaign, the server-defined day this browser last
// had a viewable impression of it on this site, and a one-time freshness
// value. Only ever leaves the browser as one first-of-day report.
const REACH_STORE = "reach";

// Sentinel used when no campaign endAt is provided — the pin (and the
// matching ts_counted record) lives forever. Number.POSITIVE_INFINITY
// round-trips through IndexedDB's Structured Clone unchanged, and the
// freshness checks (`now < expiresAt`) stay true indefinitely.
const FOREVER: number = Number.POSITIVE_INFINITY;

export interface Pin {
  slotId: string;
  creativeId: string;
  page: number;
  foldedAt: number;
  // Absolute expiry as epoch millis. Equals the campaign's endAt when
  // the server gave us one; otherwise +Infinity (no expiry — only
  // creative_removed or user-unfold can clear the pin).
  expiresAt: number;
  // Last time this pin was "visited" — i.e., the server honored it on
  // a page load. Bumped by the bootstrap after each serve response
  // that includes this pin in its dogear.honored set. Pins that
  // haven't been visited within IDLE_WINDOW_MS are swept on the next
  // page load (use-it-or-lose-it). Defaults to foldedAt so a freshly
  // created pin gets a 24h grace period before activity counts.
  lastSeenAt: number;
}

/** A pin that hasn't been visited (re-honored) within this many ms is
  * considered abandoned and gets cleared on the next page load. */
export const IDLE_WINDOW_MS = 24 * 60 * 60 * 1000; // 24 hours

/** Compute the pin's effective expiry from a server-supplied campaign
  * endAt. End-of-campaign when known, forever when not. */
export function pinExpiresAt(
  _foldedAt: number,
  serverPinExpiresAt: number | undefined,
): number {
  if (serverPinExpiresAt !== undefined && serverPinExpiresAt > 0) {
    return serverPinExpiresAt;
  }
  return FOREVER;
}

// Hard deadline on EVERY IndexedDB call. WebKit can leave
// `indexedDB.open()` — and individual requests on an open connection —
// without ever firing onsuccess, onerror OR onblocked. Nothing throws
// and nothing logs; the promise simply never settles.
//
// That is not a storage problem, it is a blank-page problem: the
// bootstrap awaits getAllPins() before it sends /v1/serve/batch, so a
// hung open means no serve request, no render, and a slot left at its
// reserved height — an empty white box. collapseEmptyDivs can't save it
// either, because that runs downstream of the batch too. Reported on
// real iPhones, intermittent, worse on reload, never on Chrome.
//
// 400ms against the batch's own 1000ms budget: long enough that a
// healthy device (sub-millisecond, in-process) never trips it, short
// enough that a wedged one still gets its ad. Losing the read costs the
// bookmark for THIS pageview only — nothing is deleted, so the pin is
// honored again on the next load.
const IDB_TIMEOUT_MS = 400;

let dbPromise: Promise<IDBDatabase> | null = null;

/**
 * Run an IndexedDB operation under the deadline, resolving to `fallback`
 * if the open or the request doesn't answer in time. Never rejects — the
 * dog-ear store is an enhancement, and no caller should have to guard
 * against it to stay alive.
 *
 * `run` receives the open db and a resolve callback; it keeps each
 * operation's own success/error handling exactly as it was, so the
 * behaviour on a *failing* IndexedDB is unchanged. Only the previously
 * unhandled case — one that never answers at all — is new.
 */
function idb<T>(
  fallback: T,
  run: (db: IDBDatabase, resolve: (value: T) => void) => void,
): Promise<T> {
  return new Promise<T>((resolve) => {
    let settled = false;
    const done = (value: T): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(value);
    };
    const timer = setTimeout(() => {
      if (settled) return;
      // Drop the cached connection. If the OPEN is what hung, every
      // later call would otherwise await the same dead promise for the
      // life of the page; clearing it lets the next call try afresh.
      dbPromise = null;
      console.warn("[promovolve] indexedDB timed out — serving without dog-ear state");
      done(fallback);
    }, IDB_TIMEOUT_MS);
    openDb().then(
      (db) => {
        if (settled) return;
        try {
          run(db, done);
        } catch {
          // A transaction can throw synchronously (InvalidStateError on a
          // connection the OS closed under us).
          done(fallback);
        }
      },
      () => done(fallback),
    );
  });
}

function openDb(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise<IDBDatabase>((resolve, reject) => {
    if (typeof indexedDB === "undefined") {
      reject(new Error("indexedDB unavailable"));
      return;
    }
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: "slotId" });
      }
      if (!db.objectStoreNames.contains(COUNTED_STORE)) {
        db.createObjectStore(COUNTED_STORE, { keyPath: "creativeId" });
      }
      if (!db.objectStoreNames.contains(IMPRESSIONS_STORE)) {
        db.createObjectStore(IMPRESSIONS_STORE, { keyPath: "id", autoIncrement: true });
      }
      if (!db.objectStoreNames.contains(REACH_STORE)) {
        db.createObjectStore(REACH_STORE, { keyPath: "campaignId" });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error("indexedDB open failed"));
    req.onblocked = () => reject(new Error("indexedDB open blocked"));
  });
  return dbPromise;
}

function isFresh(pin: Pin, now: number = Date.now()): boolean {
  if (now >= pin.expiresAt) return false;
  // Idle sweep: a pin not visited within IDLE_WINDOW_MS is treated as
  // abandoned. Older records (pre-lastSeenAt schema) get a one-time
  // pass by falling back to foldedAt so they aren't deleted just for
  // not having the field yet.
  const lastSeen = pin.lastSeenAt ?? pin.foldedAt;
  if (lastSeen > 0 && now - lastSeen > IDLE_WINDOW_MS) return false;
  return true;
}

/** Read a pin for a slot. Expired records are deleted in this same
 *  pass (read-time cleanup). Returns null when no pin exists, the pin
 *  expired, or IndexedDB is unavailable.
 */
export function getPin(slotId: string): Promise<Pin | null> {
  return idb<Pin | null>(null, (db, resolve) => {
    const tx = db.transaction(STORE, "readwrite");
    const store = tx.objectStore(STORE);
    const req = store.get(slotId);
    req.onsuccess = () => {
      const pin = req.result as Pin | undefined;
      if (!pin) {
        resolve(null);
        return;
      }
      if (!isFresh(pin)) {
        store.delete(slotId);
        resolve(null);
        return;
      }
      resolve(pin);
    };
    req.onerror = () => resolve(null);
  });
}

/** Read every fresh pin in the store. Expired records are deleted
 *  in the same pass (read-time cleanup, mirrors getPin). Used by
 *  display() to send all pins as hints — pins for slots not on the
 *  current page become "exclude this creative everywhere" hints
 *  server-side, so the user never encounters their pinned creative
 *  in some random slot on a different page.
 */
export function getAllPins(): Promise<Pin[]> {
  return idb<Pin[]>([], (db, resolve) => {
    const tx = db.transaction(STORE, "readwrite");
    const store = tx.objectStore(STORE);
    const req = store.getAll();
    req.onsuccess = () => {
      const all = (req.result as Pin[]) ?? [];
      const fresh: Pin[] = [];
      const now = Date.now();
      for (const pin of all) {
        if (isFresh(pin, now)) fresh.push(pin);
        else store.delete(pin.slotId);
      }
      resolve(fresh);
    };
    req.onerror = () => resolve([]);
  });
}

/** Bump lastSeenAt on a slot's pin so it survives the idle sweep on
 *  the next page load. Called by the bootstrap whenever the serve
 *  response honors the slot's pin — i.e., the reader effectively
 *  "visited" the pinned creative. No-op when no pin exists or
 *  IndexedDB is unavailable.
 */
export function touchPin(slotId: string, now: number = Date.now()): Promise<void> {
  return idb<void>(undefined, (db, resolve) => {
    const tx = db.transaction(STORE, "readwrite");
    const store = tx.objectStore(STORE);
    const req = store.get(slotId);
    req.onsuccess = () => {
      const pin = req.result as Pin | undefined;
      if (!pin) {
        resolve();
        return;
      }
      pin.lastSeenAt = now;
      store.put(pin);
    };
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
    tx.onabort = () => resolve();
  });
}

/** Write or replace a pin. Refolding the same slot overwrites the
 *  prior record with new page + foldedAt.
 */
export function setPin(pin: Pin): Promise<void> {
  return idb<void>(undefined, (db, resolve) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).put(pin);
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve(); // Swallow — IndexedDB write failure shouldn't break the page.
    tx.onabort = () => resolve();
  });
}

/** Delete a pin. No-op if the pin doesn't exist or IndexedDB is
 *  unavailable.
 */
export function clearPin(slotId: string): Promise<void> {
  return idb<void>(undefined, (db, resolve) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).delete(slotId);
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
    tx.onabort = () => resolve();
  });
}

/** Delete every pin (and its ts_counted record) whose creativeId is in
 *  `creativeIds` — the server's `stalePins` signal: these pins' creative
 *  is gone or their slot no longer exists on the site, so the per-slot
 *  dogear channel can never reconcile them. Pins are keyed by slotId in
 *  IDB, so this scans the (small) pin store and matches on creativeId.
 */
export async function clearPinsByCreativeIds(creativeIds: string[]): Promise<void> {
  if (creativeIds.length === 0) return;
  const stale = new Set(creativeIds);
  const all = await getAllPins();
  await Promise.all(
    all
      .filter((p) => stale.has(p.creativeId))
      .flatMap((p) => [clearPin(p.slotId), clearCounted(p.creativeId)]),
  );
}

/** One-shot sweep that deletes every expired record across both stores.
 *  Called once at bootstrap init. With the new policy (end-of-campaign
 *  or forever) most records won't trigger here — entries with
 *  expiresAt = Infinity skip the predicate and only clear via
 *  creative_removed or unfold. Records DO expire when the server gave
 *  us a campaign endAt and that endAt has passed. Cheap — IndexedDB
 *  cursors on a per-origin store with at most ~hundreds of entries.
 */
export function sweepExpired(): Promise<void> {
  return idb<void>(undefined, (db, resolve) => {
    const tx = db.transaction([STORE, COUNTED_STORE], "readwrite");
    const now = Date.now();
    const pinCursor = tx.objectStore(STORE).openCursor();
    pinCursor.onsuccess = () => {
      const cursor = pinCursor.result;
      if (!cursor) return;
      const pin = cursor.value as Pin;
      if (!isFresh(pin, now)) cursor.delete();
      cursor.continue();
    };
    const countedCursor = tx.objectStore(COUNTED_STORE).openCursor();
    countedCursor.onsuccess = () => {
      const cursor = countedCursor.result;
      if (!cursor) return;
      const rec = cursor.value as Counted;
      if (now >= rec.expiresAt) cursor.delete();
      cursor.continue();
    };
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
    tx.onabort = () => resolve();
  });
}

// ─── ts_counted operations ───────────────────────────────────────────

/** Record that the server has already been notified about a fold of
  * this creative. The bootstrap consults this before POSTing
  * /v1/dogear-event so refolds inside the dedup window are silent on
  * the network and on the server-side fold posterior.
  *
  * Keyed by creativeId rather than slotId because a fold is a
  * per-creative engagement signal — the same creative folded on two
  * different slots is one piece of intent, not two.
  */
export interface Counted {
  creativeId: string;
  countedAt: number;
  expiresAt: number;
  // Set once the matching unfold has been reported to the server. The
  // server's retention metric is (folds - unfolds)/folds, so an unfold
  // may only be sent against a fold we actually reported, and only
  // once — folds are deduped per creative, and an undeduped unfold
  // would let a fold/unfold/refold cycle drive the numerator negative.
  // Absent on records written before unfold reporting existed, which
  // reads as "not yet reported" and is the correct default.
  unfoldReportedAt?: number;
}

/** Returns true if the server has been told about a fresh fold for
 *  this creativeId. Stale entries are deleted in the same pass. */
export function wasCounted(creativeId: string): Promise<boolean> {
  return idb<boolean>(false, (db, resolve) => {
    const tx = db.transaction(COUNTED_STORE, "readwrite");
    const store = tx.objectStore(COUNTED_STORE);
    const req = store.get(creativeId);
    req.onsuccess = () => {
      const rec = req.result as Counted | undefined;
      if (!rec) {
        resolve(false);
        return;
      }
      const now = Date.now();
      if (now >= rec.expiresAt) {
        store.delete(creativeId);
        resolve(false);
        return;
      }
      resolve(true);
    };
    req.onerror = () => resolve(false);
  });
}

/** Mark a creative's fold as already counted server-side. Called only
 *  after a successful /v1/dogear-event POST so a network failure
 *  doesn't poison the dedup state. expiresAt should match the pin's
 *  expiry (or DEFAULT_TTL_MS) so dedup outlives a refold cycle but
 *  not the natural campaign window.
 */
export function markCounted(creativeId: string, expiresAt: number): Promise<void> {
  return idb<void>(undefined, (db, resolve) => {
    const tx = db.transaction(COUNTED_STORE, "readwrite");
    tx.objectStore(COUNTED_STORE).put({
      creativeId,
      countedAt: Date.now(),
      expiresAt,
    } satisfies Counted);
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
    tx.onabort = () => resolve();
  });
}

/** Claim the right to report ONE unfold for this creative, atomically.
 *
 *  Returns true exactly once per counted fold. The unfold beacon is the
 *  mirror of the fold beacon, so it inherits the same dedup: a fold
 *  that was never reported (deduped as a refold, or pinned in an
 *  earlier window) has nothing for the server to subtract, and a
 *  second unfold of the same fold would double-subtract. Both cases
 *  return false and the caller sends nothing.
 *
 *  Deliberately does NOT delete the counted record — see clearCounted
 *  below. The claim flag rides on the same record so it expires with
 *  it, and check-and-set happen in one transaction so two unfold
 *  events in the same tick can't both claim.
 */
export function claimUnfoldReport(creativeId: string): Promise<boolean> {
  return idb<boolean>(false, (db, resolve) => {
    const tx = db.transaction(COUNTED_STORE, "readwrite");
    const store = tx.objectStore(COUNTED_STORE);
    const req = store.get(creativeId);
    req.onsuccess = () => {
      const rec = req.result as Counted | undefined;
      // No reported fold to pair with — nothing to report.
      if (!rec) {
        resolve(false);
        return;
      }
      const now = Date.now();
      if (now >= rec.expiresAt) {
        store.delete(creativeId);
        resolve(false);
        return;
      }
      if (rec.unfoldReportedAt !== undefined) {
        resolve(false);
        return;
      }
      store.put({ ...rec, unfoldReportedAt: now } satisfies Counted);
      resolve(true);
    };
    req.onerror = () => resolve(false);
  });
}

/** Drop a creative's counted record. Called when the underlying
 *  creative is removed (server signals creative_removed) so a future
 *  resurrected campaign starts with a clean dedup slate. NOT called on
 *  unfold — unfolding shouldn't re-arm "I haven't told the server",
 *  otherwise unfold→refold flips dedup on every cycle.
 */
export function clearCounted(creativeId: string): Promise<void> {
  return idb<void>(undefined, (db, resolve) => {
    const tx = db.transaction(COUNTED_STORE, "readwrite");
    tx.objectStore(COUNTED_STORE).delete(creativeId);
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
    tx.onabort = () => resolve();
  });
}

// ─── Frequency capping: impression records ───────────────────────

export interface Impression {
  id?: number;          // autoIncrement key
  campaignId: string;
  creativeId: string;
  at: number;           // epoch ms when the impression beacon fired
  n: number;            // the campaign's cap at that moment
  windowMs: number;     // …and its window
}

/** Longest window the server can send (7 days); older rows are useless. */
export const IMPRESSION_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
/** Hard size cap; a heavy reader never grows this store unboundedly. */
export const MAX_IMPRESSION_RECORDS = 500;

/** Append one billed impression and prune: drop rows older than the
 *  retention window and, if still over the size cap, the oldest rows.
 *  Best-effort — a failed write loses a count, never a render. */
export function recordImpression(rec: Impression): Promise<void> {
  return idb<void>(undefined, (db, resolve) => {
    const tx = db.transaction(IMPRESSIONS_STORE, "readwrite");
    const store = tx.objectStore(IMPRESSIONS_STORE);
    store.add({ campaignId: rec.campaignId, creativeId: rec.creativeId, at: rec.at, n: rec.n, windowMs: rec.windowMs });
    const req = store.getAll();
    req.onsuccess = () => {
      const all = ((req.result as Impression[]) ?? []).slice().sort((a, b) => a.at - b.at);
      const cutoff = rec.at - IMPRESSION_RETENTION_MS;
      let excess = Math.max(0, all.length - MAX_IMPRESSION_RECORDS);
      for (const row of all) {
        if (row.id === undefined) continue;
        if (row.at < cutoff || excess > 0) {
          store.delete(row.id);
          if (row.at >= cutoff) excess--;
        }
      }
    };
    tx.oncomplete = () => resolve(undefined);
    tx.onerror = () => resolve(undefined);
    tx.onabort = () => resolve(undefined);
  });
}

/** Re-stamp stored impressions of these campaigns with the policy the
 *  server just reported.
 *
 *  A campaign the browser is declining can never win, so it can never carry
 *  a fresh policy back on a winner — this is the only way a REMOVED cap ever
 *  reaches storage. The policy is a property of the campaign, so every one of
 *  its rows is updated; `n = 0` then means those rows never cap again.
 *  Best-effort, like every write here: a failure just leaves the old stamp
 *  and the next page load asks again. */
export function applyCapPolicies(
  policies: Array<{ campaignId: string; n: number; windowMs: number }>,
): Promise<void> {
  if (policies.length === 0) return Promise.resolve();
  const byCampaign = new Map(policies.map((p) => [p.campaignId, p]));
  return idb<void>(undefined, (db, resolve) => {
    const tx = db.transaction(IMPRESSIONS_STORE, "readwrite");
    const store = tx.objectStore(IMPRESSIONS_STORE);
    const req = store.getAll();
    req.onsuccess = () => {
      for (const row of (req.result as Impression[]) ?? []) {
        const p = byCampaign.get(row.campaignId);
        if (!p || row.id === undefined) continue;
        if (p.n === row.n && p.windowMs === row.windowMs) continue;
        store.put({ ...row, n: p.n, windowMs: p.windowMs });
      }
    };
    tx.oncomplete = () => resolve(undefined);
    tx.onerror = () => resolve(undefined);
    tx.onabort = () => resolve(undefined);
  });
}

/** All impressions inside the retention window; older rows are deleted
 *  in the same pass (read-time sweep, like getAllPins). */
export function getImpressions(now: number = Date.now()): Promise<Impression[]> {
  return idb<Impression[]>([], (db, resolve) => {
    const tx = db.transaction(IMPRESSIONS_STORE, "readwrite");
    const store = tx.objectStore(IMPRESSIONS_STORE);
    const req = store.getAll();
    req.onsuccess = () => {
      const all = (req.result as Impression[]) ?? [];
      const cutoff = now - IMPRESSION_RETENTION_MS;
      const fresh: Impression[] = [];
      for (const row of all) {
        if (row.at >= cutoff) fresh.push(row);
        else if (row.id !== undefined) store.delete(row.id);
      }
      resolve(fresh);
    };
    req.onerror = () => resolve([]);
  });
}

// ─── Reach: one report per campaign per day ──────────────────────

interface ReachRecord {
  campaignId: string;
  day: number;   // the server's day number (advertiser-local epoch day)
  fresh: string; // sent with the NEXT report, then replaced
}

export interface ReachReport {
  prev: number | null; // day last seen here; null = never
  fresh: string;
}

/** 16 random bytes as lowercase hex. */
export function newFresh(): string {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  return Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
}

/** Claim today's reach report for a campaign, in ONE transaction so two
 *  slots of the same campaign on a page can't both report. Returns null
 *  when it was already reported today, or when storage is unavailable —
 *  without storage we can't tell a repeat visit from a new one, so we
 *  don't count rather than overcount. The returned `fresh` is the value
 *  stored with the previous day (Chrome updater protocol v4's
 *  ping_freshness); a new one is stored with `day`. */
export function takeReachReport(campaignId: string, day: number): Promise<ReachReport | null> {
  return idb<ReachReport | null>(null, (db, resolve) => {
    const tx = db.transaction(REACH_STORE, "readwrite");
    const store = tx.objectStore(REACH_STORE);
    let out: ReachReport | null = null;
    const req = store.get(campaignId);
    req.onsuccess = () => {
      const prev = req.result as ReachRecord | undefined;
      // Same day (or a day number going backwards after a timezone change):
      // nothing new to report.
      if (prev && prev.day >= day) return;
      out = { prev: prev ? prev.day : null, fresh: prev ? prev.fresh : newFresh() };
      store.put({ campaignId, day, fresh: newFresh() } satisfies ReachRecord);
    };
    tx.oncomplete = () => resolve(out);
    tx.onerror = () => resolve(null);
    tx.onabort = () => resolve(null);
  });
}
