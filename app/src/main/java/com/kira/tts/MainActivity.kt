package com.kira.tts

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialProber

class MainActivity : AppCompatActivity() {

    private lateinit var protocolGroup: RadioGroup
    private lateinit var protoUdp: RadioButton
    private lateinit var protoTcp: RadioButton
    private lateinit var protoSerial: RadioButton

    private lateinit var labelAddress: TextView
    private lateinit var etAddress: EditText
    private lateinit var etPort: EditText
    private lateinit var etBaud: EditText
    private lateinit var portRow: View
    private lateinit var baudCol: View

    private lateinit var filterCompanion: CheckBox
    private lateinit var filterBattery: CheckBox
    private lateinit var filterAlerts: CheckBox
    private lateinit var filterNav: CheckBox

    private lateinit var btnStart: Button
    private lateinit var btnOpenLog: Button

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) launchServiceForSelectedProtocol(skipUsbCheck = true)
                else Toast.makeText(this@MainActivity, R.string.toast_serial_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val NOTIFICATIONS_REQUEST = 2001
        private const val ACTION_USB_PERMISSION = "com.kira.tts.USB_PERMISSION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        protocolGroup = findViewById(R.id.protocolGroup)
        protoUdp = findViewById(R.id.protoUdp)
        protoTcp = findViewById(R.id.protoTcp)
        protoSerial = findViewById(R.id.protoSerial)
        labelAddress = findViewById(R.id.labelAddress)
        etAddress = findViewById(R.id.etAddress)
        etPort = findViewById(R.id.etPort)
        etBaud = findViewById(R.id.etBaud)
        portRow = findViewById(R.id.portRow)
        baudCol = findViewById(R.id.baudCol)
        filterCompanion = findViewById(R.id.filterCompanion)
        filterBattery = findViewById(R.id.filterBattery)
        filterAlerts = findViewById(R.id.filterAlerts)
        filterNav = findViewById(R.id.filterNav)
        btnStart = findViewById(R.id.btnStart)
        btnOpenLog = findViewById(R.id.btnOpenLog)

        protocolGroup.setOnCheckedChangeListener { _, _ -> refreshProtocolUi() }
        refreshProtocolUi()

        btnStart.setOnClickListener {
            if (SnifferService.isRunning) sendStop() else sendStart()
        }
        btnOpenLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        applyRunningUi(SnifferService.isRunning)
        requestNotificationsPermission()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        applyRunningUi(SnifferService.isRunning)
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun refreshProtocolUi() {
        when {
            protoUdp.isChecked -> {
                etAddress.hint = getString(R.string.hint_address_udp)
                if (etAddress.text.toString().isBlank()) etAddress.setText("0.0.0.0")
                etPort.setText(etPort.text.toString().ifBlank { "14550" })
                baudCol.visibility = View.GONE
                portRow.visibility = View.VISIBLE
            }
            protoTcp.isChecked -> {
                etAddress.hint = getString(R.string.hint_address_tcp)
                if (etAddress.text.toString() == "0.0.0.0") etAddress.setText("")
                baudCol.visibility = View.GONE
                portRow.visibility = View.VISIBLE
            }
            protoSerial.isChecked -> {
                baudCol.visibility = View.VISIBLE
                portRow.visibility = View.VISIBLE
            }
        }
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATIONS_REQUEST
                )
            }
        }
    }

    private fun sendStart() {
        val port = etPort.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, R.string.toast_invalid_port, Toast.LENGTH_SHORT).show()
            return
        }
        val address = etAddress.text.toString().trim()
        if (!protoSerial.isChecked && address.isEmpty()) {
            Toast.makeText(this, R.string.toast_invalid_address, Toast.LENGTH_SHORT).show()
            return
        }
        if (protoSerial.isChecked) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers.isEmpty()) {
                Toast.makeText(this, R.string.toast_no_serial_device, Toast.LENGTH_LONG).show()
                return
            }
            val device = drivers.first().device
            if (!usbManager.hasPermission(device)) {
                val pi = PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                usbManager.requestPermission(device, pi)
                return
            }
        }
        launchServiceForSelectedProtocol(skipUsbCheck = true)
    }

    private fun launchServiceForSelectedProtocol(skipUsbCheck: Boolean) {
        val protocol = when {
            protoTcp.isChecked -> SnifferService.PROTO_TCP
            protoSerial.isChecked -> SnifferService.PROTO_SERIAL
            else -> SnifferService.PROTO_UDP
        }
        val groups = mutableListOf<Int>()
        if (filterCompanion.isChecked) groups += MessageCatalog.Group.COMPANION.ordinal
        if (filterBattery.isChecked) groups += MessageCatalog.Group.BATTERY.ordinal
        if (filterAlerts.isChecked) groups += MessageCatalog.Group.ALERTS.ordinal
        if (filterNav.isChecked) groups += MessageCatalog.Group.NAV.ordinal

        val intent = Intent(this, SnifferService::class.java).apply {
            action = SnifferService.ACTION_START
            putExtra(SnifferService.EXTRA_PROTOCOL, protocol)
            putExtra(SnifferService.EXTRA_ADDRESS, etAddress.text.toString().trim())
            putExtra(SnifferService.EXTRA_PORT, etPort.text.toString().toIntOrNull() ?: 14550)
            putExtra(SnifferService.EXTRA_BAUD, etBaud.text.toString().toIntOrNull() ?: 57600)
            putExtra(SnifferService.EXTRA_GROUPS, groups.toIntArray())
        }
        ContextCompat.startForegroundService(this, intent)
        applyRunningUi(true)
        Toast.makeText(this, R.string.toast_listener_started, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, LogActivity::class.java))
    }

    private fun sendStop() {
        startService(Intent(this, SnifferService::class.java).setAction(SnifferService.ACTION_STOP))
        applyRunningUi(false)
        Toast.makeText(this, R.string.toast_listener_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun applyRunningUi(running: Boolean) {
        btnStart.text = getString(if (running) R.string.stop_listening else R.string.start_listening)
    }
}
