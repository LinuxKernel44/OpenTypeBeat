package io.github.linuxkernel44.opentypebeat.extract

import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.github.linuxkernel44.opentypebeat.OpenTypeBeatApp
import org.json.JSONObject

/** Resolves audio streams with yt-dlp running in an embedded Python (Chaquopy). */
class YtDlpStreamExtractor : StreamExtractor {
    override val name = "yt-dlp"

    override fun resolve(hint: TrackHint): ResolvedStream {
        try {
            val json = JSONObject(module().callAttr("resolve", hint.videoId, hint.preferLowBitrate).toString())
            return ResolvedStream(
                url = json.getString("url"),
                mimeType = json.optString("mime").ifEmpty { null },
                bitrate = json.optInt("abr") * 1000,
                source = name,
            )
        } catch (e: PyException) {
            throw ExtractionException("yt-dlp failed: ${e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }}", e)
        }
    }

    private fun module() = Python.getInstance().getModule("otb_ytdlp")

    companion object {
        init {
            if (!Python.isStarted()) Python.start(AndroidPlatform(OpenTypeBeatApp.instance))
        }
    }
}
