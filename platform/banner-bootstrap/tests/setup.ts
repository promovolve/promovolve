// Test setup: install fake-indexeddb so storage tests have a real
// in-memory IDBDatabase implementation. Must run before any module
// reads `globalThis.indexedDB`.
import "fake-indexeddb/auto";
