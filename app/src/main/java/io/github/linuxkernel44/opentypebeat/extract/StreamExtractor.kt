package io.github.linuxkernel44.opentypebeat.extract

/** A directly playable audio stream resolved from a YouTube video id. */
data class ResolvedStream(
    val url: String,
    val mimeType: String?,
    /** Average bitrate in bits per second, or 0 when unknown. */
    val bitrate: Int,
    /** Name of the extractor that produced this stream. */
    val source: String,
)

/** What the caller already knows about the track being resolved. */
data class TrackHint(
    val videoId: String,
    /** Duration reported by the page in seconds, or 0 when unknown. */
    val durationSeconds: Double,
    /** Wall-clock time (ms) at which the page switched to this track. */
    val startedAtMs: Long,
    val preferLowBitrate: Boolean,
)

/** Turns a YouTube video id into a direct audio stream URL. */
interface StreamExtractor {
    val name: String

    /** Blocking; call from a background thread. */
    fun resolve(hint: TrackHint): ResolvedStream
}

class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
