package com.kira.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Names + best-effort decoders for the MAVLink monitor. Offsets follow the
 * MAVLink wire order (fields sorted by descending type size, ties by
 * declaration order; v2 extension fields appended last).
 */
object MsgCatalog {

    enum class Severity { OK, INFO, WARNING, CRITICAL }

    data class Decoded(val name: String, val detail: String, val severity: Severity)

    private val NAMES: Map<Int, String> = mapOf(
        0 to "HEARTBEAT", 1 to "SYS_STATUS", 2 to "SYSTEM_TIME", 4 to "PING",
        11 to "SET_MODE", 20 to "PARAM_REQUEST_READ", 21 to "PARAM_REQUEST_LIST",
        22 to "PARAM_VALUE", 23 to "PARAM_SET", 24 to "GPS_RAW_INT", 25 to "GPS_STATUS",
        27 to "RAW_IMU", 29 to "SCALED_PRESSURE", 30 to "ATTITUDE",
        32 to "LOCAL_POSITION_NED", 33 to "GLOBAL_POSITION_INT", 34 to "RC_CHANNELS_SCALED",
        35 to "RC_CHANNELS_RAW", 36 to "SERVO_OUTPUT_RAW", 39 to "MISSION_ITEM",
        40 to "MISSION_REQUEST", 41 to "MISSION_SET_CURRENT", 42 to "MISSION_CURRENT",
        44 to "MISSION_COUNT", 51 to "MISSION_REQUEST_INT", 62 to "NAV_CONTROLLER_OUTPUT",
        65 to "RC_CHANNELS", 66 to "REQUEST_DATA_STREAM", 69 to "MANUAL_CONTROL",
        70 to "RC_CHANNELS_OVERRIDE", 73 to "MISSION_ITEM_INT", 74 to "VFR_HUD",
        75 to "COMMAND_INT", 76 to "COMMAND_LONG", 77 to "COMMAND_ACK",
        81 to "MANUAL_SETPOINT", 83 to "ATTITUDE_TARGET",
        84 to "SET_POSITION_TARGET_LOCAL_NED", 86 to "SET_POSITION_TARGET_GLOBAL_INT",
        87 to "POSITION_TARGET_GLOBAL_INT", 105 to "HIGHRES_IMU", 109 to "RADIO_STATUS",
        111 to "TIMESYNC", 116 to "SCALED_IMU2", 124 to "GPS2_RAW", 125 to "POWER_STATUS",
        127 to "GPS_RTK", 128 to "GPS2_RTK", 129 to "SCALED_IMU3", 132 to "DISTANCE_SENSOR",
        136 to "TERRAIN_REPORT", 137 to "SCALED_PRESSURE2", 140 to "ACTUATOR_CONTROL_TARGET",
        141 to "ALTITUDE", 147 to "BATTERY_STATUS", 148 to "AUTOPILOT_VERSION",
        163 to "AHRS", 165 to "HWSTATUS", 168 to "WIND", 178 to "AHRS2",
        182 to "AHRS3", 193 to "EKF_STATUS_REPORT", 230 to "ESTIMATOR_STATUS",
        231 to "WIND_COV", 241 to "VIBRATION", 242 to "HOME_POSITION",
        245 to "EXTENDED_SYS_STATE", 253 to "STATUSTEXT", 259 to "CAMERA_INFORMATION",
        260 to "CAMERA_SETTINGS", 269 to "VIDEO_STREAM_INFORMATION",
    )

    fun nameOf(msgId: Int): String = NAMES[msgId] ?: "MSG_$msgId"

    fun decode(f: MavlinkScan.Frame): Decoded {
        val n = nameOf(f.msgId)
        return try {
            when (f.msgId) {
                0 -> heartbeat(f, n)
                1 -> sysStatus(f, n)
                11 -> setMode(f, n)
                24 -> gpsRawInt(f, n)
                75 -> commandInt(f, n)
                76 -> commandLong(f, n)
                77 -> commandAck(f, n)
                127 -> gpsRtk(f, n)
                147 -> batteryStatus(f, n)
                193 -> ekfStatus(f, n)
                241 -> vibration(f, n)
                253 -> statusText(f, n)
                else -> Decoded(n, "${f.payload.size}B", Severity.INFO)
            }
        } catch (_: Exception) {
            Decoded(n, "${f.payload.size}B (decode error)", Severity.INFO)
        }
    }

    private fun bb(p: ByteArray) = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
    private fun u8(p: ByteArray, o: Int) = if (o in p.indices) p[o].toInt() and 0xFF else 0
    private fun s8(p: ByteArray, o: Int) = if (o in p.indices) p[o].toInt() else 0
    private fun u16(p: ByteArray, o: Int) =
        if (o + 2 <= p.size) (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8) else 0
    private fun s16(p: ByteArray, o: Int): Int { val v = u16(p, o); return if (v and 0x8000 != 0) v - 0x10000 else v }
    private fun u32(p: ByteArray, o: Int) = if (o + 4 <= p.size) bb(p).getInt(o).toLong() and 0xFFFFFFFFL else 0L
    private fun f32(p: ByteArray, o: Int) = if (o + 4 <= p.size) bb(p).getFloat(o) else 0f

