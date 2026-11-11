package io.github.linuxkernel44.opentypebeat.player

import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A Player that mirrors the state of the web page's player (title, artist, artwork, position,
 * play/pause). It is what the media notification, lock screen, Bluetooth controls and the
 * in-app mini player talk to; every command is forwarded to the page, which stays the master.
 *
 * The audio itself comes either from the extracted stream (played by a hidden ExoPlayer that
 * follows the page) or, when extraction is not possible, from the page itself.
 */
@UnstableApi
class PageMirrorPlayer(
    private val sendCommand: (cmd: String, seconds: Double?) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var videoId: String? = null
    private var title: String = ""
    private var artist: String = ""
    private var artwork: String = ""
    private var playing = false
    private var positionMs = 0L
    private var durationMs = C.TIME_UNSET

    /** Must be called on the main thread. */
    fun update(
        id: String,
        title: String,
        artist: String,
        artwork: String,
        playing: Boolean,
        positionSeconds: Double,
        durationSeconds: Double,
    ) {
        this.videoId = id
        this.title = title
        this.artist = artist
        this.artwork = artwork
        this.playing = playing
        this.positionMs = (positionSeconds * 1000).toLong().coerceAtLeast(0)
        this.durationMs = if (durationSeconds > 0) (durationSeconds * 1000).toLong() else C.TIME_UNSET
        invalidateState()
    }

    override fun getState(): State {
        val id = videoId
        val builder = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_PREPARE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_TIMELINE,
                    )
                    .build(),
            )
        if (id == null) {
            return builder.setPlaybackState(Player.STATE_IDLE).build()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(artwork.takeIf { it.isNotEmpty() }?.toUri())
            .build()
        val item = MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
        val data = MediaItemData.Builder(id)
            .setMediaItem(item)
            .setMediaMetadata(metadata)
            .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000)
            .setIsSeekable(durationMs != C.TIME_UNSET)
            .build()
        return builder
            .setPlaylist(listOf(data))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(PositionSupplier.getExtrapolating(positionMs, if (playing) 1f else 0f))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady // optimistic, the page confirms within a moment
        sendCommand(if (playWhenReady) "play" else "pause", null)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (positionMs != C.TIME_UNSET) {
            this.positionMs = positionMs
            sendCommand("seek", positionMs / 1000.0)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        playing = false
        sendCommand("pause", null)
        return Futures.immediateVoidFuture()
    }
}
