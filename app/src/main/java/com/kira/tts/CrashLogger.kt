package com.kira.tts

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide crash capture. Any uncaught exception (main thread or the
 * bridge service's worker threads share this process) is written to a file the
 * user can view/share on the next launch, then the previous handler runs so
 * Android still terminates the process as usual. Best-effort: it must never
 * throw from inside the handler and mask the original crash.
 */
object CrashLogger {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val ver = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (_: Throwable) { "?" }
                file(app)?.writeText("TTS_Kira $ver crash @ $ts\nthread=${thread.name}\n\n$sw")
            } catch (_: Throwable) { /* never mask the original crash */ }
            prev?.uncaughtException(thread, ex)
        }
    }

    /** Full text of the last stored crash, or null if none. */
    fun read(context: Context): String? = try {
        val f = file(context)
        if (f != null && f.exists() && f.length() > 0) f.readText() else null
    } catch (_: Throwable) { null }

    fun clear(context: Context) {
        try { file(context)?.delete() } catch (_: Throwable) {}
    }

    private fun file(context: Context): File? {
        val dir = context.applicationContext.getExternalFilesDir(null) ?: return null
        return File(dir, FILE)
    }
}
