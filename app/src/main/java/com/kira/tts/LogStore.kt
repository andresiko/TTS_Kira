package com.kira.tts

import android.os.Handler
import android.os.Looper

object LogStore {

    data class Entry(
        val tsMs: Long,
        val frame: MavlinkParser.Frame,
        val decoded: MessageCatalog.Decoded,
    )

    interface Listener {
        fun onEntriesChanged()
    }

    private const val MAX_ENTRIES = 2000
    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var listener: Listener? = null
    @Volatile private var pendingNotify = false
    @Volatile var paused: Boolean = false

    @Synchronized
    fun add(entry: Entry) {
        if (paused) return
        if (entries.size >= MAX_ENTRIES) entries.removeFirst()
        entries.addLast(entry)
        scheduleNotify()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        scheduleNotify()
    }

    fun setListener(l: Listener?) {
        listener = l
        if (l != null) mainHandler.post { l.onEntriesChanged() }
    }

    private fun scheduleNotify() {
        if (pendingNotify) return
        pendingNotify = true
        mainHandler.postDelayed({
            pendingNotify = false
            listener?.onEntriesChanged()
        }, 80)
    }
}
