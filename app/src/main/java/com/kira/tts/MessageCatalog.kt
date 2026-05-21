package com.kira.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MessageCatalog {

    enum class Group { COMPANION, BATTERY, ALERTS, NAV, OTHER }

    enum class Severity { OK, INFO, WARNING, CRITICAL }

    data class Decoded(
        val name: String,
        val detail: String,
        val severity: Severity,
        val group: Group,
    )

    private val NAMES: Map<Int, String> = mapOf(
        0 to "HEARTBEAT",
        1 to "SYS_STATUS",
        11 to "SET_MODE",
        21 to "PARAM_REQUEST_LIST",
        22 to "PARAM_VALUE",
        23 to "PARAM_SET",
        24 to "GPS_RAW_INT",
        30 to "ATTITUDE",
        33 to "GLOBAL_POSITION_INT",
        35 to "RC_CHANNELS_RAW",
        39 to "MISSION_ITEM",
        41 to "MISSION_SET_CURRENT",
        65 to "RC_CHANNELS",
        69 to "MANUAL_CONTROL",
        70 to "RC_CHANNELS_OVERRIDE",
        73 to "MISSION_ITEM_INT",
        74 to "VFR_HUD",
        75 to "COMMAND_INT",
        76 to "COMMAND_LONG",
        77 to "COMMAND_ACK",
        83 to "ATTITUDE_TARGET",
        84 to "SET_POSITION_TARGET_LOCAL_NED",
        86 to "SET_POSITION_TARGET_GLOBAL_INT",
        87 to "POSITION_TARGET_GLOBAL_INT",
        105 to "HIGHRES_IMU",
        109 to "RADIO_STATUS",
        111 to "TIMESYNC",
        124 to "GPS2_RAW",
        125 to "POWER_STATUS",
        127 to "GPS_RTK",
        128 to "GPS2_RTK",
        132 to "DISTANCE_SENSOR",
        140 to "ACTUATOR_CONTROL_TARGET",
        147 to "BATTERY_STATUS",
        148 to "AUTOPILOT_VERSION",
        163 to "AHRS",
        165 to "HWSTATUS",
        178 to "AHRS2",
        193 to "EKF_STATUS_REPORT",
        225 to "EFI_STATUS",
        230 to "ESTIMATOR_STATUS",
        241 to "VIBRATION",
        242 to "HOME_POSITION",
        245 to "EXTENDED_SYS_STATE",
        253 to "STATUSTEXT",
        259 to "CAMERA_INFORMATION",
        260 to "CAMERA_SETTINGS",
    )

    private val GROUPS: Map<Int, Group> = mapOf(
        11 to Group.COMPANION,
        39 to Group.COMPANION,
        41 to Group.COMPANION,
        69 to Group.COMPANION,
        70 to Group.COMPANION,
        73 to Group.COMPANION,
        75 to Group.COMPANION,
        76 to Group.COMPANION,
        77 to Group.COMPANION,
        84 to Group.COMPANION,
        86 to Group.COMPANION,
        1 to Group.BATTERY,
        125 to Group.BATTERY,
        147 to Group.BATTERY,
        253 to Group.ALERTS,
        0 to Group.NAV,
        24 to Group.NAV,
        124 to Group.NAV,
        127 to Group.NAV,
        128 to Group.NAV,
        132 to Group.NAV,
        148 to Group.NAV,
        193 to Group.NAV,
        230 to Group.NAV,
        241 to Group.NAV,
        245 to Group.NAV,
    )

    fun groupOf(msgId: Int): Group = GROUPS[msgId] ?: Group.OTHER

    fun decode(frame: MavlinkParser.Frame): Decoded {
        val name = NAMES[frame.msgId] ?: "MSG_${frame.msgId}"
        val group = groupOf(frame.msgId)
        return when (frame.msgId) {
            0 -> decodeHeartbeat(frame, name, group)
            1 -> decodeSysStatus(frame, name, group)
            11 -> decodeSetMode(frame, name, group)
            24 -> decodeGpsRawInt(frame, name, group)
            76 -> decodeCommandLong(frame, name, group)
            75 -> decodeCommandInt(frame, name, group)
            77 -> decodeCommandAck(frame, name, group)
            127 -> decodeGpsRtk(frame, name, group)
            147 -> decodeBatteryStatus(frame, name, group)
            193 -> decodeEkfStatus(frame, name, group)
            241 -> decodeVibration(frame, name, group)
            253 -> decodeStatusText(frame, name, group)
            else -> Decoded(name, "${frame.payload.size}B payload", Severity.INFO, group)
        }
    }

    private fun bb(p: ByteArray) = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)

    private fun u8(p: ByteArray, off: Int): Int =
        if (off < p.size) p[off].toInt() and 0xFF else 0

    private fun u16(p: ByteArray, off: Int): Int =
        if (off + 2 <= p.size) (p[off].toInt() and 0xFF) or ((p[off + 1].toInt() and 0xFF) shl 8) else 0

    private fun s16(p: ByteArray, off: Int): Int {
        val v = u16(p, off)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    private fun u32(p: ByteArray, off: Int): Long =
        if (off + 4 <= p.size) bb(p).getInt(off).toLong() and 0xFFFFFFFFL else 0L

    private fun f32(p: ByteArray, off: Int): Float =
        if (off + 4 <= p.size) bb(p).getFloat(off) else 0f

    // ---- decoders ----

    private fun decodeHeartbeat(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val customMode = u32(f.payload, 0)
        val type = u8(f.payload, 4)
        val autopilot = u8(f.payload, 5)
        val baseMode = u8(f.payload, 6)
        val systemStatus = u8(f.payload, 7)
        val sev = when (systemStatus) {
            in 5..6 -> Severity.CRITICAL  // CRITICAL, EMERGENCY
            7 -> Severity.WARNING          // POWEROFF
            else -> Severity.OK
        }
        val statusName = when (systemStatus) {
            0 -> "UNINIT"; 1 -> "BOOT"; 2 -> "CALIBRATING"; 3 -> "STANDBY"
            4 -> "ACTIVE"; 5 -> "CRITICAL"; 6 -> "EMERGENCY"; 7 -> "POWEROFF"
            else -> "?"
        }
        return Decoded(n, "type=$type ap=$autopilot mode=$customMode status=$statusName base=0x${baseMode.toString(16)}", sev, g)
    }

    private fun decodeSysStatus(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val voltageMv = u16(f.payload, 14)
        val currentCa = s16(f.payload, 16)
        val remaining = if (f.payload.size > 30) f.payload[30].toInt() else -1
        val sev = when {
            remaining in 0..10 -> Severity.CRITICAL
            remaining in 11..25 -> Severity.WARNING
            else -> Severity.INFO
        }
        return Decoded(n, "V=${"%.2f".format(voltageMv / 1000.0)} I=${"%.1fA".format(currentCa / 100.0)} bat=${remaining}%", sev, g)
    }

    private fun decodeSetMode(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val customMode = u32(f.payload, 0)
        val target = u8(f.payload, 4)
        val baseMode = u8(f.payload, 5)
        return Decoded(n, "tgt=$target base=0x${baseMode.toString(16)} mode=$customMode", Severity.INFO, g)
    }

    private fun decodeCommandLong(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val cmd = u16(f.payload, 28)
        val p1 = f32(f.payload, 0)
        val p2 = f32(f.payload, 4)
        val cmdName = MavCmd.name(cmd)
        return Decoded(n, "$cmdName p1=${"%.2f".format(p1)} p2=${"%.2f".format(p2)}", Severity.INFO, g)
    }

    private fun decodeCommandInt(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val cmd = u16(f.payload, 8)
        val cmdName = MavCmd.name(cmd)
        return Decoded(n, "$cmdName frame=${u8(f.payload, 6)}", Severity.INFO, g)
    }

    private fun decodeCommandAck(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val cmd = u16(f.payload, 0)
        val result = u8(f.payload, 2)
        val resultName = when (result) {
            0 -> "ACCEPTED"; 1 -> "TEMP_REJECTED"; 2 -> "DENIED"; 3 -> "UNSUPPORTED"
            4 -> "FAILED"; 5 -> "IN_PROGRESS"; 6 -> "CANCELLED"
            else -> "result=$result"
        }
        val sev = if (result in 2..4) Severity.WARNING else Severity.OK
        return Decoded(n, "${MavCmd.name(cmd)} → $resultName", sev, g)
    }

    private fun decodeGpsRawInt(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val fixType = u8(f.payload, 28)
        val satellites = u8(f.payload, 29)
        val fixName = when (fixType) {
            0 -> "NO_GPS"; 1 -> "NO_FIX"; 2 -> "2D"; 3 -> "3D"
            4 -> "DGPS"; 5 -> "RTK_FLOAT"; 6 -> "RTK_FIXED"; 7 -> "STATIC"
            8 -> "PPP"; else -> "?"
        }
        val sev = if (fixType <= 1) Severity.WARNING else Severity.OK
        return Decoded(n, "fix=$fixName sats=$satellites", sev, g)
    }

    private fun decodeGpsRtk(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val flags = u8(f.payload, 24)
        val nsats = u8(f.payload, 25)
        return Decoded(n, "flags=0x${flags.toString(16)} sats=$nsats", Severity.INFO, g)
    }

    private fun decodeBatteryStatus(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val remaining = if (f.payload.size > 35) f.payload[35].toInt() else -1
        val v0 = u16(f.payload, 10)
        val sev = when {
            remaining in 0..10 -> Severity.CRITICAL
            remaining in 11..25 -> Severity.WARNING
            else -> Severity.INFO
        }
        return Decoded(n, "cell0=${"%.2fV".format(v0 / 1000.0)} bat=${remaining}%", sev, g)
    }

    private fun decodeEkfStatus(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val flags = u16(f.payload, 16)
        val velVar = f32(f.payload, 0)
        val posVar = f32(f.payload, 4)
        val sev = if (velVar > 1.0f || posVar > 1.0f) Severity.WARNING else Severity.OK
        return Decoded(n, "flags=0x${flags.toString(16)} velVar=${"%.2f".format(velVar)} posVar=${"%.2f".format(posVar)}", sev, g)
    }

    private fun decodeVibration(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val vx = f32(f.payload, 8)
        val vy = f32(f.payload, 12)
        val vz = f32(f.payload, 16)
        val maxV = maxOf(vx, vy, vz)
        val sev = if (maxV > 60f) Severity.WARNING else Severity.OK
        return Decoded(n, "x=${"%.1f".format(vx)} y=${"%.1f".format(vy)} z=${"%.1f".format(vz)}", sev, g)
    }

    private fun decodeStatusText(f: MavlinkParser.Frame, n: String, g: Group): Decoded {
        val severity = u8(f.payload, 0)
        val text = String(f.payload, 1, minOf(50, f.payload.size - 1), Charsets.US_ASCII)
            .trimEnd(' ', ' ', '\n', '\r')
        val sev = when (severity) {
            0, 1, 2 -> Severity.CRITICAL  // EMERGENCY, ALERT, CRITICAL
            3 -> Severity.WARNING          // ERROR
            4 -> Severity.WARNING          // WARNING
            5 -> Severity.INFO             // NOTICE
            else -> Severity.OK
        }
        val sevName = when (severity) {
            0 -> "EMERG"; 1 -> "ALERT"; 2 -> "CRIT"; 3 -> "ERR"
            4 -> "WARN"; 5 -> "NOTICE"; 6 -> "INFO"; 7 -> "DEBUG"
            else -> "?"
        }
        return Decoded(n, "[$sevName] $text", sev, g)
    }
}
