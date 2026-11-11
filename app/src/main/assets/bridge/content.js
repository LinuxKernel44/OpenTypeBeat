// OpenTypeBeat page bridge.
//
// The page keeps playing a *muted* video, which acts as the master timeline
// (so SponsorBlock skips, queue auto-advance, etc. keep working). This script
// reports the playback state to the native app, which plays the extracted audio
// stream in a real audio player and stays in sync with this timeline.
"use strict";

(() => {
  if (window.__otbBridgeLoaded) return;
  window.__otbBridgeLoaded = true;

  const port = browser.runtime.connect({ name: "content" });
  let pageAudio = false; // when true, the page keeps its own audio (fallback)
  let lastSent = "";

  function playerApi() {
    const p = document.getElementById("movie_player");
    return p && p.wrappedJSObject ? p.wrappedJSObject : null;
  }

  function videoData() {
    try {
      const api = playerApi();
      if (api && typeof api.getVideoData === "function") {
        const d = api.getVideoData();
        if (d && d.video_id) return d;
      }
    } catch (e) {
      /* ignore */
    }
    return null;
  }

  function idFromUrl() {
    const u = new URL(location.href);
    const v = u.searchParams.get("v");
    if (v) return v;
    const m = u.pathname.match(/^\/(?:shorts|live|embed)\/([\w-]{11})/);
    return m ? m[1] : null;
  }

  function mainVideo() {
    return (
      document.querySelector("video.html5-main-video") ||
      document.querySelector("#movie_player video") ||
      document.querySelector("video")
    );
  }

  function mediaSessionMeta() {
    try {
      const m = navigator.mediaSession && navigator.mediaSession.metadata;
      if (!m) return null;
      const art = m.artwork && m.artwork.length ? m.artwork[m.artwork.length - 1].src : "";
      return { title: m.title || "", artist: m.artist || "", art };
    } catch (e) {
      return null;
    }
  }

  function snapshot() {
    const video = mainVideo();
    if (!video) return null;
    const data = videoData();
    const id = (data && data.video_id) || idFromUrl();
    if (!id) return null;
    const player = document.getElementById("movie_player");
    const ad = !!(player && player.classList.contains("ad-showing"));
    if (!pageAudio) {
      video.muted = true;
    } else if (video.muted) {
      video.muted = false;
    }
    const ms = mediaSessionMeta();
    return {
      t: "state",
      id,
      playing: !video.paused && !video.ended && video.readyState > 2,
      ended: video.ended,
      pos: Number.isFinite(video.currentTime) ? video.currentTime : 0,
      dur: Number.isFinite(video.duration) ? video.duration : 0,
      ad,
      title: (ms && ms.title) || (data && data.title) || document.title,
      artist: (ms && ms.artist) || (data && data.author) || "",
      art: (ms && ms.art) || "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg",
    };
  }

  function report(force) {
    const s = snapshot();
    if (!s) return;
    // Throttle identical updates, but always send when something besides position changed.
    const key = [s.id, s.playing, s.ended, s.ad, s.title, s.artist, Math.round(s.pos)].join("|");
    if (!force && key === lastSent) return;
    lastSent = key;
    try {
      port.postMessage(s);
    } catch (e) {
      /* port closed */
    }
  }

  function clickFirst(selectors) {
    for (const sel of selectors) {
      const el = document.querySelector(sel);
      if (el) {
        el.click();
        return true;
      }
    }
    return false;
  }

  port.onMessage.addListener((msg) => {
    const video = mainVideo();
    switch (msg && msg.cmd) {
      case "play":
        if (video) video.play();
        break;
      case "pause":
        if (video) video.pause();
        break;
      case "seek":
        if (video && Number.isFinite(msg.t)) video.currentTime = msg.t;
        break;
      case "next":
        if (!clickFirst([".next-button", ".ytp-next-button"])) {
          const api = playerApi();
          if (api && api.nextVideo) api.nextVideo();
        }
        break;
      case "previous":
        if (!clickFirst([".previous-button", ".ytp-prev-button"])) {
          const api = playerApi();
          if (api && api.previousVideo) api.previousVideo();
          else if (video) video.currentTime = 0;
        }
        break;
      case "pageAudio":
        pageAudio = !!msg.value;
        if (video) video.muted = !pageAudio;
        break;
    }
    setTimeout(() => report(true), 150);
  });

  const events = ["play", "pause", "playing", "seeked", "ended", "loadedmetadata", "volumechange", "emptied"];
  const bound = new WeakSet();
  function bind() {
    const v = mainVideo();
    if (!v || bound.has(v)) return;
    bound.add(v);
    for (const ev of events) v.addEventListener(ev, () => report(true), { passive: true });
  }

  setInterval(() => {
    bind();
    report(false);
  }, 1000);
  document.addEventListener("yt-navigate-finish", () => setTimeout(() => report(true), 300));
  bind();
  report(true);
})();
