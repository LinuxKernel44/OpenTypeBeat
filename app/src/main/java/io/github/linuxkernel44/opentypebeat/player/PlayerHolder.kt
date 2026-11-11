package io.github.linuxkernel44.opentypebeat.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.linuxkernel44.opentypebeat.OpenTypeBeatApp
import okhttp3.OkHttpClient

/**
 * Process-wide players:
 *  - [get]: the ExoPlayer that plays the extracted audio stream (not exposed to the UI),
 *  - [mirror]: the Player that mirrors the web page and backs the MediaSession and the UI.
 */
@UnstableApi
object PlayerHolder {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var mirror: PageMirrorPlayer? = null

    fun get(context: Context): ExoPlayer =
        player ?: synchronized(this) {
            player ?: build(context.applicationContext).also { player = it }
        }

    fun mirror(@Suppress("UNUSED_PARAMETER") context: Context): PageMirrorPlayer =
        mirror ?: synchronized(this) {
            mirror ?: PageMirrorPlayer { cmd, seconds ->
                val coordinator = OpenTypeBeatApp.instance.coordinator
                if (seconds != null) coordinator.sendCommand(cmd, "t" to seconds) else coordinator.sendCommand(cmd)
            }.also { mirror = it }
        }

    private fun build(context: Context): ExoPlayer {
        val http = OkHttpDataSource.Factory(OkHttpClient.Builder().build())
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Origin" to "https://music.youtube.com",
                    "Referer" to "https://music.youtube.com/",
                ),
            )
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }
}
