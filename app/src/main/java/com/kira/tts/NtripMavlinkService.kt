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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ln

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

        // Bubble tuning
        private const val GPS_STALE_MS = 5000L   // fix/h_acc go grey if the forward stops
        private const val AGE_RED_S = 12         // #233 age that saturates to red
        private const val FORWARD_STALE_MS = 3000L // link dot turns solid red after this with no telemetry
        private const val ARROW_UP = "\u2191"           // up arrow
        private const val TARGET = "\uD83C\uDFAF"       // bullseye emoji
        private const val DASH = "\u2014"               // em dash

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
    private var bubbleRoot: LinearLayout? = null
    private var tvState: TextView? = null
    private var tvUp: TextView? = null
    private var tvAcc: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    @Volatile private var bubbleDismissed = false
    private var bubbleAdded = false

    // Live telemetry powering the bubble (all fed from the forwarded stream).
    @Volatile private var lastRtcmSentMs = 0L   // last GPS_RTCM_DATA #233 pushed to the drone
    @Volatile private var lastGpsRawMs = 0L     // last GPS_RAW_INT seen (staleness guard)
    @Volatile private var fixType = 0           // GPS_RAW_INT.fix_type
    @Volatile private var hAccMm: Long? = null  // GPS_RAW_INT.h_acc (mm), null if not reported
    @Volatile private var lastForwardMs = 0L    // last packet on the forward/FC socket (link dot)

    private val colorMuted = 0xFF9CA3AF.toInt()
    private val bubbleBg by lazy { GradientDrawable().apply { cornerRadius = dp(14).toFloat() } }
    private var dot: View? = null
    private var blinkOn = true
    private val dotBg by lazy {
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), 0xCCFFFFFF.toInt()) }
    }
    private val bubbleTick = object : Runnable {
        override fun run() {
            renderBubble()
            if (running.get()) mainHandler.postDelayed(this, 500L)  // 500ms \u2192 ~1 Hz dot blink
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
                showBubble()
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
        mainHandler.removeCallbacks(bubbleTick)
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

    private fun showBubble() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showBubble() }
            return
        }
        if (!Settings.canDrawOverlays(this)) return
        if (bubbleDismissed) return

        val view = ensureBubbleView()

        // Attach only if not already attached. The view is reused for the whole
        // service lifetime, so addView can never run twice â†’ a single bubble.
        if (!bubbleAdded) {
            view.alpha = 1f
            try {
                windowManager?.addView(view, bubbleParams)
            } catch (_: Exception) { /* already added; ignore */ }
            bubbleAdded = true
        }
        renderBubble()
        // Single self-rescheduling ticker so the "seconds since" counters keep
        // climbing even when no packets arrive (that is the whole point).
        mainHandler.removeCallbacks(bubbleTick)
        mainHandler.post(bubbleTick)
    }

    /**
     * Repaints the three bubble readouts from live telemetry:
     *  left   : RTK state from GPS_RAW_INT.fix_type, tinting the whole bubble.
     *  up     : seconds since the last GPS_RTCM_DATA #233 (4G/caster thermometer).
     *  target : h_acc positioning quality. Up/target grade green->red on their own.
     * fix_type / h_acc go grey when the forwarded stream stops (staleness guard).
     */
    private fun renderBubble() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { renderBubble() }
            return
        }
        if (!bubbleAdded) return
        val now = System.currentTimeMillis()
        val gpsFresh = lastGpsRawMs > 0 && (now - lastGpsRawMs) <= GPS_STALE_MS
        val fix = if (gpsFresh) fixType else -1

        tvState?.text = fixLabel(fix)
        bubbleBg.setColor(fixColor(fix))

        if (lastRtcmSentMs > 0) {
            val sec = ((now - lastRtcmSentMs) / 1000L).toInt()
            tvUp?.text = "$ARROW_UP ${sec}s"
            tvUp?.setTextColor(ageColor(sec))
        } else {
            tvUp?.text = "$ARROW_UP $DASH"
            tvUp?.setTextColor(colorMuted)
        }

        // No icon: the "cm"/"m" unit already reads as positioning accuracy.
        val acc = if (gpsFresh) hAccMm else null
        if (acc != null) {
            tvAcc?.text = formatAcc(acc)
            tvAcc?.setTextColor(accColor(acc))
        } else {
            tvAcc?.text = DASH
            tvAcc?.setTextColor(colorMuted)
        }

        // Forward-link LED: high-contrast blink (bright green <-> dark) while
        // telemetry flows; solid red when nothing has arrived for FORWARD_STALE_MS.
        val linkFresh = lastForwardMs > 0 && (now - lastForwardMs) <= FORWARD_STALE_MS
        blinkOn = !blinkOn
        dotBg.setColor(
            when {
                !linkFresh -> 0xFFEF4444.toInt()  // solid red = link lost
                blinkOn    -> 0xFF4ADE80.toInt()  // LED on  = bright green
                else       -> 0xFF14532D.toInt()  // LED off = dark green
            }
        )
        dot?.alpha = 1f
    }

    private fun fixLabel(fix: Int): String = when (fix) {
        6 -> "RTK FIX"
        5 -> "RTK FLOAT"
        4 -> "DGPS"
        3 -> "3D"
        2 -> "2D"
        0, 1 -> "NO FIX"
        else -> DASH
    }

    private fun fixColor(fix: Int): Int = when {
        fix == 6 -> 0xFF22C55E.toInt()    // RTK Fixed - green
        fix == 5 -> 0xFFF59E0B.toInt()    // RTK Float - amber
        fix == 4 -> 0xFFF97316.toInt()    // DGPS - orange
        fix in 0..3 -> 0xFFEF4444.toInt() // 2D/3D/no fix - red
        else -> 0xFF6B7280.toInt()        // stale / unknown - grey
    }

    /** Seconds since last #233: green at 0 s, graded to red at AGE_RED_S. */
    private fun ageColor(sec: Int): Int =
        gradeColor((sec.toFloat() / AGE_RED_S).coerceIn(0f, 1f))

    /** h_acc: green at <=3 cm, graded on a log scale to red at >=1 m. */
    private fun accColor(mm: Long): Int {
        val meters = (mm / 1000.0).coerceAtLeast(0.001)
        val t = ((ln(meters) - ln(0.03)) / (ln(1.0) - ln(0.03))).coerceIn(0.0, 1.0)
        return gradeColor(t.toFloat())
    }

    /** Linear green -> amber -> red gradient for t in [0,1]. */
    private fun gradeColor(t: Float): Int {
        val green = intArrayOf(0x22, 0xC5, 0x5E)
        val amber = intArrayOf(0xF5, 0x9E, 0x0B)
        val red   = intArrayOf(0xEF, 0x44, 0x44)
        val a: IntArray; val b: IntArray; val u: Float
        if (t < 0.5f) { a = green; b = amber; u = t / 0.5f }
        else { a = amber; b = red; u = (t - 0.5f) / 0.5f }
        val r  = (a[0] + (b[0] - a[0]) * u).toInt()
        val g  = (a[1] + (b[1] - a[1]) * u).toInt()
        val bl = (a[2] + (b[2] - a[2]) * u).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun formatAcc(mm: Long): String {
        val meters = mm / 1000.0
        return when {
            meters < 0.10 -> String.format(Locale.US, "%.1f cm", meters * 100)
            meters < 1.0  -> String.format(Locale.US, "%.0f cm", meters * 100)
            else          -> String.format(Locale.US, "%.2f m", meters)
        }
    }

    /** Lazily builds the single, reusable 3-readout bubble (reused for the lifetime). */
    private fun ensureBubbleView(): LinearLayout {
        bubbleRoot?.let { return it }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val state = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.02f
            setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
        }
        val dotView = View(this).apply { background = dotBg }
        // LED sits centered UNDER the RTK state so it costs no horizontal room.
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(state, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(dotView, LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                topMargin = dp(3)
            })
        }
        val up = TextView(this).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
        }
        val acc = TextView(this).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
        }
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(0x59000000)   // translucent dark chip so tinted text stays legible
            }
            addView(up)
            addView(acc)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(3), dp(7), dp(3))
            elevation = 12f
            background = bubbleBg
            addView(leftCol, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(rightCol, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6); gravity = Gravity.CENTER_VERTICAL })
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
        attachDragHandler(root, params)
        bubbleRoot = root
        dot = dotView
        tvState = state
        tvUp = up
        tvAcc = acc
        bubbleParams = params
        return root
    }

    private fun attachDragHandler(view: View, params: WindowManager.LayoutParams) {
        val screenW = resources.displayMetrics.widthPixels
        val dismissTop = dp(110)       // top band that triggers removal
        val dismissRadiusX = dp(130)   // horizontal tolerance around screen center
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
                        // Dropping into the dismiss zone fully stops the service
                        // (RTK bridge + notification + process), not just hides the bubble.
                        view.post { stopEverything() }
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
        val view = bubbleRoot ?: return
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

    /** Full teardown: stop the bridge, drop the notification, kill the service. */
    private fun stopEverything() {
        stopBridge()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    /** Updates fix_type + h_acc from the forwarded GPS_RAW_INT (#24). */
    private fun onGpsRaw(g: MavlinkHelper.GpsRaw) {
        lastGpsRawMs = System.currentTimeMillis()
        fixType = g.fixType
        if (g.hAccMm != null) hAccMm = g.hAccMm
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
                lastForwardMs = System.currentTimeMillis()
                val raw = buf.copyOf(pkt.length)
                try { MonitorState.ingest(raw) } catch (_: Exception) {}
                MavlinkHelper.parsePosition(raw)?.let { onDronePosition(it) }
                MavlinkHelper.parseGpsRaw(raw)?.let { onGpsRaw(it) }
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
                    lastForwardMs = System.currentTimeMillis()
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
                    MavlinkHelper.parseGpsRaw(raw)?.let { onGpsRaw(it) }
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
                if (msgs.isNotEmpty()) lastRtcmSentMs = System.currentTimeMillis()

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
        showBubble()
        updateNotification()
    }

    private fun log(msg: String) {
        BridgeState.publishLog(msg)
    }
}
