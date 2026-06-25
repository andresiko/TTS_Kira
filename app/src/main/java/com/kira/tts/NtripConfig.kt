package com.kira.tts

data class NtripConfig(
    val host: String,
    val port: Int,
    val mountpoint: String,
    val user: String,
    val pass: String
) {
    companion object {
        fun parse(raw: String): NtripConfig {
            var input = raw.trim().removePrefix("http://").removePrefix("https://")
            var user = ""
            var pass = ""
            val atIdx = input.indexOf('@')
            if (atIdx >= 0) {
                val creds = input.substring(0, atIdx)
                input = input.substring(atIdx + 1)
                val colonIdx = creds.indexOf(':')
                if (colonIdx >= 0) {
                    user = creds.substring(0, colonIdx)
                    pass = creds.substring(colonIdx + 1)
                } else user = creds
            }
            val slashIdx = input.indexOf('/')
            val mountpoint = if (slashIdx >= 0) input.substring(slashIdx + 1) else ""
            val hostPort = if (slashIdx >= 0) input.substring(0, slashIdx) else input
            val colonIdx = hostPort.lastIndexOf(':')
            val host = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
            val port = if (colonIdx >= 0) hostPort.substring(colonIdx + 1).toIntOrNull() ?: 2101 else 2101
            return NtripConfig(host, port, mountpoint, user, pass)
        }
    }
}
