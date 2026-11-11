package io.github.linuxkernel44.opentypebeat.extract

import android.util.Log
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

/**
 * Holds the proof-of-origin tokens the web page itself sent to YouTube (captured by the bridge
 * extension), so that the native extractors can present the same token for the same visitor.
 *
 * Token values are never logged.
 */
object PoTokenStore : PoTokenProvider {
    private const val TAG = "PoTokenStore"
    private const val MAX_AGE_MS = 6L * 60 * 60 * 1000
    private const val REAL_TOKEN_MIN_LENGTH = 40

    @Volatile private var visitorData: String = ""
    @Volatile private var playerToken: String = ""
    @Volatile private var streamingToken: String = ""
    @Volatile private var updatedAt = 0L

    /** True when the page reports a signed-in session (tokens may then be account-bound). */
    @Volatile var loggedIn = false
        private set

    val hasToken: Boolean
        get() = (playerToken.isNotEmpty() || streamingToken.isNotEmpty()) && fresh()

    private fun fresh() = System.currentTimeMillis() - updatedAt < MAX_AGE_MS

    fun onPageSession(visitorData: String, loggedIn: Boolean) {
        this.loggedIn = loggedIn
        if (visitorData.isNotEmpty() && this.visitorData.isEmpty()) this.visitorData = visitorData
    }

    fun onTokenMessage(json: JSONObject) {
        val kind = json.optString("kind")
        val token = json.optString("token")
        if (token.isEmpty()) return
        val vd = json.optString("visitorData")
        when (kind) {
            "player" -> {
                playerToken = token
                if (vd.isNotEmpty()) visitorData = vd
            }
            "sabr", "url" -> streamingToken = token
            else -> return
        }
        updatedAt = System.currentTimeMillis()
        Log.i(
            TAG,
            "captured kind=$kind tokenLen=${token.length} visitorDataLen=${visitorData.length} " +
                "client=${json.optString("clientName")} fields=${json.optString("fields")} " +
                "inner=${json.optString("innerFields")} loggedIn=$loggedIn",
        )
    }

    /**
     * The page first sends a short "cold start" token (about 16 characters) and only later the
     * real attestation token (100+ characters). Prefer the real one for both uses.
     */
    private fun effectiveTokens(): Pair<String, String>? {
        val player = playerToken.takeIf { it.length >= REAL_TOKEN_MIN_LENGTH }.orEmpty()
        val streaming = streamingToken.takeIf { it.length >= REAL_TOKEN_MIN_LENGTH }.orEmpty()
        val real = player.ifEmpty { streaming }.ifEmpty { return null }
        return Pair(player.ifEmpty { real }, streaming.ifEmpty { real })
    }

    /** Arguments for yt-dlp's youtube extractor (null when nothing usable was captured). */
    fun ytDlpTokens(): Triple<String, String, String>? {
        if (!hasToken || visitorData.isEmpty()) return null
        val (player, streaming) = effectiveTokens() ?: return null
        return Triple(visitorData, player, streaming)
    }

    // ---- NewPipeExtractor PoTokenProvider ----------------------------------------------------

    private fun webResult(): PoTokenResult? {
        if (!hasToken || visitorData.isEmpty()) return null
        val (player, streaming) = effectiveTokens() ?: return null
        return PoTokenResult(visitorData, player, streaming)
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? = webResult()
}
