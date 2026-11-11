package io.github.linuxkernel44.opentypebeat.extract

import android.util.Log

/** Picks and chains extractors according to the user's preference. */
object Extractors {
    private const val TAG = "Extractors"

    private val newPipe by lazy { NewPipeStreamExtractor() }
    private val ytDlp by lazy { YtDlpStreamExtractor() }

    fun chainFor(preference: String): List<StreamExtractor> = when (preference) {
        "newpipe" -> listOf(newPipe)
        "ytdlp" -> listOf(ytDlp)
        else -> listOf(newPipe, ytDlp)
    }

    /**
     * Tries each extractor in order, skipping the ones in [skip], and returns the first success.
     * Blocking; call from a background thread.
     */
    fun resolve(
        videoId: String,
        preference: String,
        preferLowBitrate: Boolean,
        skip: Set<String> = emptySet(),
    ): ResolvedStream {
        var last: Exception? = null
        for (extractor in chainFor(preference)) {
            if (extractor.name in skip) continue
            try {
                return extractor.resolve(videoId, preferLowBitrate)
            } catch (e: Exception) {
                Log.w(TAG, "${extractor.name} failed for $videoId: ${e.message}")
                last = e
            }
        }
        throw ExtractionException("No extractor could resolve $videoId", last)
    }
}
