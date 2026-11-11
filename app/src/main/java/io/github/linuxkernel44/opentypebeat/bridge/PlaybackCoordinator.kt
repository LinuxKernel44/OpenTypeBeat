package io.github.linuxkernel44.opentypebeat.bridge

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.linuxkernel44.opentypebeat.Prefs
import io.github.linuxkernel44.opentypebeat.extract.Extractors
import io.github.linuxkernel44.opentypebeat.extract.PageCaptureStreamExtractor
import io.github.linuxkernel44.opentypebeat.extract.TrackHint
import io.github.linuxkernel44.opentypebeat.player.PlaybackService
import io.github.linuxkernel44.opentypebeat.player.PlayerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import kotlin.math.abs

/**
 * Keeps the native audio player in sync with the (muted) page player.
 *
 * The page is the master: it owns the queue, ad blocking and SponsorBlock skipping. Every time
 * it reports a new video, the audio stream is extracted and played natively; play/pause/seek
 * done on the native side (notification, lock screen, headset) are forwarded back to the page.
 */
@UnstableApi
class PlaybackCoordinator(private val app: Application) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { Prefs(app) }
    private val player get() = PlayerHolder.get(app)

    private var port: WebExtension.Port? = null
    private var currentId: String? = null
    private var latest: PageState? = null
    private var extractJob: Job? = null
    private var applyingRemote = false
    private var lastNativeChangeAt = 0L
    private var lastDriftSeekAt = 0L
    private var failedExtractors = mutableSetOf<String>()
    private var consecutiveFailures = 0
    private var tracksSeen = 0
    private var failureShown = false
    private var netEvents = 0

    /** UI callbacks (main thread). */
    var onMessage: ((String) -> Unit)? = null

    data class PageState(
        val id: String,
        val playing: Boolean,
        val ended: Boolean,
        val pos: Double,
        val dur: Double,
        val ad: Boolean,
        val title: String,
        val artist: String,
        val art: String,
    )

    private val playerListener = object : Player.Listener {
        // The hidden ExoPlayer only reacts to the system (audio focus loss, headphones unplugged);
        // user commands go through the PageMirrorPlayer straight to the page.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (applyingRemote) return
            lastNativeChangeAt = System.currentTimeMillis()
            sendCommand(if (playWhenReady) "play" else "pause")
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Player error: ${error.errorCodeName}", error)
            val id = currentId ?: return
            // Stream URLs expire or get rejected; try again, skipping the extractor that failed.
            val last = lastSource
            if (last != null) failedExtractors += last
            loadTrack(id, retry = true)
        }
    }

    private var lastSource: String? = null
    private var trackStartedAt = 0L

    init {
        main.post { player.addListener(playerListener) }
    }

    // ---- Messages from / to the page ----------------------------------------------------------

    fun onPortConnected(newPort: WebExtension.Port) {
        port = newPort
        newPort.setDelegate(object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                val json = message as? JSONObject ?: return
                main.post { handleMessage(json) }
            }

            override fun onDisconnect(port: WebExtension.Port) {
                if (this@PlaybackCoordinator.port === port) this@PlaybackCoordinator.port = null
            }
        })
    }

    fun sendCommand(cmd: String, vararg extras: Pair<String, Any>) {
        val json = JSONObject().put("cmd", cmd)
        for ((k, v) in extras) json.put(k, v)
        try {
            port?.postMessage(json)
        } catch (e: Exception) {
            Log.w(TAG, "Could not send $cmd to page", e)
        }
    }

    private fun handleMessage(json: JSONObject) {
        when (json.optString("t")) {
            "audioUrl" -> {
                PageCaptureStreamExtractor.onAudioUrl(json.optString("url"))
                return
            }
            "net" -> {
                // Diagnostics: googlevideo requests that carry no mime parameter (e.g. SABR streaming).
                if (++netEvents % 25 == 1) {
                    Log.i(TAG, "page media request: sabr=${json.optBoolean("sabr")} mime=${json.optBoolean("mime")} method=${json.optString("method")}")
                }
                return
            }
        }
        if (json.optString("t") != "state") return
        val state = PageState(
            id = json.optString("id"),
            playing = json.optBoolean("playing"),
            ended = json.optBoolean("ended"),
            pos = json.optDouble("pos", 0.0),
            dur = json.optDouble("dur", 0.0),
            ad = json.optBoolean("ad"),
            title = json.optString("title"),
            artist = json.optString("artist"),
            art = json.optString("art"),
        )
        if (state.id.isEmpty()) return
        latest = state
        PlayerHolder.mirror(app).update(
            id = state.id,
            title = state.title,
            artist = state.artist,
            artwork = state.art,
            playing = state.playing && !state.ad,
            positionSeconds = state.pos,
            durationSeconds = state.dur,
        )
        onPageState(state)
    }

    // ---- Sync logic ---------------------------------------------------------------------------

    private fun onPageState(s: PageState) {
        if (s.ad) {
            // Never play ad audio natively; the blocker normally removes ads before this.
            applyRemote { player.pause() }
            return
        }
        if (s.id != currentId) {
            currentId = s.id
            trackStartedAt = System.currentTimeMillis()
            failedExtractors.clear()
            tracksSeen++
            ensureServiceRunning()
            // After repeated failures stop delaying every track by the extraction attempts and
            // let the page play its own audio; retry extraction now and then.
            val skip = prefs.extractor == "off" || (consecutiveFailures >= 2 && tracksSeen % 6 != 0)
            if (skip) {
                extractJob?.cancel()
                usePageAudio()
            } else {
                // Keep the page audible while the stream is being resolved (no silent start).
                sendCommand("pageAudio", "value" to true)
                loadTrack(s.id, retry = false)
            }
            return
        }
        if (extractJob?.isActive == true) return // still resolving the stream
        val recentlyNative = System.currentTimeMillis() - lastNativeChangeAt < 2000
        applyRemote {
            if (!recentlyNative && player.playWhenReady != s.playing && !s.ended) {
                player.playWhenReady = s.playing
            }
            val now = System.currentTimeMillis()
            if (!recentlyNative && player.playbackState != Player.STATE_IDLE &&
                abs(player.currentPosition / 1000.0 - s.pos) > DRIFT_SECONDS &&
                now - lastDriftSeekAt > 3000
            ) {
                lastDriftSeekAt = now
                player.seekTo((s.pos * 1000).toLong())
            }
        }
    }

    private fun loadTrack(videoId: String, retry: Boolean) {
        extractJob?.cancel()
        val meta = latest?.takeIf { it.id == videoId }
        extractJob = scope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) {
                    Extractors.resolve(
                        hint = TrackHint(
                            videoId = videoId,
                            durationSeconds = latest?.takeIf { it.id == videoId }?.dur ?: 0.0,
                            startedAtMs = trackStartedAt,
                            preferLowBitrate = prefs.audioQuality == "low",
                        ),
                        preference = prefs.extractor,
                        skip = failedExtractors,
                    )
                }
                if (currentId != videoId) return@launch // user moved on while resolving
                lastSource = resolved.source
                val s = latest?.takeIf { it.id == videoId } ?: meta
                val item = MediaItem.Builder()
                    .setMediaId(videoId)
                    .setUri(resolved.url.toUri())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(s?.title)
                            .setArtist(s?.artist)
                            .setArtworkUri(s?.art?.takeIf { it.isNotEmpty() }?.toUri())
                            .build(),
                    )
                    .build()
                consecutiveFailures = 0
                sendCommand("pageAudio", "value" to false)
                applyRemote {
                    player.setMediaItem(item, ((s?.pos ?: 0.0) * 1000).toLong())
                    player.prepare()
                    player.playWhenReady = s?.playing ?: true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed for $videoId (retry=$retry)", e)
                consecutiveFailures++
                // Fall back to the page's own audio so the user is never left in silence.
                usePageAudio()
                if (!failureShown) {
                    failureShown = true
                    onMessage?.invoke(
                        "Could not extract the audio stream (YouTube blocks it), so the page's own audio is used. " +
                            "Media controls still work. You can pick \"Page audio only\" in Settings.",
                    )
                }
            }
        }
    }

    /** The page plays its own audio; the native player stays idle. */
    private fun usePageAudio() {
        sendCommand("pageAudio", "value" to true)
        applyRemote {
            player.stop()
            player.clearMediaItems()
        }
    }

    private fun ensureServiceRunning() {
        ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
    }

    private inline fun applyRemote(block: () -> Unit) {
        applyingRemote = true
        try {
            block()
        } finally {
            applyingRemote = false
        }
    }

    companion object {
        private const val TAG = "PlaybackCoordinator"
        private const val DRIFT_SECONDS = 2.0
    }
}
