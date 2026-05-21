package com.kira.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SnifferService : Service() {

    companion object {
        const val ACTION_START = "com.kira.tts.START"
        const val ACTION_STOP = "com.kira.tts.STOP"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_PORT = "port"
        const val EXTRA_BAUD = "baud"
        const val EXTRA_GROUPS = "groups"

        const val PROTO_UDP = "UDP"
        const val PROTO_TCP = "TCP"
        const val PROTO_SERIAL = "SERIAL"

        private const val CHANNEL_ID = "tts_kira_sniffer"
        private const val NOTIF_ID = 4501

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var lastProtocol: String = ""
            private set
        val frameCount = AtomicLong(0L)
    }

    private val running = AtomicBoolean(false)
    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null
    private var serialPort: UsbSerialPort? = null
    private var workerThread: Thread? = null

    private var enabledGroups: Set<MessageCatalog.Group> = setOf(
        MessageCatalog.Group.COMPANION,
        MessageCatalog.Group.BATTERY,
        MessageCatalog.Group.ALERTS,
        MessageCatalog.Group.NAV,
    )

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSniff()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (running.get()) return START_STICKY
                val protocol = intent.getStringExtra(EXTRA_PROTOCOL) ?: PROTO_UDP
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: "0.0.0.0"
                val port = intent.getIntExtra(EXTRA_PORT, 14550)
                val baud = intent.getIntExtra(EXTRA_BAUD, 57600)
                val groupBits = intent.getIntArrayExtra(EXTRA_GROUPS) ?: intArrayOf(0, 1, 2, 3)
                enabledGroups = groupBits.map { MessageCatalog.Group.values()[it] }.toSet() +
                        MessageCatalog.Group.OTHER

                lastProtocol = "$protocol $address:$port"
                frameCount.set(0L)
                isRunning = true
                running.set(true)
                startForeground(NOTIF_ID, buildNotification())

                workerThread = Thread {
                    when (protocol) {
                        PROTO_UDP -> runUdp(address, port)
                        PROTO_TCP -> runTcp(address, port)
                        PROTO_SERIAL -> runSerial(baud)
                    }
                }.apply { isDaemon = true; start() }
                return START_STICKY
            }
            else -> {
                stopSelf(); return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopSniff()
        isRunning = false
        super.onDestroy()
    }

    private fun stopSniff() {
        running.set(false)
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        try { tcpSocket?.close() } catch (_: Exception) {}
        tcpSocket = null
        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null
    }

    // --- Readers ---------------------------------------------------------

    private fun runUdp(bind: String, port: Int) {
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(bind, port))
                soTimeout = 2000
            }
            udpSocket = sock
            val parser = MavlinkParser.StreamParser()
            val buf = ByteArray(4096)
            val pkt = DatagramPacket(buf, buf.size)
            while (running.get()) {
                try {
                    sock.receive(pkt)
                    val chunk = buf.copyOf(pkt.length)
                    ingest(parser.feed(chunk))
                } catch (_: java.net.SocketTimeoutException) {}
            }
        } catch (_: Exception) {
            if (running.get()) selfStop()
        }
    }

    private fun runTcp(host: String, port: Int) {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), 5000)
            sock.soTimeout = 2000
            tcpSocket = sock
            val ins = sock.getInputStream()
            val parser = MavlinkParser.StreamParser()
            val buf = ByteArray(4096)
            while (running.get()) {
                try {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    ingest(parser.feed(buf.copyOf(n)))
                } catch (_: java.net.SocketTimeoutException) {}
            }
        } catch (_: Exception) {
            if (running.get()) selfStop()
        }
    }

    private fun runSerial(baud: Int) {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers.isEmpty()) { selfStop(); return }
            val driver = drivers.first()
            val connection = usbManager.openDevice(driver.device) ?: run { selfStop(); return }
            val port = driver.ports.first()
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort = port
            val parser = MavlinkParser.StreamParser()
            val buf = ByteArray(4096)
            while (running.get()) {
                try {
                    val n = port.read(buf, 1000)
                    if (n > 0) ingest(parser.feed(buf.copyOf(n)))
                } catch (_: Exception) { if (!running.get()) break }
            }
        } catch (_: Exception) {
            if (running.get()) selfStop()
        }
    }

    private fun ingest(frames: List<MavlinkParser.Frame>) {
        if (frames.isEmpty()) return
        for (f in frames) {
            val decoded = MessageCatalog.decode(f)
            if (decoded.group !in enabledGroups) continue
            LogStore.add(LogStore.Entry(System.currentTimeMillis(), f, decoded))
            frameCount.incrementAndGet()
        }
        updateNotification()
    }

    private fun selfStop() {
        stopSniff()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Notification ----------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SnifferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_listening, lastProtocol, frameCount.get()))
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop_action), stop)
            .build()
    }

    private fun updateNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {}
    }
}
