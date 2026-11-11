"""yt-dlp bridge used by the Kotlin YtDlpStreamExtractor (through Chaquopy)."""

import json

import yt_dlp


def resolve(video_id, prefer_low, visitor_data="", player_token="", gvs_token=""):
    """Return a JSON string describing a directly playable audio stream for the video.

    The PO tokens (when given) are the ones the web page itself used for the same visitor.
    """
    youtube_args = {}
    if visitor_data and (player_token or gvs_token):
        # Clients that accept PO tokens, with the tokens the page captured.
        youtube_args["player_client"] = ["web", "mweb", "tv"]
        youtube_args["visitor_data"] = [visitor_data]
        tokens = []
        for client in ("web", "mweb"):
            if gvs_token:
                tokens.append("%s.gvs+%s" % (client, gvs_token))
            if player_token:
                tokens.append("%s.player+%s" % (client, player_token))
        youtube_args["po_token"] = tokens
    else:
        # Clients that do not need a JavaScript runtime or PO tokens come first.
        youtube_args["player_client"] = ["android_vr", "tv", "web_safari"]

    opts = {
        "format": "worstaudio[protocol^=http]/worstaudio" if prefer_low
        else "bestaudio[protocol^=http]/bestaudio",
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "socket_timeout": 20,
        "extractor_args": {"youtube": youtube_args},
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
