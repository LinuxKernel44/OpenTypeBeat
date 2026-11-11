// Relays messages between the page content scripts and the native OpenTypeBeat app.
"use strict";

const NATIVE_APP = "opentypebeat";
const contentPorts = new Set();
let nativePort = null;

// ---- Proof-of-origin (PO) token capture ---------------------------------------------------------
// The page runs YouTube's own attestation (BotGuard) and sends the resulting PO token in its
// requests. We read it from those requests so that the native extractors can present the same
// token for the same visitor.

function readVarint(buf, pos) {
  let result = 0;
  let mul = 1;
  let b;
  do {
    if (pos >= buf.length) throw new Error("truncated varint");
    b = buf[pos++];
    result += (b & 0x7f) * mul;
    mul *= 128;
  } while (b & 0x80);
  return [result, pos];
}

function parseFields(buf) {
  const out = [];
  let pos = 0;
  while (pos < buf.length) {
    let key;
    [key, pos] = readVarint(buf, pos);
    const field = Math.floor(key / 8);
    const wire = key & 7;
    if (wire === 0) {
      let v;
      [v, pos] = readVarint(buf, pos);
      out.push({ field, wire, value: v });
    } else if (wire === 2) {
      let len;
      [len, pos] = readVarint(buf, pos);
      out.push({ field, wire, bytes: buf.subarray(pos, pos + len) });
      pos += len;
    } else if (wire === 1) {
      pos += 8;
    } else if (wire === 5) {
      pos += 4;
    } else {
      break;
    }
  }
  return out;
}

function toBase64Url(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_");
}

function requestBodyBytes(details) {
  const raw = details.requestBody && details.requestBody.raw;
  if (!raw || !raw.length) return null;
  const parts = raw.filter((p) => p.bytes).map((p) => new Uint8Array(p.bytes));
  const total = parts.reduce((n, p) => n + p.length, 0);
  const all = new Uint8Array(total);
  let off = 0;
  for (const p of parts) {
    all.set(p, off);
    off += p.length;
  }
  return all;
}

// YouTube gzip-compresses some request bodies; the gzip magic number is 1f 8b.
async function bodyToText(bytes) {
  if (bytes.length > 2 && bytes[0] === 0x1f && bytes[1] === 0x8b) {
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
    return await new Response(stream).text();
  }
  return new TextDecoder().decode(bytes);
}

async function bodyToBytes(bytes) {
  if (bytes.length > 2 && bytes[0] === 0x1f && bytes[1] === 0x8b) {
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }
  return bytes;
}

const sentTokens = {};
function reportToken(kind, token, extra) {
  if (!token || !nativePort) return;
  if (sentTokens[kind] === token && !extra.force) return;
  sentTokens[kind] = token;
  try {
    nativePort.postMessage(Object.assign({ t: "poToken", kind, token }, extra));
  } catch (e) {
    /* ignore */
  }
}

