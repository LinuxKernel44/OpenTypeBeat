package io.github.linuxkernel44.opentypebeat.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.linuxkernel44.opentypebeat.MainActivity
import io.github.linuxkernel44.opentypebeat.OpenTypeBeatApp

/**
 * Foreground media service: exposes the shared player as a MediaSession so the system
 * shows a media notification and lock-screen / Bluetooth controls.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val coordinator = (application as OpenTypeBeatApp).coordinator
        val player = PageControlledPlayer(
            PlayerHolder.mirror(this),
            onNext = { coordinator.sendCommand("next") },
            onPrevious = { coordinator.sendCommand("previous") },
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    /**
     * The queue lives in the web page, so next/previous are forwarded to it instead of being
     * handled by a native playlist.
     */
    private class PageControlledPlayer(
        player: Player,
        private val onNext: () -> Unit,
        private val onPrevious: () -> Unit,
    ) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun seekToNext() = onNext()
        override fun seekToNextMediaItem() = onNext()
        override fun seekToPrevious() = onPrevious()
        override fun seekToPreviousMediaItem() = onPrevious()
        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true
    }
}
