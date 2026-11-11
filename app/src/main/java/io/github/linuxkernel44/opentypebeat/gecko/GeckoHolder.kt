package io.github.linuxkernel44.opentypebeat.gecko

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import io.github.linuxkernel44.opentypebeat.BuildConfig
import io.github.linuxkernel44.opentypebeat.OpenTypeBeatApp
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Owns the single GeckoRuntime and GeckoSession of the app, plus the WebExtensions
 * (the built-in page bridge, uBlock Origin and SponsorBlock).
 *
 * The session outlives the activity so playback keeps going when the UI is closed.
 */
@UnstableApi
object GeckoHolder {
    private const val TAG = "GeckoHolder"
    const val NATIVE_APP = "opentypebeat"
    private const val BRIDGE_ID = "bridge@opentypebeat.linuxkernel44.github.io"
    private const val BRIDGE_URI = "resource://android/assets/bridge/"

    /** AMO extensions installed on first launch. */
    private data class Addon(val id: String, val name: String, val url: String)

    private val addons = listOf(
        Addon(
            "uBlock0@raymondhill.net",
            "uBlock Origin",
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi",
        ),
        Addon(
            "sponsorBlocker@ajay.app",
            "SponsorBlock",
            "https://addons.mozilla.org/firefox/downloads/latest/sponsorblock/latest.xpi",
        ),
    )

    @Volatile
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null

    /** Status messages for the UI (extension installs etc.). */
    var onStatus: ((String) -> Unit)? = null

    fun runtime(context: Context): GeckoRuntime =
        runtime ?: synchronized(this) {
            runtime ?: createRuntime(context.applicationContext).also { runtime = it }
        }

    fun session(context: Context, desktop: Boolean): GeckoSession {
        val rt = runtime(context)
        session?.let { return it }
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(
                if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
            )
            .viewportMode(
                if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE,
            )
            .build()
        return GeckoSession(settings).also {
            it.setPermissionDelegate(AutoplayPermissionDelegate())
            it.open(rt)
            session = it
        }
    }

    private fun createRuntime(app: Context): GeckoRuntime {
        val settings = GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .consoleOutput(BuildConfig.DEBUG)
            .aboutConfigEnabled(BuildConfig.DEBUG)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .build()
        val rt = GeckoRuntime.create(app, settings)
        setupExtensions(rt, app)
        return rt
    }

    private fun setupExtensions(rt: GeckoRuntime, app: Context) {
        val controller = rt.webExtensionController

        // The user installs trusted AMO add-ons through this app only; accept them silently.
        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse>? =
                GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, false, true))
        }

        controller.ensureBuiltIn(BRIDGE_URI, BRIDGE_ID).accept(
            { ext ->
                ext?.setMessageDelegate(
                    object : WebExtension.MessageDelegate {
                        override fun onConnect(port: WebExtension.Port) {
                            (app.applicationContext as OpenTypeBeatApp).coordinator.onPortConnected(port)
                        }
                    },
                    NATIVE_APP,
                )
            },
            { e -> Log.e(TAG, "Could not load the page bridge extension", e) },
        )

        installMissingAddons(rt, force = false)
    }

    /** Installs uBlock Origin / SponsorBlock when they are missing (or reinstalls them if [force]). */
    fun installMissingAddons(rt: GeckoRuntime, force: Boolean) {
        val controller = rt.webExtensionController
        controller.list().accept(
            { installed ->
                val byId = installed.orEmpty().associateBy { it.id }
                for (addon in addons) {
                    val existing = byId[addon.id]
                    if (existing != null && !force) continue
                    val doInstall = {
                        controller.install(addon.url, WebExtensionController.INSTALLATION_METHOD_MANAGER).accept(
                            { onStatus?.invoke("${addon.name} installed") },
                            { e ->
                                Log.e(TAG, "Installing ${addon.name} failed", e)
                                onStatus?.invoke("Could not install ${addon.name}: ${e?.message}")
                            },
                        )
                    }
                    if (existing != null) {
                        controller.uninstall(existing).accept({ doInstall() }, { doInstall() })
                    } else {
                        doInstall()
                    }
                }
            },
            { e -> Log.e(TAG, "Listing extensions failed", e) },
        )
    }

    /** Allows media autoplay (needed so the queue can advance on its own); denies the rest. */
    private class AutoplayPermissionDelegate : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? {
            val allow = perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
            return GeckoResult.fromValue(
                if (allow) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
            )
        }
    }
}
