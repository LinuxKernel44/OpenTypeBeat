// Relays messages between the page content scripts and the native OpenTypeBeat app.
"use strict";

const NATIVE_APP = "opentypebeat";
const contentPorts = new Set();
let nativePort = null;

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
      if (!/[?&]mime=audio/.test(details.url) || !nativePort) return;
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
