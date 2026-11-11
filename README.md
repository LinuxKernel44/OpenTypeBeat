# OpenTypeBeat

A free, open-source Android client for **YouTube** and **YouTube Music**, built for digging through
*type beat* content. The whole web experience runs inside **GeckoView** (Firefox's engine) with the
**uBlock Origin** and **SponsorBlock** extensions, while the audio is played by a real native player
with a media notification, lock-screen controls and background playback.

## Features

- Full YouTube / YouTube Music in GeckoView — no Google Play Services, no proprietary blobs.
- **uBlock Origin** and **SponsorBlock** installed from addons.mozilla.org on first launch.
- Sign in with your Google account (cookies persist in the Gecko profile).
- **Native audio playback**: the audio stream of the current video is extracted on the fly and played
  with Media3/ExoPlayer — pause, seek bar, next/previous, notification, headset and Bluetooth controls,
  background playback.
- Two extractors: **NewPipeExtractor** (default) with **yt-dlp** as a fallback, selectable in settings.
- One-tap **beat style chips** (Trap, Drill, Boom Bap, Lo-fi, R&B, Afrobeat, …) that search
  "`<style> type beat`".
- Opens `youtube.com`, `music.youtube.com` and `youtu.be` links.

## How it works

```
 GeckoView page (YouTube / YT Music)            Native app
 ┌─────────────────────────────────┐   port    ┌──────────────────────────────┐
 │ muted <video> = master timeline │ ───────►  │ PlaybackCoordinator          │
 │ uBlock Origin, SponsorBlock     │           │  ├─ Extractors (NewPipe/yt-dlp)
 │ bridge content script           │ ◄───────  │  └─ ExoPlayer + MediaSession │
 └─────────────────────────────────┘ commands  └──────────────────────────────┘
```

The page keeps playing a **muted** video. It owns the queue, ad blocking and SponsorBlock skipping.
A built-in WebExtension reports the current video and position to the app, which extracts the audio
stream and plays it natively, staying in sync with the page. Play/pause/seek/next from the
notification or lock screen are sent back to the page. If extraction ever fails, the page's own
audio is switched back on so you are never left in silence.

## Building

Requirements: JDK 17, the Android SDK (platform 37.2, build-tools 36+), Python 3 (for the bundled
yt-dlp, built through Chaquopy).

```bash
./gradlew assembleDebug            # debug APK
./gradlew assembleRelease          # release APK (signed if keystore.properties exists)
```

For an emulator build pass the ABI: `./gradlew assembleDebug -PabiFilters=x86_64`.

### Release signing

Create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Disclaimer

OpenTypeBeat is an independent project and is not affiliated with or endorsed by Google or YouTube.
Make sure your use complies with the terms of the services you access.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE). Third-party components keep their own licenses
(GeckoView: MPL-2.0, NewPipeExtractor: GPL-3.0, yt-dlp: Unlicense, Media3: Apache-2.0,
uBlock Origin: GPL-3.0, SponsorBlock: LGPL-3.0).
