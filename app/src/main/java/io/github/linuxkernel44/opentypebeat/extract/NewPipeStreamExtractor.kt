package io.github.linuxkernel44.opentypebeat.extract

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Resolves audio streams with NewPipeExtractor (pure JVM, no Python needed). */
class NewPipeStreamExtractor : StreamExtractor {
    override val name = "NewPipeExtractor"

    override fun resolve(hint: TrackHint): ResolvedStream {
        ensureInitialized()
        val preferLowBitrate = hint.preferLowBitrate
        try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${hint.videoId}")
            val candidates = info.audioStreams.filter {
                it.isUrl && it.content.isNotEmpty() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
            }
            if (candidates.isEmpty()) throw ExtractionException("NewPipeExtractor returned no progressive audio stream")
            val chosen = if (preferLowBitrate) {
                candidates.filter { it.averageBitrate > 0 }.minByOrNull { it.averageBitrate } ?: candidates.first()
            } else {
                candidates.maxByOrNull { it.averageBitrate } ?: candidates.first()
            }
            return ResolvedStream(
                url = chosen.content,
                mimeType = chosen.format?.mimeType,
                bitrate = chosen.averageBitrate.coerceAtLeast(0) * 1000,
                source = name,
            )
        } catch (e: ExtractionException) {
            throw e
        } catch (e: Exception) {
            throw ExtractionException("NewPipeExtractor failed: ${e.message}", e)
        }
    }

    private class OkHttpDownloader : Downloader() {
        private val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()

        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(request.url())
                .addHeader("User-Agent", USER_AGENT)
            for ((key, values) in request.headers()) {
                builder.removeHeader(key)
                for (v in values) builder.addHeader(key, v)
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                return Response(
                    resp.code,
                    resp.message,
                    resp.headers.toMultimap(),
                    resp.body?.string(),
                    resp.request.url.toString(),
                )
            }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        @Volatile
        private var initialized = false

        @Synchronized
        private fun ensureInitialized() {
            if (initialized) return
            NewPipe.init(OkHttpDownloader(), Localization.DEFAULT)
            initialized = true
        }
    }
}
