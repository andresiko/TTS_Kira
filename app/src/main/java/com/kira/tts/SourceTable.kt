package com.kira.tts

import android.util.Base64
import java.net.InetSocketAddress
import java.net.Socket

data class MountpointInfo(
    val name: String,
    val format: String,
    val country: String,
    val latDeg: Double,
    val lonDeg: Double,
    val networkSolution: Boolean,
    val generator: String
) {
    /** Etiqueta humana: VRS / Nearest / FKP / iMAX / MAC / Single base / ... */
    val type: String
        get() = when {
            generator.isNotBlank() -> generator
            networkSolution -> "Network"
            else -> "Single base"
        }
}

object SourceTable {
    /**
     * GET / to the caster, returns the STR records. Best-effort:
     * if the caster does not return a standard sourcetable, returns an empty list.
     */
    fun fetch(host: String, port: Int, user: String, pass: String, timeoutMs: Int = 5000): List<MountpointInfo> {
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                sock.soTimeout = timeoutMs
                val auth = if (user.isNotEmpty()) {
                    val creds = "$user:$pass"
                    val enc = Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
                    "Authorization: Basic $enc\r\n"
                } else ""
                val req = "GET / HTTP/1.0\r\n" +
                          "Host: $host\r\n" +
                          "Ntrip-Version: Ntrip/1.0\r\n" +
                          "User-Agent: NTRIP MavlinkApp/1.0\r\n" +
                          auth +
                          "Connection: close\r\n\r\n"
                sock.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                sock.getOutputStream().flush()
                val body = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1).readText()
                return body.lineSequence()
                    .filter { it.startsWith("STR;") }
                    .mapNotNull { parseStr(it) }
                    .toList()
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun parseStr(line: String): MountpointInfo? {
        val f = line.split(';')
        if (f.size < 13) return null
        return MountpointInfo(
            name = f[1],
            format = f.getOrNull(3) ?: "",
            country = f.getOrNull(8) ?: "",
            latDeg = f.getOrNull(9)?.toDoubleOrNull() ?: 0.0,
            lonDeg = f.getOrNull(10)?.toDoubleOrNull() ?: 0.0,
            networkSolution = f.getOrNull(12)?.trim() == "1",
            generator = f.getOrNull(13)?.trim() ?: ""
        )
    }
}
