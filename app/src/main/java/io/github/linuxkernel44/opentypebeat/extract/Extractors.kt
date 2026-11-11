package io.github.linuxkernel44.opentypebeat.extract

import android.util.Log

/** Picks and chains extractors according to the user's preference. */
object Extractors {
    private const val TAG = "Extractors"

    private val page by lazy { PageCaptureStreamExtractor() }
    private val newPipe by lazy { NewPipeStreamExtractor() }
    private val ytDlp by lazy { YtDlpStreamExtractor() }

    fun chainFor(preference: String): List<StreamExtractor> = when (preference) {
        "off" -> emptyList()
        "page" -> listOf(page)
        "newpipe" -> listOf(newPipe)
        "ytdlp" -> listOf(ytDlp)
        else -> listOf(page, newPipe, ytDlp)
    }

    /**
     * Tries each extractor in order, skipping the ones in [skip], and returns the first success.
     * Blocking; call from a background thread.
     */
    fun resolve(hint: TrackHint, preference: String, skip: Set<String> = emptySet()): ResolvedStream {
        var last: Throwable? = null
        for (extractor in chainFor(preference)) {
            if (extractor.name in skip) continue
            try {
                return extractor.resolve(hint)
            } catch (e: Throwable) {
                // Also catches linkage / memory errors from the extractor libraries (Rhino, Python):
                // a broken extractor must never take the whole app down.
                Log.w(TAG, "${extractor.name} failed for ${hint.videoId}: ${e.javaClass.simpleName}: ${e.message}", e)
                last = e
            }
        }
        throw ExtractionException("No extractor could resolve ${hint.videoId}", last)
    }
}
