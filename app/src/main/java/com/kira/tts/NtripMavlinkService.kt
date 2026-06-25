package com.kira.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class NtripMavlinkService : Service() {

    companion object {
        const val ACTION_START = "com.kira.tts.START"
        const val ACTION_STOP  = "com.kira.tts.STOP"
        const val EXTRA_FC_IP        = "fc_ip"
        const val EXTRA_FC_PORT      = "fc_port"
        const val EXTRA_NTRIP_HOST   = "ntrip_host"
        const val EXTRA_NTRIP_PORT   = "ntrip_port"
        const val EXTRA_NTRIP_MOUNT  = "ntrip_mount"
        const val EXTRA_NTRIP_USER   = "ntrip_user"
        const val EXTRA_NTRIP_PASS   = "ntrip_pass"

        private const val CHANNEL_ID = "ntrip_bridge"
        private const val NOTIF_ID = 7423

        @Volatile var isRunning: Boolean = false
            private set
    }

    private val running = AtomicBoolean(false)
    private var udpSocket: DatagramSocket? = null
    private var ntripSocket: Socket? = null
    private var monitorSocket: DatagramSocket? = null

    private lateinit var fcIp: String
    private var fcPort: Int = 19856
    private lateinit var ntripConfig: NtripConfig

    @Volatile private var dronePos: MavlinkHelper.DronePosition? = null
    @Volatile private var rtcmBytesTotal = 0L
    @Volatile private var mavMsgsTotal = 0L
    private var checks = StatusChecks()

    @Volatile private var baseLat: Double? = null
    @Volatile private var baseLon: Double? = null
    @Volatile private var centerLat: Double? = null
    @Volatile private var centerLon: Double? = null
    @Volatile private var mountpointType: String? = null
    private val rtcmParser = RtcmHelper.StreamParser()

    @Volatile private var loggedVehicleHb = false
    @Volatile private var loggedGcsHb = false
    @Volatile private var loggedMavlinkNoHb = false
    @Volatile private var lastRtcmMs = 0L
    @Volatile private var everHadPosition = false

    private val rtcmTimeoutMs = 10000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    @Volatile private var bubbleDismissed = false
    private var bubbleAdded = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        MonitorState.loadFrom(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBridge()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (running.get()) return START_STICKY
                val ip   = intent.getStringExtra(EXTRA_FC_IP)
                val host = intent.getStringExtra(EXTRA_NTRIP_HOST)
                val mp   = intent.getStringExtra(EXTRA_NTRIP_MOUNT)
                if (ip.isNullOrBlank() || host.isNullOrBlank() || mp.isNullOrBlank()) {
                    stopSelf(); return START_NOT_STICKY
                }
                fcIp = ip
                fcPort = intent.getIntExtra(EXTRA_FC_PORT, 19856)
                ntripConfig = NtripConfig(
                    host = host,
                    port = intent.getIntExtra(EXTRA_NTRIP_PORT, 2101),
                    mountpoint = mp,
                    user = intent.getStringExtra(EXTRA_NTRIP_USER) ?: "",
                    pass = intent.getStringExtra(EXTRA_NTRIP_PASS) ?: ""
                )
                BridgeState.reset()
                checks = StatusChecks()
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_connecting_caster), false))
                bubbleDismissed = false
                showBubble(false)
                isRunning = true
                startBridge()
                return START_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopBridge()
        removeBubble()
        isRunning = false
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(summary: String, rtkOk: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NtripMavlinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(summary)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setColor(if (rtkOk) 0xFF22C55E.toInt() else 0xFF4F8CFF.toInt())
            .build()
    }

    private fun updateNotification() {
        val rtkOk = everHadPosition && checks.mavlinkSending == CheckState.OK
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(notifSummary(), rtkOk))
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS permission not granted */ }
    }

    private fun notifSummary(): String {
        val s = checks
        val rtkOk = everHadPosition && s.mavlinkSending == CheckState.OK
        return when {
            rtkOk -> {
                val base = s.baseDistanceKm?.let {
                    if (it < 1.0) getString(R.string.notification_base_m, (it * 1000).toInt())
                    else getString(R.string.notification_base_km, it)
                } ?: ""
                getString(R.string.notification_rtk_active, base)
            }
            s.ntripConnected == CheckState.OK && !everHadPosition ->
                getString(R.string.notification_waiting_position)
            s.internet == CheckState.OK ->
                getString(R.string.notification_connecting_caster)
            else -> getString(R.string.notification_no_internet)
        }
    }

    // -------------------------------------------------------------------------
    // Overlay bubble
    // -------------------------------------------------------------------------

    private fun showBubble(rtkOk: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showBubble(rtkOk) }
            return
        }
        if (!Settings.canDrawOverlays(this)) return
        if (bubbleDismissed) return

        val bgRes = if (rtkOk) R.drawable.bubble_ok else R.drawable.bubble_fail
        val text = if (rtkOk) getString(R.string.bubble_fix) else getString(R.string.bubble_no_fix)

        val view = ensureBubbleView()
        view.text = text
        view.setBackgroundResource(bgRes)

        // Attach only if not already attached. The view is reused for the whole
        // service lifetime, so addView can never run twice â†’ a single bubble.
        if (!bubbleAdded) {
            view.alpha = 1f
            try {
                windowManager?.addView(view, bubbleParams)
            } catch (_: Exception) { /* already added; ignore */ }
            bubbleAdded = true
        }
    }

    /** Lazily builds the single, reusable bubble view + params. */
    private fun ensureBubbleView(): TextView {
        bubbleView?.let { return it }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            elevation = 12f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(48)
        }
        attachDragHandler(view, params)
        bubbleView = view
        bubbleParams = params
        return view
    }

    private fun attachDragHandler(view: TextView, params: WindowManager.LayoutParams) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val dismissTop = dp(110)       // top band that triggers removal
        val dismissRadiusX = dp(130)   // horizontal tolerance around screen center
        val homeX = dp(16); val homeY = dp(48)
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false

        fun inDismissZone(): Boolean {
            val centerX = params.x + view.width / 2
            return params.y <= dismissTop && abs(centerX - screenW / 2) <= dismissRadiusX
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    moved = true
                    // Fade as a hint that releasing here removes the bubble.
                    view.alpha = if (inDismissZone()) 0.4f else 1f
                    try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved && inDismissZone()) {
                        bubbleDismissed = true
                        view.post {
                            removeBubble()
                            // Reset for a clean reappearance on the next connect.
                            view.alpha = 1f
                            params.x = homeX; params.y = homeY
                        }
                    } else {
                        view.alpha = 1f
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun removeBubble() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { removeBubble() }
            return
        }
        val view = bubbleView ?: return
        if (bubbleAdded) {
            try { windowManager?.removeView(view) } catch (_: Exception) {}
            bubbleAdded = false
        }
        // Keep the view reference so it is reused (never recreated).
    }

    // -------------------------------------------------------------------------
    // Bridge workers
    // -------------------------------------------------------------------------

    private fun startBridge() {
        running.set(true)
        Thread { runUdpLoop() }.apply { isDaemon = true; start() }
        Thread { runNtripLoop() }.apply { isDaemon = true; start() }
        Thread { runMonitorLoop() }.apply { isDaemon = true; start() }
    }

    private fun stopBridge() {
        running.set(false)
        try { ntripSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        closeMonitorSocket()
        log(getString(R.string.log_disconnected))
    }

    /** Updates drone position from any source (RTK socket or monitor forwarding). */
    private fun onDronePosition(pos: MavlinkHelper.DronePosition) {
        dronePos = pos
        if (!everHadPosition) {
            everHadPosition = true
            log(getString(R.string.log_initial_position, pos.latDeg, pos.lonDeg))
        }
        checks = checks.copy(
            dronePosition = CheckState.OK,
            dronePos = pos,
            rtcmKb = rtcmBytesTotal / 1024,
            mavMsgs = mavMsgsTotal,
            baseDistanceKm = computeBaseDistKm(pos),
            centerDistanceKm = computeCenterDistKm(pos)
        )
        publishChecks()
    }

    /**
     * Forwarding-port reader: ALWAYS listens on MonitorState.port (the QGC MAVLink
     * forwarding endpoint, default 14445) on its own dedicated socket. It is the
     * PRIMARY position source for the GGA, so the VRS position stays fresh even
     * when QGC owns the FC port, and it also feeds the message monitor (which
     * self-gates on MonitorState.enabled). If the configured port equals the FC
     * port it shares the RTK socket via the tap in runUdpLoop instead.
     */
    private fun runMonitorLoop() {
        val buf = ByteArray(8192)
        val pkt = DatagramPacket(buf, buf.size)
        var loggedListening = false
        while (running.get()) {
            val port = MonitorState.port
            // TTS_Kira: the forwarding-port listener runs ALWAYS so the GPS position
            // stays fresh from QGC's forward even when QGC owns the FC port. The
            // message log self-gates inside MonitorState.ingest() on `enabled`.
            if (port == fcPort) {
                closeMonitorSocket()
                loggedListening = false
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                continue
            }
            var sock = monitorSocket
            if (sock == null) {
                sock = try {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(port))
                        soTimeout = 1000
                    }
                } catch (_: Exception) { null }
                monitorSocket = sock
                if (sock == null) {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    continue
                }
                if (!loggedListening) {
                    log(getString(R.string.log_monitor_listening, port))
                    loggedListening = true
                }
            }
            try {
                sock.receive(pkt)
                val raw = buf.copyOf(pkt.length)
                try { MonitorState.ingest(raw) } catch (_: Exception) {}
                MavlinkHelper.parsePosition(raw)?.let { onDronePosition(it) }
            } catch (_: java.net.SocketTimeoutException) {
            } catch (_: Exception) {
                closeMonitorSocket()
            }
        }
        closeMonitorSocket()
    }

    private fun closeMonitorSocket() {
        try { monitorSocket?.close() } catch (_: Exception) {}
        monitorSocket = null
    }

    private fun runUdpLoop() {
        try {
            udpSocket = DatagramSocket(null)
            udpSocket!!.reuseAddress = true
            udpSocket!!.bind(InetSocketAddress(fcPort))
            udpSocket!!.soTimeout = 2000
            log(getString(R.string.log_udp_listening, fcPort))

            val buf = ByteArray(4096)
            val pkt = DatagramPacket(buf, buf.size)
            while (running.get()) {
                try {
                    udpSocket!!.receive(pkt)
                    val raw = buf.copyOf(pkt.length)

                    // Monitor tap only when it targets the FC port; otherwise the
                    // monitor uses its own dedicated socket in runMonitorLoop.
                    // No-op (single volatile read) unless explicitly enabled.
                    if (MonitorState.enabled && MonitorState.port == fcPort) {
                        try { MonitorState.ingest(raw) } catch (_: Exception) {}
                    }

                    val hb = MavlinkHelper.findHeartbeat(raw)
                    when {
                        hb != null && hb.source == MavlinkHelper.HeartbeatSource.VEHICLE -> {
                            if (!loggedVehicleHb) {
                                log(getString(R.string.log_drone_detected, hb.mavType, hb.autopilot, hb.mavlinkVersion))
                                loggedVehicleHb = true
                            }
                        }
                        hb != null && hb.source == MavlinkHelper.HeartbeatSource.GCS -> {
                            if (!loggedGcsHb) {
                                log(getString(R.string.log_gcs_heartbeat))
                                loggedGcsHb = true
                            }
                        }
                        MavlinkHelper.hasMavlinkFrames(raw) -> {
                            if (!loggedMavlinkNoHb) {
                                log(getString(R.string.log_mavlink_without_heartbeat))
                                loggedMavlinkNoHb = true
                            }
                        }
                    }

                    MavlinkHelper.parsePosition(raw)?.let { onDronePosition(it) }
                } catch (_: java.net.SocketTimeoutException) { /* normal */ }

                // Watchdog: if >10s without RTCM, mark sending as stopped
                val now = System.currentTimeMillis()
                if (lastRtcmMs > 0 &&
                    now - lastRtcmMs > rtcmTimeoutMs &&
                    checks.mavlinkSending == CheckState.OK) {
                    log(getString(R.string.log_no_recent_rtcm, rtcmTimeoutMs / 1000))
                    checks = checks.copy(mavlinkSending = CheckState.FAIL)
                    publishChecks()
                }
            }
        } catch (e: Exception) {
            if (running.get()) log(getString(R.string.log_udp_error, e.message ?: ""))
        }
    }

    private fun runNtripLoop() {
        try {
            val testAddr = InetAddress.getByName("8.8.8.8")
            if (!testAddr.isReachable(3000)) throw Exception("Not reachable")
            checks = checks.copy(internet = CheckState.OK)
            publishChecks()
            log(getString(R.string.log_internet_ok))
        } catch (e: Exception) {
            checks = checks.copy(internet = CheckState.FAIL)
            publishChecks()
            log(getString(R.string.log_no_internet, e.message ?: ""))
            stopBridge()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        Thread { fetchSourceTable() }.apply { isDaemon = true; start() }

        try {
            log(getString(R.string.log_connecting, ntripConfig.host, ntripConfig.port))
            ntripSocket = Socket(ntripConfig.host, ntripConfig.port)
            val ins: InputStream = ntripSocket!!.getInputStream()
            val outs: OutputStream = ntripSocket!!.getOutputStream()

            outs.write(buildNtripRequest().toByteArray(Charsets.US_ASCII))
            outs.flush()

            val responseBuf = StringBuilder()
            var b: Int
            while (ins.read().also { b = it } != -1) {
                responseBuf.append(b.toChar())
                if (responseBuf.endsWith("\r\n\r\n")) break
                if (responseBuf.length > 2048) break
            }
            val response = responseBuf.toString()
            if (!response.contains("200") && !response.contains("ICY 200")) {
                checks = checks.copy(ntripConnected = CheckState.FAIL)
                publishChecks()
                log(getString(R.string.log_caster_rejected, response.take(120)))
                stopBridge()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            checks = checks.copy(ntripConnected = CheckState.OK)
            publishChecks()
            log(getString(R.string.log_ntrip_connected))

            val ggaThread = Thread {
                while (running.get()) {
                    try {
                        val pos = dronePos
                        if (pos != null) {
                            val gga = MavlinkHelper.buildNmeaGga(pos)
                            outs.write(gga.toByteArray(Charsets.US_ASCII))
                            outs.flush()
                            log(getString(R.string.log_gga_sent, pos.latDeg, pos.lonDeg))
                        } else {
                            log(getString(R.string.log_gga_pending))
                        }
                        Thread.sleep(5000)
                    } catch (_: Exception) {}
                }
            }
            ggaThread.isDaemon = true
            ggaThread.start()

            val fcAddr = InetAddress.getByName(fcIp)
            val rtcmBuf = ByteArray(4096)
            while (running.get()) {
                val read = ins.read(rtcmBuf)
                if (read <= 0) break

                val chunk = rtcmBuf.copyOf(read)
                rtcmBytesTotal += read
                lastRtcmMs = System.currentTimeMillis()

                rtcmParser.feed(chunk) { msgNum, payload ->
                    if (msgNum == 1005 || msgNum == 1006) {
                        val bp = RtcmHelper.decodeStationaryRefPoint(payload)
                        if (bp != null) {
                            val newBase = baseLat == null
                            baseLat = bp.latDeg
                            baseLon = bp.lonDeg
                            if (newBase) {
                                log(getString(R.string.log_rtcm_base, msgNum, bp.latDeg, bp.lonDeg))
                            }
                        }
                    }
                }

                val msgs = MavlinkHelper.buildGpsRtcmMessages(chunk)
                for (msg in msgs) {
                    val pkt = DatagramPacket(msg, msg.size, fcAddr, fcPort)
                    udpSocket?.send(pkt)
                    mavMsgsTotal++
                }

                val pos = dronePos
                checks = checks.copy(
                    rtcmReceiving = CheckState.OK,
                    mavlinkSending = CheckState.OK,
                    rtcmKb = rtcmBytesTotal / 1024,
                    mavMsgs = mavMsgsTotal,
                    baseLat = baseLat,
                    baseLon = baseLon,
                    baseDistanceKm = pos?.let { computeBaseDistKm(it) }
                )
                publishChecks()
            }

            log(getString(R.string.log_ntrip_closed))
            checks = checks.copy(ntripConnected = CheckState.FAIL)
            publishChecks()
        } catch (e: Exception) {
            if (running.get()) {
                checks = checks.copy(ntripConnected = CheckState.FAIL)
                publishChecks()
                log(getString(R.string.log_ntrip_error, e.message ?: ""))
            }
        } finally {
            stopBridge()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fetchSourceTable() {
        val entries = SourceTable.fetch(
            ntripConfig.host, ntripConfig.port,
            ntripConfig.user, ntripConfig.pass
        )
        val info = entries.firstOrNull { it.name == ntripConfig.mountpoint }
        if (info != null) {
            centerLat = info.latDeg
            centerLon = info.lonDeg
            mountpointType = info.type
            log(getString(R.string.log_mountpoint_info, info.name, info.type, info.latDeg, info.lonDeg))
            checks = checks.copy(mountpointType = info.type)
            val pos = dronePos
            if (pos != null) {
                checks = checks.copy(centerDistanceKm = computeCenterDistKm(pos))
            }
            publishChecks()
        } else if (entries.isNotEmpty()) {
            log(getString(R.string.log_mountpoint_missing, ntripConfig.mountpoint, entries.size))
        }
    }

    private fun buildNtripRequest(): String {
        val auth = if (ntripConfig.user.isNotEmpty()) {
            val creds = "${ntripConfig.user}:${ntripConfig.pass}"
            val encoded = Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
            "Authorization: Basic $encoded\r\n"
        } else ""
        return "GET /${ntripConfig.mountpoint} HTTP/1.0\r\n" +
               "Host: ${ntripConfig.host}\r\n" +
               "Ntrip-Version: Ntrip/1.0\r\n" +
               "User-Agent: NTRIP MavlinkApp/1.0\r\n" +
               "Accept: */*\r\n" +
               auth +
               "Connection: close\r\n\r\n"
    }

    private fun computeBaseDistKm(pos: MavlinkHelper.DronePosition): Double? {
        val la = baseLat ?: return null
        val lo = baseLon ?: return null
        return MavlinkHelper.distanceKm(pos.latDeg, pos.lonDeg, la, lo)
    }

    private fun computeCenterDistKm(pos: MavlinkHelper.DronePosition): Double? {
        val la = centerLat ?: return null
        val lo = centerLon ?: return null
        return MavlinkHelper.distanceKm(pos.latDeg, pos.lonDeg, la, lo)
    }

    private fun publishChecks() {
        BridgeState.publishChecks(checks)
        // Green bubble if we have initial position and are sending RTCM right now
        val rtkOk = everHadPosition && checks.mavlinkSending == CheckState.OK
        showBubble(rtkOk)
        updateNotification()
    }

    private fun log(msg: String) {
        BridgeState.publishLog(msg)
    }
}
