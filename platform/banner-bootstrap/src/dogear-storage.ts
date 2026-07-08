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
const DB_VERSION = 2;
const STORE = "pins";
const COUNTED_STORE = "ts_counted";

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

let dbPromise: Promise<IDBDatabase> | null = null;

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
export async function getPin(slotId: string): Promise<Pin | null> {
  let db: IDBDatabase;
  try {
    db = await openDb();
  } catch {
    return null;
  }
  return new Promise<Pin | null>((resolve) => {
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
export async function getAllPins(): Promise<Pin[]> {
  let db: IDBDatabase;
  try {
    db = await openDb();
  } catch {
    return [];
  }
  return new Promise<Pin[]>((resolve) => {
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
export async function touchPin(slotId: string, now: number = Date.now()): Promise<void> {
  let db: IDBDatabase;
  try {
    db = await openDb();
  } catch {
    return;
  }
  return new Promise<void>((resolve) => {
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
export async function setPin(pin: Pin): Promise<void> {
  let db: IDBDatabase;
  try {
    db = await openDb();
  } catch {
    return;
  }
  return new Promise<void>((resolve) => {
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
export async function clearPin(slotId: string): Promise<void> {
  let db: IDBDatabase;
  try {
    db = await openDb();
  } catch {
    return;
  }
  return new Promise<void>((resolve) => {
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
export async function sweepExpired(): Promise<void> {
  let db: IDBDatabase;
  try {
    db = await openDb();
  } catch {
    return;
  }
  return new Promise<void>((resolve) => {
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
}

/** Returns true if the server has been told about a fresh fold for
 *  this creativeId. Stale entries are deleted in the same pass. */
export async function wasCounted(creativeId: string): Promise<boolean> {
  let db: IDBDatabase;
  try {
    db = await openDb();
  } catch {
    return false;
  }
  return new Promise<boolean>((resolve) => {
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
export async function markCounted(creativeId: string, expiresAt: number): Promise<void> {
  let db: IDBDatabase;
  try {
    db = await openDb();
  } catch {
    return;
  }
  return new Promise<void>((resolve) => {
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

/** Drop a creative's counted record. Called when the underlying
 *  creative is removed (server signals creative_removed) so a future
 *  resurrected campaign starts with a clean dedup slate. NOT called on
 *  unfold — unfolding shouldn't re-arm "I haven't told the server",
 *  otherwise unfold→refold flips dedup on every cycle.
 */
export async function clearCounted(creativeId: string): Promise<void> {
  let db: IDBDatabase;
  try {
    db = await openDb();
  } catch {
    return;
  }
  return new Promise<void>((resolve) => {
    const tx = db.transaction(COUNTED_STORE, "readwrite");
    tx.objectStore(COUNTED_STORE).delete(creativeId);
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
    tx.onabort = () => resolve();
  });
}
