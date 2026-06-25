package com.kira.tts

import java.util.ArrayDeque

enum class CheckState { PENDING, OK, FAIL }

data class StatusChecks(
    val internet: CheckState = CheckState.PENDING,
    val ntripConnected: CheckState = CheckState.PENDING,
    val rtcmReceiving: CheckState = CheckState.PENDING,
    val droneConnected: CheckState = CheckState.PENDING,
    val dronePosition: CheckState = CheckState.PENDING,
    val mavlinkSending: CheckState = CheckState.PENDING,
    val dronePos: MavlinkHelper.DronePosition? = null,
    val rtcmKb: Long = 0,
    val mavMsgs: Long = 0,
    val baseDistanceKm: Double? = null,
    val centerDistanceKm: Double? = null,
    val baseLat: Double? = null,
    val baseLon: Double? = null,
    val mountpointType: String? = null
)

/**
 * Singleton compartido entre Service (productor) y Activity (consumidor).
 * Como todo vive en el mismo proceso basta una referencia a memoria.
 */
object BridgeState {
    interface Listener {
        fun onChecks(s: StatusChecks)
        fun onLog(msg: String)
    }

    private const val MAX_LOG_LINES = 200

    @Volatile var checks: StatusChecks = StatusChecks()
        private set

    private val logLines = ArrayDeque<String>()
    @Volatile private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
        if (l != null) {
            l.onChecks(checks)
            val snapshot = synchronized(logLines) { logLines.toList() }
            snapshot.forEach { l.onLog(it) }
        }
    }

    fun publishChecks(s: StatusChecks) {
        checks = s
        listener?.onChecks(s)
    }

    fun publishLog(msg: String) {
        synchronized(logLines) {
            if (logLines.size >= MAX_LOG_LINES) logLines.removeFirst()
            logLines.addLast(msg)
        }
        listener?.onLog(msg)
    }

    fun reset() {
        checks = StatusChecks()
        synchronized(logLines) { logLines.clear() }
        listener?.onChecks(checks)
    }
}
