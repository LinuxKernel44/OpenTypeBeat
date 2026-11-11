"""yt-dlp bridge used by the Kotlin YtDlpStreamExtractor (through Chaquopy)."""

import json

import yt_dlp


def resolve(video_id, prefer_low):
    """Return a dict describing a directly playable audio stream for the video."""
    opts = {
        "format": "worstaudio[protocol^=http]/worstaudio" if prefer_low
        else "bestaudio[protocol^=http]/bestaudio",
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "socket_timeout": 20,
        # Clients that do not need a JavaScript runtime or PO tokens come first.
        "extractor_args": {"youtube": {"player_client": ["android_vr", "tv", "web_safari"]}},
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info("https://www.youtube.com/watch?v=" + video_id, download=False)
    url = info.get("url")
    if not url:
        raise RuntimeError("yt-dlp returned no playable audio URL")
    return json.dumps(
        {
            "url": url,
            "mime": "audio/" + (info.get("ext") or "mp4"),
            "abr": int(info.get("abr") or 0),
        }
    )
