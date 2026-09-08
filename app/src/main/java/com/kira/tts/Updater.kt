package com.kira.tts

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update against the public GitHub Releases of this repo. No token: the
 * repository is public, so the releases API and asset downloads are anonymous.
 * The release tag is "v<versionCode>" (monotonic git commit count), which is
 * what we compare against the installed versionCode.
 */
object Updater {
    private const val LATEST_URL =
        "https://api.github.com/repos/andresiko/TTS_Kira/releases/latest"

    data class Release(val versionCode: Int, val name: String, val apkUrl: String)

    /** Blocking network call — run off the main thread. Returns null on any failure. */
    fun fetchLatest(): Release? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TTS_Kira-updater")
            }
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val vc = tag.trimStart('v', 'V').toIntOrNull() ?: return null
            val name = json.optString("name").ifEmpty { tag }
            val assets = json.optJSONArray("assets") ?: return null
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    apk = a.optString("browser_download_url"); break
                }
            }
            apk?.let { Release(vc, name, it) }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Downloads the APK to app-private external storage. Returns the file or null. */
    fun downloadApk(context: Context, url: String): File? {
        var conn: HttpURLConnection? = null
        return try {
            val dir = context.getExternalFilesDir(null) ?: return null
            val out = File(dir, "update.apk")
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "TTS_Kira-updater")
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.use { input -> out.outputStream.use { input.copyTo(it) } }
            out
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
