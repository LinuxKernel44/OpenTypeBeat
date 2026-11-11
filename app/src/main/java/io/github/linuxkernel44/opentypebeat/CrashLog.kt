package io.github.linuxkernel44.opentypebeat

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.util.Date

/**
 * Minimal diagnostics: remembers the last uncaught Java exception and builds a shareable report
 * (device info, last crash, this app's own logcat). Tokens are never logged by the app.
 */
object CrashLog {
    private const val TAG = "CrashLog"
    private const val CRASH_FILE = "crash.txt"
    private const val MAX_CRASH_BYTES = 64 * 1024

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val file = File(app.filesDir, CRASH_FILE)
                if (file.length() > MAX_CRASH_BYTES) file.delete()
                file.appendText("=== ${Date()} thread=${thread.name}\n${Log.getStackTraceString(error)}\n")
            } catch (_: Throwable) {
                // never fail while handling a crash
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Blocking: reads logcat. Call from a background thread. */
    fun buildShareIntent(context: Context): Intent {
        val report = StringBuilder()
        report.appendLine("OpenTypeBeat ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        report.appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        report.appendLine()
        val crash = File(context.filesDir, CRASH_FILE)
        report.appendLine("---- last Java crashes ----")
        report.appendLine(if (crash.exists()) crash.readText() else "(none)")
        report.appendLine()
        report.appendLine("---- logcat (this app) ----")
        report.appendLine(readLogcat())

        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "opentypebeat-log.txt")
        file.writeText(report.toString())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "OpenTypeBeat debug log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun readLogcat(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "6000")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.destroy()
        text
    } catch (e: Throwable) {
        Log.w(TAG, "Could not read logcat", e)
        "(logcat unavailable: ${e.message})"
    }
}