    private fun heartbeat(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        val customMode = u32(p, 0)
        val type = u8(p, 4); val autopilot = u8(p, 5); val systemStatus = u8(p, 7)
        val statusName = when (systemStatus) {
            0 -> "UNINIT"; 1 -> "BOOT"; 2 -> "CALIBRATING"; 3 -> "STANDBY"
            4 -> "ACTIVE"; 5 -> "CRITICAL"; 6 -> "EMERGENCY"; 7 -> "POWEROFF"; else -> "?"
        }
        val sev = when (systemStatus) { 5, 6 -> Severity.CRITICAL; 7 -> Severity.WARNING; else -> Severity.OK }
        val origin = if (type == 6) "GCS" else "VEHICLE"
        return Decoded(n, "$origin type=$type ap=$autopilot status=$statusName mode=$customMode", sev)
    }

    private fun sysStatus(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        val v = u16(p, 14); val i = s16(p, 16); val rem = s8(p, 30)
        val sev = when { rem in 0..10 -> Severity.CRITICAL; rem in 11..25 -> Severity.WARNING; else -> Severity.INFO }
        return Decoded(n, "V=${"%.2f".format(v / 1000.0)} I=${"%.1fA".format(i / 100.0)} bat=$rem%", sev)
    }

    private fun setMode(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        return Decoded(n, "tgt=${u8(p, 4)} base=0x${u8(p, 5).toString(16)} mode=${u32(p, 0)}", Severity.INFO)
    }

    private fun gpsRawInt(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        val fix = u8(p, 28); val sats = u8(p, 29)
        val fixName = when (fix) {
            0 -> "NO_GPS"; 1 -> "NO_FIX"; 2 -> "2D"; 3 -> "3D"; 4 -> "DGPS"
            5 -> "RTK_FLOAT"; 6 -> "RTK_FIXED"; 7 -> "STATIC"; 8 -> "PPP"; else -> "?"
        }
        val sev = if (fix <= 1) Severity.WARNING else Severity.OK
        return Decoded(n, "fix=$fixName sats=$sats", sev)
    }

    private fun commandLong(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        val cmd = u16(p, 28)
        return Decoded(n, "${MavCmd.name(cmd)} p1=${"%.2f".format(f32(p, 0))} p2=${"%.2f".format(f32(p, 4))}", Severity.INFO)
    }

    private fun commandInt(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        val cmd = u16(p, 28)
        return Decoded(n, "${MavCmd.name(cmd)} frame=${u8(p, 32)}", Severity.INFO)
    }

    private fun commandAck(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        val cmd = u16(p, 0); val result = u8(p, 2)
        val resultName = when (result) {
            0 -> "ACCEPTED"; 1 -> "TEMP_REJECTED"; 2 -> "DENIED"; 3 -> "UNSUPPORTED"
            4 -> "FAILED"; 5 -> "IN_PROGRESS"; 6 -> "CANCELLED"; else -> "result=$result"
        }
        val sev = if (result in 2..4) Severity.WARNING else Severity.OK
        return Decoded(n, "${MavCmd.name(cmd)} â†’ $resultName", sev)
    }

    private fun gpsRtk(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        return Decoded(n, "sats=${u8(p, 33)} health=${u8(p, 31)} rate=${u8(p, 32)}Hz", Severity.INFO)
    }

    private fun batteryStatus(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        val rem = s8(p, 35); val v0 = u16(p, 10)
        val sev = when { rem in 0..10 -> Severity.CRITICAL; rem in 11..25 -> Severity.WARNING; else -> Severity.INFO }
        return Decoded(n, "cell0=${"%.2fV".format(v0 / 1000.0)} bat=$rem%", sev)
    }

    private fun ekfStatus(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        val flags = u16(p, 20); val velVar = f32(p, 0); val posVar = f32(p, 4)
        val sev = if (velVar > 1.0f || posVar > 1.0f) Severity.WARNING else Severity.OK
        return Decoded(n, "flags=0x${flags.toString(16)} velVar=${"%.2f".format(velVar)} posVar=${"%.2f".format(posVar)}", sev)
    }

    private fun vibration(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        val vx = f32(p, 8); val vy = f32(p, 12); val vz = f32(p, 16)
        val sev = if (maxOf(vx, vy, vz) > 60f) Severity.WARNING else Severity.OK
        return Decoded(n, "x=${"%.1f".format(vx)} y=${"%.1f".format(vy)} z=${"%.1f".format(vz)}", sev)
    }

    private fun statusText(f: MavlinkScan.Frame, n: String): Decoded {
        val p = f.payload
        val severity = u8(p, 0)
        val textLen = (p.size - 1).coerceIn(0, 50)
        val text = if (textLen > 0) String(p, 1, textLen, Charsets.US_ASCII).trimEnd(' ', ' ', '\n', '\r') else ""
        val sevName = when (severity) {
            0 -> "EMERG"; 1 -> "ALERT"; 2 -> "CRIT"; 3 -> "ERR"
            4 -> "WARN"; 5 -> "NOTICE"; 6 -> "INFO"; 7 -> "DEBUG"; else -> "?"
        }
        val sev = when (severity) { 0, 1, 2 -> Severity.CRITICAL; 3, 4 -> Severity.WARNING; 5 -> Severity.INFO; else -> Severity.OK }
        return Decoded(n, "[$sevName] $text", sev)
    }
}
