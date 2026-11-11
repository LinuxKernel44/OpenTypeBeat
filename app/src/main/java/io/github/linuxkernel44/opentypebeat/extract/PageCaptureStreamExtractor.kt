package io.github.linuxkernel44.opentypebeat.extract

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Reuses the audio stream URL that the web page itself requested from googlevideo.com.
 *
 * Those URLs are already authorised for the current session (cookies, proof-of-origin token,
 * signature deciphering), so this works whenever the page itself can play the video — including
 * when a signed-in Google account is needed. The background script of the bridge extension
 * reports every `videoplayback` request with an audio mime type; they are matched to the current
 * track by their `dur` parameter and request time.
 *
 * The page asks for chunks in YouTube's own wrapper format (UMP). Before a URL is handed to the
 * player it is turned into a plain progressive URL and probed: only a response that really is
 * audio is accepted.
 */
class PageCaptureStreamExtractor : StreamExtractor {
    override val name = "Page capture"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun resolve(hint: TrackHint): ResolvedStream {
        val deadline = System.currentTimeMillis() + WAIT_MS
        var candidate = best(hint)
        while (candidate == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            candidate = best(hint)
        }
        val pick = candidate ?: throw ExtractionException("The page did not request an audio stream in time")

        val failures = mutableListOf<String>()
        for ((label, url) in variants(pick.url)) {
            val probe = probe(url)
            Log.i(TAG, "probe variant=$label code=${probe.code} type=${probe.type} head=${probe.head}")
            if (probe.accepted) return ResolvedStream(url, probe.type, pick.bitrate, name)
            failures += "$label: ${probe.code} ${probe.type}"
        }
        throw ExtractionException("The captured stream was rejected (${failures.joinToString("; ")})")
    }

    private fun best(hint: TrackHint): Captured? {
        val candidates = captured.filter { it.at >= hint.startedAtMs - SLACK_MS }
            .filter { hint.durationSeconds <= 0 || it.durationSeconds <= 0 || abs(it.durationSeconds - hint.durationSeconds) < 2.0 }
        return if (hint.preferLowBitrate) candidates.minByOrNull { it.bitrate } else candidates.maxByOrNull { it.at }
    }

    private data class Captured(
        val url: String,
        val mime: String?,
        val durationSeconds: Double,
        val bitrate: Int,
        val at: Long,
    )

    private class Probe(val code: Int, val type: String?, val head: String, val accepted: Boolean)

    private fun probe(url: String): Probe {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1023")
                .header("User-Agent", USER_AGENT)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            http.newCall(request).execute().use { response ->
                val type = response.header("Content-Type")?.substringBefore(';')?.trim()
                val buf = ByteArray(16)
                val n = response.body?.byteStream()?.read(buf) ?: 0
                val head = buf.take(n.coerceAtLeast(0)).joinToString("") { "%02x".format(it) }
                val ok = (response.code == 200 || response.code == 206) &&
                    type != null && (type.startsWith("audio/") || type.startsWith("video/"))
                Probe(response.code, type, head, ok)
            }
        } catch (e: Exception) {
            Probe(-1, e.javaClass.simpleName, "", false)
        }
    }

    /** Plain progressive variants of a captured URL, most likely first. */
    private fun variants(raw: String): List<Pair<String, String>> {
        val uri = Uri.parse(raw)
        return listOf(
            "plain" to rebuild(uri, setOf("range", "rn", "rbuf", "ump", "srfvp")),
            "keep-ump-flags" to rebuild(uri, setOf("range", "rn", "rbuf")),
        ).distinctBy { it.second }
    }

    private fun rebuild(uri: Uri, drop: Set<String>): String {
        val builder = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name in drop) continue
            for (v in uri.getQueryParameters(name)) builder.appendQueryParameter(name, v)
        }
        return builder.build().toString()
    }

    companion object {
        private const val TAG = "PageCapture"
        private const val WAIT_MS = 5000L
        private const val SLACK_MS = 2500L
        private const val MAX_KEPT = 24
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        private val captured = CopyOnWriteArrayList<Captured>()

        /** Called with every audio `videoplayback` URL the page requests. */
        fun onAudioUrl(rawUrl: String) {
            val uri = Uri.parse(rawUrl)
            val mime = uri.getQueryParameter("mime")
            val dur = uri.getQueryParameter("dur")?.toDoubleOrNull() ?: 0.0
            val clen = uri.getQueryParameter("clen")?.toLongOrNull() ?: 0L
            val bitrate = if (dur > 0 && clen > 0) ((clen * 8) / dur).toInt() else 0
            val itag = uri.getQueryParameter("itag")
            // The page re-requests chunks of the same stream; keep one entry per itag + duration.
            captured.removeAll { it.durationSeconds == dur && it.url.contains("itag=$itag") }
            captured.add(Captured(rawUrl, mime, dur, bitrate, System.currentTimeMillis()))
            while (captured.size > MAX_KEPT) captured.removeAt(0)
        }
    }
}
