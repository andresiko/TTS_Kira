package com.kira.tts

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Optional MAVLink monitor, fully decoupled from the RTK data path.
 *
 * When [enabled] is false (the default), the RTK service does nothing extra â€”
 * the only cost on the network thread is reading one volatile boolean. The
 * flag is persisted so it survives restarts; toggling it from the UI takes
 * effect immediately (same process, volatile field).
 */
object MonitorState {

    data class Entry(
        val tsMs: Long,
        val frame: MavlinkScan.Frame,
        val decoded: MsgCatalog.Decoded,
    )

    data class LinkHealth(
        val totalFrames: Long,
        val lossPercent: Double,
        val heartbeatIntervalMs: Long,
        val framesPerSec: Double,
        val distinctMsgIds: Int,
    )

    interface Listener { fun onMonitorChanged() }

    private const val PREFS = "ntrip_history"
    private const val KEY_ENABLED = "monitor_enabled"
    private const val KEY_PORT = "monitor_port"
    private const val DEFAULT_PORT = 14445
    private const val MAX_ENTRIES = 1500

    @Volatile var enabled: Boolean = true
        private set
    @Volatile var port: Int = DEFAULT_PORT
        private set
    @Volatile var paused: Boolean = false

    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var listener: Listener? = null
    @Volatile private var pendingNotify = false

    // Link-health tracking
    private val lastSeqByKey = HashMap<Int, Int>()
    private var receivedFrames = 0L
    private var lostFrames = 0L
    private var distinctMsgIds = HashSet<Int>()
    private var lastVehicleHbMs = 0L
    private var heartbeatIntervalMs = 0L
    private var windowStartMs = 0L
    private var windowCount = 0L
    private var framesPerSec = 0.0

    fun loadFrom(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean(KEY_ENABLED, true)
        port = p.getInt(KEY_PORT, DEFAULT_PORT)
    }

    fun setEnabled(ctx: Context, value: Boolean) {
        enabled = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        if (!value) reset()
    }

    fun setPort(ctx: Context, value: Int) {
        port = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PORT, value).apply()
    }

    /** Called from the network thread for each received datagram. Must never throw. */
    fun ingest(raw: ByteArray) {
        if (!enabled || paused) return
        val frames = MavlinkScan.scan(raw)
        if (frames.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            for (f in frames) {
                trackHealth(f, now)
                val decoded = MsgCatalog.decode(f)
                if (entries.size >= MAX_ENTRIES) entries.removeFirst()
                entries.addLast(Entry(now, f, decoded))
            }
        }
        scheduleNotify()
    }

    private fun trackHealth(f: MavlinkScan.Frame, now: Long) {
        val key = (f.sysId shl 8) or f.compId
        val prev = lastSeqByKey[key]
        if (prev != null) {
            val gap = ((f.seq - prev - 1) and 0xFF)
            // Ignore implausibly large gaps (likely a different stream / reset)
            if (gap in 1..64) lostFrames += gap
        }
        lastSeqByKey[key] = f.seq
        receivedFrames++
        distinctMsgIds.add(f.msgId)

        if (f.msgId == 0 && f.compId != 0 && f.sysId != 255) {
            if (lastVehicleHbMs != 0L) heartbeatIntervalMs = now - lastVehicleHbMs
            lastVehicleHbMs = now
        }

        if (windowStartMs == 0L) windowStartMs = now
        windowCount++
        val elapsed = now - windowStartMs
        if (elapsed >= 1000) {
            framesPerSec = windowCount * 1000.0 / elapsed
            windowStartMs = now
            windowCount = 0
        }
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun health(): LinkHealth {
        val total = receivedFrames + lostFrames
        val loss = if (total > 0) lostFrames * 100.0 / total else 0.0
        return LinkHealth(receivedFrames, loss, heartbeatIntervalMs, framesPerSec, distinctMsgIds.size)
    }

    @Synchronized
    fun reset() {
        entries.clear()
        lastSeqByKey.clear()
        receivedFrames = 0; lostFrames = 0
        distinctMsgIds = HashSet()
        lastVehicleHbMs = 0; heartbeatIntervalMs = 0
        windowStartMs = 0; windowCount = 0; framesPerSec = 0.0
        scheduleNotify()
    }

    fun setListener(l: Listener?) {
        listener = l
        if (l != null) mainHandler.post { l.onMonitorChanged() }
    }

    private fun scheduleNotify() {
        if (pendingNotify) return
        pendingNotify = true
        mainHandler.postDelayed({
            pendingNotify = false
            listener?.onMonitorChanged()
        }, 120)
    }
}
