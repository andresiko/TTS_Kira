package com.kira.tts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BridgeState.Listener {

    private lateinit var etFcIp: AutoCompleteTextView
    private lateinit var etFcPort: EditText
    private lateinit var etNtrip: AutoCompleteTextView
    private lateinit var btnConnect: Button
    private lateinit var btnClearCreds: Button
    private lateinit var btnResetFc: ImageButton
    private lateinit var btnMountpoints: Button
    private lateinit var switchMonitor: Switch
    private lateinit var btnOpenMonitor: Button
    private lateinit var etMonitorPort: EditText

    private lateinit var configHeader: ViewGroup
    private lateinit var configContent: ViewGroup
    private lateinit var configChevron: ImageView
    private lateinit var configSummary: TextView
    private lateinit var rootContainer: ViewGroup

    private lateinit var prefs: SharedPreferences
    private lateinit var fcIpAdapter: ArrayAdapter<String>
    private lateinit var ntripAdapter: ArrayAdapter<String>

    private lateinit var checkInternet: TextView
    private lateinit var checkNtrip: TextView
    private lateinit var checkRtcm: TextView
    private lateinit var checkPosition: TextView
    private lateinit var checkMavlink: TextView

    private lateinit var labelRtcm: TextView
    private lateinit var labelPosition: TextView
    private lateinit var labelMavlink: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var tvVersion: TextView

    private val logLines = ArrayDeque<String>(200)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var diagnosticRunnable: Runnable? = null
    private var lastDiagnosticMs = 0L
    private var autoConnectWatchdog: Runnable? = null
    private var connectionRequested = false

    companion object {
        private const val NOTIFICATIONS_PERMISSION_REQUEST = 1002
        private const val PREFS_NAME = "ntrip_history"
        private const val KEY_FC_IP = "fc_ip"
        private const val KEY_NTRIP = "ntrip"
        private const val KEY_LAST_CONNECT_OK = "last_connect_ok"
        private const val MAX_HISTORY = 8
        private const val HISTORY_SEP = "\n"
        private const val DEFAULT_FC_IP = "192.168.144.12"
        private const val DEFAULT_FC_PORT = "19856"
        private const val DIAGNOSTIC_DELAY_MS = 8000L
        private const val DIAGNOSTIC_COOLDOWN_MS = 30000L
        private const val AUTO_CONNECT_TIMEOUT_MS = 5000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etFcIp       = findViewById(R.id.etFcIp)
        etFcPort     = findViewById(R.id.etFcPort)
        etNtrip      = findViewById(R.id.etNtrip)
        btnConnect   = findViewById(R.id.btnConnect)
        btnClearCreds = findViewById(R.id.btnClearCreds)
        btnResetFc   = findViewById(R.id.btnResetFc)
        btnMountpoints = findViewById(R.id.btnMountpoints)
        switchMonitor  = findViewById(R.id.switchMonitor)
        btnOpenMonitor = findViewById(R.id.btnOpenMonitor)
        etMonitorPort  = findViewById(R.id.etMonitorPort)
        checkInternet  = findViewById(R.id.checkInternet)
        checkNtrip     = findViewById(R.id.checkNtrip)
        checkRtcm      = findViewById(R.id.checkRtcm)
        checkPosition  = findViewById(R.id.checkPosition)
        checkMavlink   = findViewById(R.id.checkMavlink)
        labelRtcm      = findViewById(R.id.labelRtcm)
        labelPosition  = findViewById(R.id.labelPosition)
        labelMavlink   = findViewById(R.id.labelMavlink)
        tvLog          = findViewById(R.id.tvLog)
        logScroll      = findViewById(R.id.logScroll)
        tvVersion      = findViewById(R.id.tvVersion)

        logScroll.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        configHeader   = findViewById(R.id.configHeader)
        configContent  = findViewById(R.id.configContent)
        configChevron  = findViewById(R.id.configChevron)
        configSummary  = findViewById(R.id.configSummary)
        rootContainer  = findViewById(android.R.id.content)

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "v${pInfo.versionName}"
        } catch (_: Exception) {}

        configHeader.setOnClickListener { toggleConfig() }

        btnConnect.setOnClickListener {
            if (NtripMavlinkService.isRunning) sendStop() else sendStart()
        }
        btnResetFc.setOnClickListener {
            etFcIp.setText(DEFAULT_FC_IP)
            etFcPort.setText(DEFAULT_FC_PORT)
            Toast.makeText(this, R.string.toast_ip_port_restored, Toast.LENGTH_SHORT).show()
        }
        btnMountpoints.setOnClickListener { detectMountpoints() }

        MonitorState.loadFrom(this)
        etMonitorPort.setText(MonitorState.port.toString())
        switchMonitor.isChecked = MonitorState.enabled
        switchMonitor.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val p = etMonitorPort.text.toString().toIntOrNull()
                if (p == null || p !in 1..65535) {
                    Toast.makeText(this, R.string.toast_invalid_port, Toast.LENGTH_SHORT).show()
                    switchMonitor.isChecked = false
                    return@setOnCheckedChangeListener
                }
                MonitorState.setPort(this, p)
                MonitorState.setEnabled(this, true)
            } else {
                MonitorState.setEnabled(this, false)
            }
        }
        btnOpenMonitor.setOnClickListener {
            startActivity(Intent(this, MonitorActivity::class.java))
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        fcIpAdapter = buildHistoryAdapter(loadHistory(KEY_FC_IP))
        ntripAdapter = buildHistoryAdapter(loadHistory(KEY_NTRIP))
        etFcIp.setAdapter(fcIpAdapter)
        etNtrip.setAdapter(ntripAdapter)
        attachHistoryUi(etFcIp, KEY_FC_IP, fcIpAdapter)
        attachHistoryUi(etNtrip, KEY_NTRIP, ntripAdapter)

        loadHistory(KEY_NTRIP).firstOrNull()?.let { etNtrip.setText(it) }

        btnClearCreds.setOnClickListener { clearStoredCredentials() }

        etNtrip.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else false
        }

        resetChecksUi()
        requestOverlayPermission()
        requestNotificationsPermission()

        maybeAutoConnect()
    }

    private fun maybeAutoConnect() {
        if (NtripMavlinkService.isRunning) return
        if (!prefs.getBoolean(KEY_LAST_CONNECT_OK, false)) return
        if (etNtrip.text.toString().isBlank()) return
        sendStart()
        val r = Runnable {
            if (BridgeState.checks.ntripConnected != CheckState.OK) {
                val problems = collectDiagnosticProblems(BridgeState.checks)
                if (problems.isNotEmpty()) {
                    setConfigExpanded(true)
                    showDiagnosticDialog(problems)
                }
                sendStop()
            }
        }
        autoConnectWatchdog = r
        mainHandler.postDelayed(r, AUTO_CONNECT_TIMEOUT_MS)
    }

    override fun onStart() {
        super.onStart()
        BridgeState.setListener(this)
        // Honor a pending connect request (e.g. auto-connect started in onCreate)
        // even before the service has flipped isRunning asynchronously.
        applyConnectedUi(NtripMavlinkService.isRunning || connectionRequested)
    }

    override fun onStop() {
        super.onStop()
        BridgeState.setListener(null)
        diagnosticRunnable?.let { mainHandler.removeCallbacks(it) }
        autoConnectWatchdog?.let { mainHandler.removeCallbacks(it) }
    }

    override fun onChecks(s: StatusChecks) { runOnUiThread { renderChecks(s) } }
    override fun onLog(msg: String) { runOnUiThread { appendLog(msg) } }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATIONS_PERMISSION_REQUEST
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // History
    // -------------------------------------------------------------------------

    private fun buildHistoryAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items.toMutableList())

    private fun loadHistory(key: String): List<String> =
        prefs.getString(key, null)
            ?.split(HISTORY_SEP)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun saveHistory(key: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        val updated = (listOf(trimmed) + loadHistory(key).filter { it != trimmed })
            .take(MAX_HISTORY)
        prefs.edit().putString(key, updated.joinToString(HISTORY_SEP)).apply()
        val adapter = if (key == KEY_FC_IP) fcIpAdapter else ntripAdapter
        adapter.clear()
        adapter.addAll(updated)
        adapter.notifyDataSetChanged()
    }

    private fun stripCredentials(ntrip: String): String {
        val s = ntrip.trim().removePrefix("http://").removePrefix("https://")
        val at = s.lastIndexOf('@')
        return if (at >= 0) s.substring(at + 1) else s
    }

    private fun clearStoredCredentials() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_clear_credentials_title)
            .setMessage(R.string.dialog_clear_credentials_message)
            .setPositiveButton(R.string.dialog_clear_credentials_positive) { _, _ ->
                etNtrip.setText(stripCredentials(etNtrip.text.toString()))
                etNtrip.setSelection(etNtrip.text.length)
                val cleaned = loadHistory(KEY_NTRIP)
                    .map { stripCredentials(it) }
                    .filter { it.isNotBlank() }
                    .distinct()
                prefs.edit().putString(KEY_NTRIP, cleaned.joinToString(HISTORY_SEP)).apply()
                ntripAdapter.clear()
                ntripAdapter.addAll(cleaned)
                ntripAdapter.notifyDataSetChanged()
                Toast.makeText(this, R.string.toast_credentials_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeFromHistory(key: String, value: String) {
        val updated = loadHistory(key).filter { it != value }
        prefs.edit().putString(key, updated.joinToString(HISTORY_SEP)).apply()
        val adapter = if (key == KEY_FC_IP) fcIpAdapter else ntripAdapter
        adapter.remove(value)
        adapter.notifyDataSetChanged()
    }

    private fun attachHistoryUi(
        view: AutoCompleteTextView,
        key: String,
        adapter: ArrayAdapter<String>
    ) {
        val showIfAny = {
            if (adapter.count > 0 && view.isEnabled) view.showDropDown()
        }
        view.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showIfAny() }
        view.setOnClickListener { showIfAny() }

        view.setOnLongClickListener {
            val items = (0 until adapter.count).mapNotNull { adapter.getItem(it) }
            if (items.isEmpty()) return@setOnLongClickListener false
            val arr = items.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_history_title)
                .setItems(arr) { _, which -> removeFromHistory(key, arr[which]) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }
    }

    // -------------------------------------------------------------------------
    // Mountpoint detection
    // -------------------------------------------------------------------------

    private fun detectMountpoints() {
        val ntripStr = etNtrip.text.toString().trim()
        if (ntripStr.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_caster_first, Toast.LENGTH_SHORT).show()
            return
        }
        val cfg = try { NtripConfig.parse(ntripStr) } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_invalid_ntrip_format, Toast.LENGTH_SHORT).show()
            return
        }
        if (cfg.host.isEmpty()) {
            Toast.makeText(this, R.string.toast_empty_caster_host, Toast.LENGTH_SHORT).show()
            return
        }

        btnMountpoints.text = getString(R.string.mountpoints_searching)
        btnMountpoints.isEnabled = false

        Thread {
            val list = SourceTable.fetch(cfg.host, cfg.port, cfg.user, cfg.pass)
            runOnUiThread {
                btnMountpoints.text = getString(R.string.mountpoints_button)
                btnMountpoints.isEnabled = true
                if (list.isEmpty()) {
                    Toast.makeText(this, R.string.toast_no_mountpoints, Toast.LENGTH_LONG).show()
                } else {
                    showMountpointsDialog(list, cfg)
                }
            }
        }.start()
    }

    private fun showMountpointsDialog(list: List<MountpointInfo>, cfg: NtripConfig) {
        val labels = list.map { mp ->
            val tag = mp.type.ifBlank { mp.format }
            "${mp.name}  â€”  $tag"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_mountpoints_title, cfg.host))
            .setItems(labels) { _, which ->
                val picked = list[which]
                etNtrip.setText(rewriteMountpoint(etNtrip.text.toString(), picked.name))
                etNtrip.setSelection(etNtrip.text.length)
                Toast.makeText(this, getString(R.string.toast_mountpoint_selected, picked.name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rewriteMountpoint(current: String, newMount: String): String {
        val raw = current.trim().removePrefix("http://").removePrefix("https://")
        val slashIdx = raw.indexOf('/')
        return if (slashIdx >= 0) "${raw.substring(0, slashIdx)}/$newMount"
               else "$raw/$newMount"
    }

    // -------------------------------------------------------------------------
    // Connection via Service
    // -------------------------------------------------------------------------

    private fun sendStart() {
        val ip   = etFcIp.text.toString().trim()
        val port = etFcPort.text.toString().toIntOrNull() ?: 19856
        val ntripStr = etNtrip.text.toString().trim()

        if (ip.isEmpty() || ntripStr.isEmpty()) {
            Toast.makeText(this, R.string.toast_fill_all_fields, Toast.LENGTH_SHORT).show()
            return
        }

        val cfg = try { NtripConfig.parse(ntripStr) } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_invalid_ntrip_format, Toast.LENGTH_SHORT).show()
            return
        }
        if (cfg.host.isEmpty() || cfg.mountpoint.isEmpty()) {
            Toast.makeText(this, R.string.toast_empty_host_or_mountpoint, Toast.LENGTH_SHORT).show()
            return
        }

        saveHistory(KEY_FC_IP, ip)
        saveHistory(KEY_NTRIP, ntripStr)

        logLines.clear()
        resetChecksUi()

        val intent = Intent(this, NtripMavlinkService::class.java).apply {
            action = NtripMavlinkService.ACTION_START
            putExtra(NtripMavlinkService.EXTRA_FC_IP, ip)
            putExtra(NtripMavlinkService.EXTRA_FC_PORT, port)
            putExtra(NtripMavlinkService.EXTRA_NTRIP_HOST, cfg.host)
            putExtra(NtripMavlinkService.EXTRA_NTRIP_PORT, cfg.port)
            putExtra(NtripMavlinkService.EXTRA_NTRIP_MOUNT, cfg.mountpoint)
            putExtra(NtripMavlinkService.EXTRA_NTRIP_USER, cfg.user)
            putExtra(NtripMavlinkService.EXTRA_NTRIP_PASS, cfg.pass)
        }
        ContextCompat.startForegroundService(this, intent)
        applyConnectedUi(true)
        scheduleDiagnostic()
    }

    private fun sendStop() {
        val intent = Intent(this, NtripMavlinkService::class.java)
            .setAction(NtripMavlinkService.ACTION_STOP)
        startService(intent)
        applyConnectedUi(false)
        resetChecksUi()
        diagnosticRunnable?.let { mainHandler.removeCallbacks(it) }
        autoConnectWatchdog?.let { mainHandler.removeCallbacks(it) }
        autoConnectWatchdog = null
    }

    private fun applyConnectedUi(connected: Boolean) {
        connectionRequested = connected
        if (connected) {
            btnConnect.text = getString(R.string.disconnect_button)
            btnConnect.setBackgroundResource(R.drawable.btn_danger)
            setFieldsEnabled(false)
            setConfigExpanded(false)
        } else {
            btnConnect.text = getString(R.string.connect_button)
            btnConnect.setBackgroundResource(R.drawable.btn_primary)
            setFieldsEnabled(true)
            setConfigExpanded(true)
        }
    }

    private fun setConfigExpanded(expanded: Boolean) {
        TransitionManager.beginDelayedTransition(rootContainer)
        configContent.visibility = if (expanded) View.VISIBLE else View.GONE
        configChevron.setImageResource(
            if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
        if (expanded) {
            configSummary.visibility = View.GONE
        } else {
            configSummary.text = buildConfigSummary()
            configSummary.visibility = View.VISIBLE
        }
    }

    private fun toggleConfig() {
        setConfigExpanded(configContent.visibility != View.VISIBLE)
    }

    private fun buildConfigSummary(): String {
        val ip = etFcIp.text.toString().trim().ifEmpty { "â€”" }
        val port = etFcPort.text.toString().trim().ifEmpty { "â€”" }
        val mp = try {
            NtripConfig.parse(etNtrip.text.toString()).mountpoint.ifEmpty { "â€”" }
        } catch (_: Exception) { "â€”" }
        return "$ip:$port Â· $mp"
    }

    // -------------------------------------------------------------------------
    // Post-connection diagnostics
    // -------------------------------------------------------------------------

    private fun scheduleDiagnostic() {
        diagnosticRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            val now = System.currentTimeMillis()
            if (now - lastDiagnosticMs < DIAGNOSTIC_COOLDOWN_MS) return@Runnable
            val problems = collectDiagnosticProblems(BridgeState.checks)
            if (problems.isNotEmpty()) {
                lastDiagnosticMs = now
                setConfigExpanded(true)
                showDiagnosticDialog(problems)
            }
        }
        diagnosticRunnable = r
        mainHandler.postDelayed(r, DIAGNOSTIC_DELAY_MS)
    }

    private fun collectDiagnosticProblems(s: StatusChecks): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (s.internet != CheckState.OK) {
            out += getString(R.string.diagnostic_no_internet_title) to
                getString(R.string.diagnostic_no_internet_hint)
        } else if (s.ntripConnected != CheckState.OK) {
            out += getString(R.string.diagnostic_ntrip_failed_title) to
                getString(R.string.diagnostic_ntrip_failed_hint)
        } else if (s.rtcmReceiving != CheckState.OK) {
            out += getString(R.string.diagnostic_no_rtcm_title) to
                getString(R.string.diagnostic_no_rtcm_hint)
        }
        if (s.dronePosition != CheckState.OK) {
            out += getString(R.string.diagnostic_no_drone_position_title) to
                getString(R.string.diagnostic_no_drone_position_hint)
        }
        return out
    }

    private fun showDiagnosticDialog(problems: List<Pair<String, String>>) {
        val msg = StringBuilder()
        problems.forEachIndexed { i, (title, hint) ->
            if (i > 0) msg.append("\n\n")
            msg.append("â€¢ ").append(title).append("\n").append(hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_diagnostic_title)
            .setMessage(msg)
            .setPositiveButton(R.string.dialog_diagnostic_positive, null)
            .show()
    }

    // -------------------------------------------------------------------------
    // UI renders
    // -------------------------------------------------------------------------

    private fun renderChecks(s: StatusChecks) {
        setCheck(checkInternet,  s.internet)
        setCheck(checkNtrip,     s.ntripConnected)
        setCheck(checkRtcm,      s.rtcmReceiving)
        setCheck(checkPosition,  s.dronePosition)
        setCheck(checkMavlink,   s.mavlinkSending)

        if (s.ntripConnected == CheckState.OK) {
            prefs.edit().putBoolean(KEY_LAST_CONNECT_OK, true).apply()
            autoConnectWatchdog?.let { mainHandler.removeCallbacks(it) }
            autoConnectWatchdog = null
        }

        if (s.rtcmReceiving == CheckState.OK) {
            val baseStr = s.baseDistanceKm?.let { d ->
                val typePart = s.mountpointType?.let { "$it Â· " } ?: ""
                if (d < 1.0) getString(R.string.rtcm_base_distance_m, typePart, (d * 1000).toInt())
                else getString(R.string.rtcm_base_distance_km, typePart, d)
            } ?: s.mountpointType?.let { getString(R.string.rtcm_mountpoint_type, it) } ?: ""
            labelRtcm.text = getString(R.string.rtcm_label_with_kb, s.rtcmKb, baseStr)
        }

        if (s.dronePosition == CheckState.OK && s.dronePos != null) {
            labelPosition.text = getString(
                R.string.initial_position_label,
                s.dronePos.latDeg,
                s.dronePos.lonDeg
            )
        }

        if (s.mavlinkSending == CheckState.OK)
            labelMavlink.text = getString(R.string.mavlink_sent_count, s.mavMsgs)
        else if (s.mavlinkSending == CheckState.FAIL)
            labelMavlink.text = getString(R.string.mavlink_send_stopped)

        if ((s.ntripConnected == CheckState.FAIL || s.internet == CheckState.FAIL) &&
            !NtripMavlinkService.isRunning) {
            applyConnectedUi(false)
        }
    }

    private fun setCheck(tv: TextView, state: CheckState) {
        val pending = ContextCompat.getColor(this, R.color.text_tertiary)
        val ok      = ContextCompat.getColor(this, R.color.success)
        val fail    = ContextCompat.getColor(this, R.color.danger)
        when (state) {
            CheckState.PENDING -> { tv.text = "â—‹"; tv.setTextColor(pending) }
            CheckState.OK      -> { tv.text = "â—"; tv.setTextColor(ok) }
            CheckState.FAIL    -> { tv.text = "âœ•"; tv.setTextColor(fail) }
        }
    }

    private fun resetChecksUi() {
        val pending = ContextCompat.getColor(this, R.color.text_tertiary)
        listOf(checkInternet, checkNtrip, checkRtcm, checkPosition, checkMavlink).forEach {
            it.text = "â—‹"; it.setTextColor(pending)
        }
        labelRtcm.text     = getString(R.string.status_rtcm)
        labelPosition.text = getString(R.string.status_initial_position)
        labelMavlink.text  = getString(R.string.status_mavlink_sending)
        tvLog.text = getString(R.string.log_placeholder)
        logLines.clear()
    }

    private fun appendLog(msg: String) {
        if (logLines.size >= 200) logLines.removeFirst()
        logLines.addLast(msg)
        // Auto-scroll only if the user is at the bottom (24 px margin)
        val atBottom = run {
            val child = logScroll.getChildAt(0) ?: return@run true
            val diff = child.bottom - (logScroll.height + logScroll.scrollY)
            diff <= 24
        }
        tvLog.text = logLines.joinToString("\n")
        if (atBottom) {
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        etFcIp.isEnabled   = enabled
        etFcPort.isEnabled = enabled
        etNtrip.isEnabled  = enabled
        btnClearCreds.isEnabled = enabled
        btnResetFc.isEnabled = enabled
        btnMountpoints.isEnabled = enabled
    }
}
