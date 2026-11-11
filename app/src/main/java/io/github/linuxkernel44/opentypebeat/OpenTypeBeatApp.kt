package io.github.linuxkernel44.opentypebeat

import android.app.Application
import androidx.media3.common.util.UnstableApi
import io.github.linuxkernel44.opentypebeat.bridge.PlaybackCoordinator

@UnstableApi
class OpenTypeBeatApp : Application() {
    val coordinator: PlaybackCoordinator by lazy { PlaybackCoordinator(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)
    }

    companion object {
        lateinit var instance: OpenTypeBeatApp
            private set
    }
}
