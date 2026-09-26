const corsHeaders = {
  "Access-Control-Allow-Methods": "GET, HEAD, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Access-Control-Max-Age": "86400",
};

function isLoopbackHost(hostname) {
  return ["localhost", "127.0.0.1", "[::1]"].includes(hostname);
}

function isLoopbackOrigin(origin) {
  if (!origin) return true;
  try {
    const url = new URL(origin);
    return (url.protocol === "http:" || url.protocol === "https:") && isLoopbackHost(url.hostname);
  } catch {
    return false;
  }
}

function response(body = null, init = {}, origin = null) {
  const headers = new Headers(init.headers);
  for (const [name, value] of Object.entries(corsHeaders)) headers.set(name, value);
  if (origin) {
    headers.set("Access-Control-Allow-Origin", origin);
    headers.set("Vary", "Origin");
  }
  return new Response(body, { ...init, headers });
}

function objectKey(url) {
  const path = new URL(url).pathname.slice(1);
  if (!path) return null;
  return path.split("/").map(decodeURIComponent).join("/");
}

export function byteRange(value, size) {
  if (size <= 0) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(value || "");
  if (!match || (match[1] === "" && match[2] === "")) return null;

  let start;
  let end;
  if (match[1] === "") {
    const suffix = Number(match[2]);
    if (!Number.isSafeInteger(suffix) || suffix <= 0) return null;
    start = Math.max(size - suffix, 0);
    end = size - 1;
  } else {
    start = Number(match[1]);
    end = match[2] === "" ? size - 1 : Number(match[2]);
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start > end) return null;
    if (start >= size) return null;
    end = Math.min(end, size - 1);
  }
  return { offset: start, length: end - start + 1, end };
}

export default {
  async fetch(request, env) {
    const requestUrl = new URL(request.url);
    if (!isLoopbackHost(requestUrl.hostname)) return new Response("Forbidden", { status: 403 });
    const origin = request.headers.get("Origin");
    if (!isLoopbackOrigin(origin)) return new Response("Forbidden", { status: 403 });
    const respond = (body = null, init = {}) => response(body, init, origin);

    if (request.method === "OPTIONS") return respond(null, { status: 204 });
    if (requestUrl.pathname === "/health") {
      return respond(env.LOCAL_INSTANCE_ID || "ok");
    }

    const key = objectKey(request.url);
    if (!key) return respond("Object key is required", { status: 400 });

    if (request.method === "PUT") {
      await env.BUCKET.put(key, request.body, {
        httpMetadata: {
          contentType: request.headers.get("Content-Type") || "application/octet-stream",
          cacheControl: request.headers.get("Cache-Control") || undefined,
        },
      });
      return respond(null, { status: 201 });
    }

    if (request.method === "GET") {
      const metadata = await env.BUCKET.head(key);
      if (metadata === null) return respond("Not found", { status: 404 });
      const headers = new Headers();
      metadata.writeHttpMetadata(headers);
      headers.set("etag", metadata.httpEtag);
      headers.set("accept-ranges", "bytes");

      const rangeHeader = request.headers.get("Range");
      if (!rangeHeader || !rangeHeader.startsWith("bytes=") || rangeHeader.includes(",")) {
        const object = await env.BUCKET.get(key);
        if (object === null) return respond("Not found", { status: 404 });
        return respond(object.body, { headers });
      }

      const range = byteRange(rangeHeader, metadata.size);
      if (!range) {
        headers.set("content-range", `bytes */${metadata.size}`);
        return respond(null, { status: 416, headers });
      }
      const object = await env.BUCKET.get(key, {
        range: { offset: range.offset, length: range.length },
      });
      if (object === null) return respond("Not found", { status: 404 });
      headers.set("content-range", `bytes ${range.offset}-${range.end}/${metadata.size}`);
      headers.set("content-length", String(range.length));
      return respond(object.body, { status: 206, headers });
    }

    if (request.method === "HEAD") {
      const object = await env.BUCKET.head(key);
      if (object === null) return respond(null, { status: 404 });
      const headers = new Headers();
      object.writeHttpMetadata(headers);
      headers.set("etag", object.httpEtag);
      headers.set("content-length", String(object.size));
      headers.set("accept-ranges", "bytes");
      return respond(null, { headers });
    }

    if (request.method === "DELETE") {
      await env.BUCKET.delete(key);
      return respond(null, { status: 204 });
    }

    return respond("Method not allowed", {
      status: 405,
      headers: { Allow: "GET, HEAD, PUT, DELETE, OPTIONS" },
    });
  },
};
