package com.kira.tts

import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Date
import java.util.Locale

class MonitorActivity : AppCompatActivity(), MonitorState.Listener {

    private lateinit var tvHealth: TextView
    private lateinit var tvLog: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnPause: Button
    private lateinit var btnClear: Button
    private lateinit var btnExport: Button

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> uri?.let { writeLogTo(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        tvHealth = findViewById(R.id.tvHealth)
        tvLog = findViewById(R.id.tvMonitorLog)
        scroll = findViewById(R.id.monitorScroll)
        btnPause = findViewById(R.id.btnMonitorPause)
        btnClear = findViewById(R.id.btnMonitorClear)
        btnExport = findViewById(R.id.btnMonitorExport)

        btnPause.setOnClickListener {
            MonitorState.paused = !MonitorState.paused
            btnPause.text = getString(if (MonitorState.paused) R.string.monitor_resume else R.string.monitor_pause)
        }
        btnClear.setOnClickListener { MonitorState.reset() }
        btnExport.setOnClickListener {
            val stamp = DateFormat.format("yyyyMMdd_HHmmss", Date())
            exportLauncher.launch("mavlink_log_$stamp.txt")
        }
    }

    private fun writeLogTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(buildExportText().toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, R.string.monitor_export_ok, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.monitor_export_fail, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun buildExportText(): String {
        val h = MonitorState.health()
        val sb = StringBuilder()
        sb.append("# KIRA-rtk MAVLink monitor log\n")
        sb.append("# Exported: ").append(DateFormat.format("yyyy-MM-dd HH:mm:ss", Date())).append('\n')
        sb.append("# Listen port: ").append(MonitorState.port).append('\n')
        sb.append("# Frames: ").append(h.totalFrames)
            .append("  Loss: ").append(String.format(Locale.US, "%.1f%%", h.lossPercent))
            .append("  HB: ").append(if (h.heartbeatIntervalMs > 0) "${h.heartbeatIntervalMs} ms" else "-")
            .append("  ").append(String.format(Locale.US, "%.0f msg/s", h.framesPerSec))
            .append("  ").append(h.distinctMsgIds).append(" msg types\n\n")
        for (e in MonitorState.snapshot()) {
            val time = DateFormat.format("HH:mm:ss", Date(e.tsMs))
            sb.append(time).append("  ")
                .append(e.frame.sysId).append('.').append(e.frame.compId)
                .append(" v").append(e.frame.version).append("  ")
                .append(e.decoded.name).append("  ")
                .append(e.decoded.detail).append('\n')
        }
        return sb.toString()
    }

    override fun onStart() {
        super.onStart()
        MonitorState.setListener(this)
    }

    override fun onStop() {
        super.onStop()
        MonitorState.setListener(null)
    }

    override fun onMonitorChanged() {
        val health = MonitorState.health()
        tvHealth.text = getString(
            R.string.monitor_health_fmt,
            health.totalFrames,
            health.lossPercent,
            if (health.heartbeatIntervalMs > 0) "${health.heartbeatIntervalMs} ms" else "â€”",
            health.framesPerSec,
            health.distinctMsgIds
        )

        val snapshot = MonitorState.snapshot()
        if (snapshot.isEmpty()) {
            tvLog.text = if (MonitorState.enabled) getString(R.string.monitor_waiting)
            else getString(R.string.monitor_disabled_hint)
            return
        }

        // Show only the last lines to keep the TextView light.
        val tail = if (snapshot.size > 400) snapshot.subList(snapshot.size - 400, snapshot.size) else snapshot
        val sb = StringBuilder(tail.size * 48)
        for (e in tail) {
            val time = DateFormat.format("HH:mm:ss", Date(e.tsMs))
            val mark = when (e.decoded.severity) {
                MsgCatalog.Severity.CRITICAL -> "!! "
                MsgCatalog.Severity.WARNING -> " ! "
                else -> "   "
            }
            sb.append(time).append(mark)
                .append(e.frame.sysId).append('.').append(e.frame.compId)
                .append(" v").append(e.frame.version).append("  ")
                .append(e.decoded.name).append("  ")
                .append(e.decoded.detail).append('\n')
        }

        val atBottom = run {
            val child = scroll.getChildAt(0) ?: return@run true
            child.bottom - (scroll.height + scroll.scrollY) <= 32
        }
        tvLog.text = sb
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