if (browser.webRequest && browser.webRequest.onBeforeRequest) {
  // 1) Player requests: JSON body with serviceIntegrityDimensions.poToken and the visitor data.
  browser.webRequest.onBeforeRequest.addListener(
    (details) => {
      if (details.method !== "POST") return;
      const raw = requestBodyBytes(details); // only available synchronously
      if (!raw) return;
      (async () => {
        try {
          const json = JSON.parse(await bodyToText(raw));
          const token = json.serviceIntegrityDimensions && json.serviceIntegrityDimensions.poToken;
          const client = (json.context && json.context.client) || {};
          if (token) {
            reportToken("player", token, {
              visitorData: client.visitorData || "",
              clientName: client.clientName || "",
              clientVersion: client.clientVersion || "",
              videoId: json.videoId || "",
              force: true,
            });
          } else if (nativePort) {
            nativePort.postMessage({
              t: "net",
              note: "player request without poToken",
              client: (client.clientName || "") + " vdLen=" + (client.visitorData || "").length,
            });
          }
        } catch (e) {
          if (nativePort) {
            const head = Array.from(raw.subarray(0, 8)).map((x) => x.toString(16).padStart(2, "0")).join("");
            nativePort.postMessage({ t: "net", note: "player body not parsed (" + e + ") head=" + head, client: "" });
          }
        }
      })();
    },
    { urls: ["*://*.youtube.com/youtubei/v1/player*"] },
    ["requestBody"]
  );

  // 2) SABR streaming requests: protobuf body, streamer_context (field 19) carries the PO token.
  browser.webRequest.onBeforeRequest.addListener(
    (details) => {
      if (details.method !== "POST" || !/[?&]sabr=1/.test(details.url)) return;
      const raw = requestBodyBytes(details);
      if (!raw) return;
      (async () => {
        try {
          const bytes = await bodyToBytes(raw);
          const top = parseFields(bytes);
          const ctx = top.find((f) => f.field === 19 && f.wire === 2);
          let token = "";
          let inner = [];
          if (ctx) {
            inner = parseFields(ctx.bytes);
            const t = inner.find((f) => f.field === 2 && f.wire === 2);
            if (t) token = toBase64Url(t.bytes);
          }
          if (token) {
            reportToken("sabr", token, {
              fields: top.map((f) => f.field).join(","),
              innerFields: inner.map((f) => f.field).join(","),
            });
          } else if (nativePort) {
            nativePort.postMessage({
              t: "net",
              note: "sabr request without token",
              fields: top.map((f) => f.field).join(","),
            });
          }
        } catch (e) {
          /* ignore */
        }
      })();
    },
    { urls: ["*://*.googlevideo.com/videoplayback*"] },
    ["requestBody"]
  );
}

function connectNative() {
  try {
    nativePort = browser.runtime.connectNative(NATIVE_APP);
    nativePort.onMessage.addListener((msg) => {
      for (const p of contentPorts) {
        try {
          p.postMessage(msg);
        } catch (e) {
          contentPorts.delete(p);
        }
      }
    });
    nativePort.onDisconnect.addListener(() => {
      nativePort = null;
      setTimeout(connectNative, 1000);
    });
  } catch (e) {
    nativePort = null;
    setTimeout(connectNative, 2000);
  }
}

browser.runtime.onConnect.addListener((port) => {
  if (port.name !== "content") return;
  contentPorts.add(port);
  port.onDisconnect.addListener(() => contentPorts.delete(port));
  port.onMessage.addListener((msg) => {
    if (nativePort) {
      try {
        nativePort.postMessage(msg);
      } catch (e) {
        /* reconnect handled by onDisconnect */
      }
    }
  });
});

// Report the audio stream URLs the page itself requests. They already carry everything
// YouTube requires (session, proof-of-origin token), so the native player can reuse them.
if (browser.webRequest && browser.webRequest.onBeforeRequest) {
  browser.webRequest.onBeforeRequest.addListener(
    (details) => {
      if (!nativePort) return;
      // Streaming PO token passed as a URL parameter (non-SABR playback).
      const pot = /[?&]pot=([^&]+)/.exec(details.url);
      if (pot) reportToken("url", decodeURIComponent(pot[1]), {});
      const hasAudioMime = /[?&]mime=audio/.test(details.url);
      if (!hasAudioMime) {
        // Diagnostics only: SABR streaming requests carry no mime parameter.
        try {
          nativePort.postMessage({
            t: "net",
            sabr: /[?&]sabr=1/.test(details.url),
            mime: /[?&]mime=/.test(details.url),
            method: details.method,
          });
        } catch (e) {
          /* ignore */
        }
        return;
      }
      try {
        nativePort.postMessage({ t: "audioUrl", url: details.url });
      } catch (e) {
        /* reconnect handled by onDisconnect */
      }
    },
    { urls: ["*://*.googlevideo.com/videoplayback*"] }
  );
}

connectNative();
