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

/** Turns a YouTube video id into a direct audio stream URL. */
interface StreamExtractor {
    val name: String

    /** Blocking; call from a background thread. */
    fun resolve(videoId: String, preferLowBitrate: Boolean): ResolvedStream
}

class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
