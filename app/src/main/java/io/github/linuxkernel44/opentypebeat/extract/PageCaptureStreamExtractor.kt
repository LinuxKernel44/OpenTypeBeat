package io.github.linuxkernel44.opentypebeat.extract

import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Reuses the audio stream URL that the web page itself requested from googlevideo.com.
 *
 * Those URLs are already authorised for the current session (cookies, proof-of-origin token,
 * signature deciphering), so this works whenever the page itself can play the video — including
 * when a signed-in Google account is needed. The background script of the bridge extension
 * reports every `videoplayback` request with an audio mime type; they are matched to the current
 * track by their `dur` parameter and request time.
 */
class PageCaptureStreamExtractor : StreamExtractor {
    override val name = "Page capture"

    override fun resolve(hint: TrackHint): ResolvedStream {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (true) {
            best(hint)?.let { return it }
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(200)
        }
        throw ExtractionException("The page did not request an audio stream in time")
    }

    private fun best(hint: TrackHint): ResolvedStream? {
        val candidates = captured.filter { it.at >= hint.startedAtMs - SLACK_MS }
            .filter { hint.durationSeconds <= 0 || it.durationSeconds <= 0 || abs(it.durationSeconds - hint.durationSeconds) < 2.0 }
        val pick = (if (hint.preferLowBitrate) candidates.minByOrNull { it.bitrate } else candidates.maxByOrNull { it.at })
            ?: return null
        return ResolvedStream(pick.url, pick.mime, pick.bitrate, name)
    }

    private data class Captured(
        val url: String,
        val mime: String?,
        val durationSeconds: Double,
        val bitrate: Int,
        val at: Long,
    )

    companion object {
        private const val WAIT_MS = 5000L
        private const val SLACK_MS = 2500L
        private const val MAX_KEPT = 24

        private val captured = CopyOnWriteArrayList<Captured>()

        /** Called with every audio `videoplayback` URL the page requests. */
        fun onAudioUrl(rawUrl: String) {
            val uri = Uri.parse(rawUrl)
            val clean = stripChunking(uri)
            val mime = uri.getQueryParameter("mime")
            val dur = uri.getQueryParameter("dur")?.toDoubleOrNull() ?: 0.0
            val clen = uri.getQueryParameter("clen")?.toLongOrNull() ?: 0L
            val bitrate = if (dur > 0 && clen > 0) ((clen * 8) / dur).toInt() else 0
            val itag = uri.getQueryParameter("itag")
            // The page re-requests chunks of the same stream; keep one entry per itag + duration.
            captured.removeAll { it.durationSeconds == dur && it.url.contains("itag=$itag") }
            captured.add(Captured(clean, mime, dur, bitrate, System.currentTimeMillis()))
            while (captured.size > MAX_KEPT) captured.removeAt(0)
        }

        /** Removes the per-chunk parameters so the player can request the whole stream itself. */
        private fun stripChunking(uri: Uri): String {
            val drop = setOf("range", "rn", "rbuf")
            val builder = uri.buildUpon().clearQuery()
            for (name in uri.queryParameterNames) {
                if (name in drop) continue
                for (v in uri.getQueryParameters(name)) builder.appendQueryParameter(name, v)
            }
            return builder.build().toString()
        }
    }
}
