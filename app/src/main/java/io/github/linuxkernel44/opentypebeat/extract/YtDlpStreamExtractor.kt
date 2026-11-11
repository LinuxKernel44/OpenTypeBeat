package io.github.linuxkernel44.opentypebeat.extract

/** Placeholder until the embedded Python runtime is wired up. */
class YtDlpStreamExtractor : StreamExtractor {
    override val name = "yt-dlp"

    override fun resolve(videoId: String, preferLowBitrate: Boolean): ResolvedStream =
        throw ExtractionException("yt-dlp is not available in this build")
}
