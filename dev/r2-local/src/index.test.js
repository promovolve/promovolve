import assert from "node:assert/strict";
import test from "node:test";

import worker, { byteRange } from "./index.js";

const bytes = new TextEncoder().encode("0123456789");

function object(body = bytes) {
  return {
    body,
    size: bytes.length,
    httpEtag: '"test-etag"',
    writeHttpMetadata(headers) {
      headers.set("Content-Type", "video/mp4");
    },
  };
}

function env() {
  return {
    LOCAL_INSTANCE_ID: "test-instance",
    BUCKET: {
      async head() {
        return object();
      },
      async get(_key, options) {
        if (!options?.range) return object();
        const { offset, length } = options.range;
        return object(bytes.slice(offset, offset + length));
      },
      async put() {},
      async delete() {},
    },
  };
}

test("parses closed, open, and suffix byte ranges", () => {
  assert.deepEqual(byteRange("bytes=2-5", 10), { offset: 2, length: 4, end: 5 });
  assert.deepEqual(byteRange("bytes=7-", 10), { offset: 7, length: 3, end: 9 });
  assert.deepEqual(byteRange("bytes=-3", 10), { offset: 7, length: 3, end: 9 });
});

test("returns a partial response for a satisfiable range", async () => {
  const result = await worker.fetch(new Request("http://127.0.0.1:8787/video.mp4", {
    headers: { Range: "bytes=2-5" },
  }), env());

  assert.equal(result.status, 206);
  assert.equal(result.headers.get("Content-Range"), "bytes 2-5/10");
  assert.equal(result.headers.get("Accept-Ranges"), "bytes");
  assert.equal(await result.text(), "2345");
});

test("returns object metadata without a body for HEAD", async () => {
  const testEnv = env();
  let getCalls = 0;
  testEnv.BUCKET.get = async () => {
    getCalls += 1;
    return object();
  };
  const result = await worker.fetch(new Request("http://127.0.0.1:8787/video.mp4", {
    method: "HEAD",
  }), testEnv);

  assert.equal(result.status, 200);
  assert.equal(result.headers.get("Content-Length"), "10");
  assert.equal(await result.text(), "");
  assert.equal(getCalls, 0);
});

test("returns not found for HEAD when the object does not exist", async () => {
  const testEnv = env();
  let getCalls = 0;
  testEnv.BUCKET.head = async () => null;
  testEnv.BUCKET.get = async () => {
    getCalls += 1;
    return object();
  };

  const result = await worker.fetch(new Request("http://127.0.0.1:8787/missing.mp4", {
    method: "HEAD",
  }), testEnv);

  assert.equal(result.status, 404);
  assert.equal(await result.text(), "");
  assert.equal(getCalls, 0);
});

test("ignores unsupported multiple ranges", async () => {
  const result = await worker.fetch(new Request("http://127.0.0.1:8787/video.mp4", {
    headers: { Range: "bytes=0-1,4-5" },
  }), env());

  assert.equal(result.status, 200);
  assert.equal(await result.text(), "0123456789");
});

test("rejects an unsatisfiable single range", async () => {
  const result = await worker.fetch(new Request("http://127.0.0.1:8787/video.mp4", {
    headers: { Range: "bytes=20-30" },
  }), env());

  assert.equal(result.status, 416);
  assert.equal(result.headers.get("Content-Range"), "bytes */10");
});

test("rejects browser requests from non-loopback origins", async () => {
  const result = await worker.fetch(new Request("http://127.0.0.1:8787/video.mp4", {
    method: "PUT",
    headers: { Origin: "https://example.com" },
    body: bytes,
    duplex: "half",
  }), env());

  assert.equal(result.status, 403);
});

test("rejects requests addressed to a non-loopback host", async () => {
  const result = await worker.fetch(new Request("http://192.0.2.10:8787/video.mp4"), env());
  assert.equal(result.status, 403);
});

test("identifies the Worker instance through health", async () => {
  const result = await worker.fetch(new Request("http://127.0.0.1:8787/health"), env());
  assert.equal(await result.text(), "test-instance");
});
