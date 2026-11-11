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
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (applyingRemote) return
            lastNativeChangeAt = System.currentTimeMillis()
            sendCommand(if (playWhenReady) "play" else "pause")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (applyingRemote || reason != Player.DISCONTINUITY_REASON_SEEK) return
            lastNativeChangeAt = System.currentTimeMillis()
            sendCommand("seek", "t" to newPosition.positionMs / 1000.0)
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
            failedExtractors.clear()
            loadTrack(s.id, retry = false)
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
                        videoId = videoId,
                        preference = prefs.extractor,
                        preferLowBitrate = prefs.audioQuality == "low",
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
                sendCommand("pageAudio", "value" to false)
                applyRemote {
                    ensureServiceRunning()
                    player.setMediaItem(item, ((s?.pos ?: 0.0) * 1000).toLong())
                    player.prepare()
                    player.playWhenReady = s?.playing ?: true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed for $videoId (retry=$retry)", e)
                // Fall back to the page's own audio so the user is never left in silence.
                sendCommand("pageAudio", "value" to true)
                applyRemote { player.pause() }
                onMessage?.invoke("Audio extraction failed, using the page's audio: ${e.message}")
            }
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
