package io.github.linuxkernel44.opentypebeat

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Typed access to the app settings (see res/xml/preferences.xml). */
class Prefs(context: Context) {
    private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    /** "auto" (NewPipeExtractor, then yt-dlp), "newpipe" or "ytdlp". */
    val extractor: String get() = sp.getString(KEY_EXTRACTOR, "auto") ?: "auto"

    /** "best" or "low" (data saver). */
    val audioQuality: String get() = sp.getString(KEY_AUDIO_QUALITY, "best") ?: "best"

    /** "music" or "youtube". */
    val startPage: String get() = sp.getString(KEY_START_PAGE, "music") ?: "music"

    var desktopSite: Boolean
        get() = sp.getBoolean(KEY_DESKTOP, false)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP, value).apply()

    companion object {
        const val KEY_EXTRACTOR = "extractor"
        const val KEY_AUDIO_QUALITY = "audio_quality"
        const val KEY_START_PAGE = "start_page"
        const val KEY_DESKTOP = "desktop_site"
        const val KEY_REINSTALL_EXTENSIONS = "reinstall_extensions"
    }
}
