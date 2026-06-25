package com.kira.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.*

object MavlinkHelper {

    private const val MAVLINK_STX = 0xFE.toByte()
    private const val MAVLINK2_STX = 0xFD.toByte()
    private const val MSG_ID_HEARTBEAT = 0
    private const val MSG_ID_GLOBAL_POSITION_INT = 33
    private const val MSG_ID_GPS_RTCM_DATA = 233
    private const val MAX_RTCM_DATA = 180
    private const val MAV_TYPE_GCS = 6
    private const val CRC_EXTRA_HEARTBEAT = 50

    private val CRC_EXTRA = mapOf(
        MSG_ID_GLOBAL_POSITION_INT to 104,
        MSG_ID_GPS_RTCM_DATA to 35
    )

    enum class HeartbeatSource { VEHICLE, GCS }

    data class HeartbeatInfo(
        val source: HeartbeatSource,
        val mavType: Int,
        val autopilot: Int,
        val mavlinkVersion: Int
    )

    private var seq = 0

    data class DronePosition(
        val latDeg: Double,
        val lonDeg: Double,
        val altMsl: Double
    )

    fun parsePosition(data: ByteArray): DronePosition? {
        var i = 0
        var result: DronePosition? = null
        while (i < data.size) {
            val b = data[i]
            // ---- MAVLink 1 ----
            if (b == MAVLINK_STX && i + 8 <= data.size) {
                val len = data[i + 1].toInt() and 0xFF
                if (i + 6 + len + 2 > data.size) break
                val msgId = data[i + 5].toInt() and 0xFF
                if (msgId == MSG_ID_GLOBAL_POSITION_INT && len >= 16) {
                    result = decodeGlobalPositionInt(data, i + 6, len) ?: result
                }
                i += 6 + len + 2
                continue
            }
            // ---- MAVLink 2 ----
            if (b == MAVLINK2_STX && i + 12 <= data.size) {
                val len = data[i + 1].toInt() and 0xFF
                val incompat = data[i + 2].toInt() and 0xFF
                val msgId = (data[i + 7].toInt() and 0xFF) or
                            ((data[i + 8].toInt() and 0xFF) shl 8) or
                            ((data[i + 9].toInt() and 0xFF) shl 16)
                val signatureLen = if (incompat and 0x01 != 0) 13 else 0
                val totalSize = 10 + len + 2 + signatureLen
                if (i + totalSize > data.size) break
                if (msgId == MSG_ID_GLOBAL_POSITION_INT) {
                    result = decodeGlobalPositionInt(data, i + 10, len) ?: result
                }
                i += totalSize
                continue
            }
            i++
        }
        return result
    }

    private fun decodeGlobalPositionInt(data: ByteArray, payloadStart: Int, len: Int): DronePosition? {
        if (len < 16) return null
        // v2 puede recortar bytes finales a 0, pero los primeros 16 son time(4)+lat(4)+lon(4)+alt(4)
        val payload = data.copyOfRange(payloadStart, payloadStart + 16)
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        bb.getInt() // time_boot_ms
        val lat = bb.getInt()
        val lon = bb.getInt()
        val alt = bb.getInt()
        // Reject "no fix" / Null Island (0,0) and out-of-range values: a bogus
        // position would push a junk GGA to the caster and anchor the VRS base
        // in the wrong place.
        if (lat == 0 && lon == 0) return null
        val latDeg = lat / 1e7
        val lonDeg = lon / 1e7
        if (latDeg < -90.0 || latDeg > 90.0 || lonDeg < -180.0 || lonDeg > 180.0) return null
        return DronePosition(latDeg, lonDeg, alt / 1000.0)
    }

    fun hasMavlinkFrames(data: ByteArray): Boolean {
        for (i in data.indices) {
            if (data[i] == MAVLINK_STX || data[i] == MAVLINK2_STX) return true
        }
        return false
    }

    /**
     * Finds the first valid HEARTBEAT (msg id 0) in the buffer.
     * Supports MAVLink 1 (STX 0xFE) and MAVLink 2 (STX 0xFD).
     * Validates CRC16 with the message's CRC_EXTRA.
     * Returns null if no HEARTBEAT with a correct CRC is found.
     */
    fun findHeartbeat(data: ByteArray): HeartbeatInfo? {
        var i = 0
        while (i < data.size) {
            val b = data[i]
            // ---- MAVLink 1 ----
            if (b == MAVLINK_STX && i + 8 <= data.size) {
                val len = data[i + 1].toInt() and 0xFF
                if (i + 6 + len + 2 <= data.size) {
                    val msgId = data[i + 5].toInt() and 0xFF
                    if (msgId == MSG_ID_HEARTBEAT && len >= 9) {
                        val computed = crc16(data, i + 1, i + 5 + len, CRC_EXTRA_HEARTBEAT)
                        val frameCrc = (data[i + 6 + len].toInt() and 0xFF) or
                                       ((data[i + 7 + len].toInt() and 0xFF) shl 8)
                        if (computed == frameCrc) {
                            return readHeartbeat(data, i + 6, len)
                        }
                    }
                    i += 6 + len + 2
                    continue
                }
            }
            // ---- MAVLink 2 ----
            if (b == MAVLINK2_STX && i + 12 <= data.size) {
                val len = data[i + 1].toInt() and 0xFF
                val incompat = data[i + 2].toInt() and 0xFF
                val msgId = (data[i + 7].toInt() and 0xFF) or
                            ((data[i + 8].toInt() and 0xFF) shl 8) or
                            ((data[i + 9].toInt() and 0xFF) shl 16)
                val signatureLen = if (incompat and 0x01 != 0) 13 else 0
                val totalSize = 10 + len + 2 + signatureLen
                if (i + totalSize <= data.size) {
                    if (msgId == MSG_ID_HEARTBEAT) {
                        val computed = crc16(data, i + 1, i + 9 + len, CRC_EXTRA_HEARTBEAT)
                        val frameCrc = (data[i + 10 + len].toInt() and 0xFF) or
                                       ((data[i + 11 + len].toInt() and 0xFF) shl 8)
                        if (computed == frameCrc) {
                            return readHeartbeat(data, i + 10, len)
                        }
                    }
                    i += totalSize
                    continue
                }
            }
            i++
        }
        return null
    }

    /** Reads HEARTBEAT payload fields. v2 may truncate bytes to 0. */
    private fun readHeartbeat(data: ByteArray, payloadStart: Int, len: Int): HeartbeatInfo {
        val type      = if (len >= 5) data[payloadStart + 4].toInt() and 0xFF else 0
        val autopilot = if (len >= 6) data[payloadStart + 5].toInt() and 0xFF else 0
        val mavVer    = if (len >= 9) data[payloadStart + 8].toInt() and 0xFF else 0
        val src = if (type == MAV_TYPE_GCS) HeartbeatSource.GCS else HeartbeatSource.VEHICLE
        return HeartbeatInfo(src, type, autopilot, mavVer)
    }

    /**
     * GPS_RTCM_DATA: payload = 1 byte flags + 1 byte data_len + N bytes data (N <= 180)
     * Total payload size = 2 + N (variable, not fixed)
     */
    fun buildGpsRtcmMessages(rtcmData: ByteArray, sysId: Int = 255, compId: Int = 190): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        val chunks = rtcmData.toList().chunked(MAX_RTCM_DATA)
        val multiFragment = chunks.size > 1

        chunks.forEachIndexed { index, chunk ->
            val flags = if (multiFragment) ((index and 0x3) shl 1) or 0x01 else 0x00
            // Exact-size payload: 2 header bytes + chunk.size data bytes
            val payload = ByteArray(2 + chunk.size)
            payload[0] = flags.toByte()
            payload[1] = chunk.size.toByte()
            chunk.forEachIndexed { j, b -> payload[2 + j] = b }
            messages.add(buildFrame(MSG_ID_GPS_RTCM_DATA, payload, sysId, compId))
        }
        return messages
    }

    private fun buildFrame(msgId: Int, payload: ByteArray, sysId: Int, compId: Int): ByteArray {
        val len = payload.size
        val frame = ByteArray(6 + len + 2)
        frame[0] = MAVLINK_STX
        frame[1] = len.toByte()
        frame[2] = (seq++ and 0xFF).toByte()
        frame[3] = sysId.toByte()
        frame[4] = compId.toByte()
        frame[5] = msgId.toByte()
        payload.copyInto(frame, 6)
        val crc = crc16(frame, 1, 5 + len, CRC_EXTRA[msgId] ?: 0)
        frame[6 + len] = (crc and 0xFF).toByte()
        frame[7 + len] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }

    internal fun crc16(data: ByteArray, start: Int, end: Int, extra: Int): Int {
        var crc = 0xFFFF
        for (i in start..end) {
            val b = data[i].toInt() and 0xFF
            var tmp = b xor (crc and 0xFF)
            tmp = tmp xor (tmp shl 4) and 0xFF
            crc = (crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)
        }
        val tmp2 = extra xor (crc and 0xFF)
        val tmp3 = tmp2 xor (tmp2 shl 4) and 0xFF
        crc = (crc shr 8) xor (tmp3 shl 8) xor (tmp3 shl 3) xor (tmp3 shr 4)
        return crc and 0xFFFF
    }

    fun buildNmeaGga(pos: DronePosition): String {
        val latAbs = abs(pos.latDeg)
        val latDeg = latAbs.toInt()
        val latMin = (latAbs - latDeg) * 60.0
        val latHem = if (pos.latDeg >= 0) "N" else "S"
        val lonAbs = abs(pos.lonDeg)
        val lonDeg = lonAbs.toInt()
        val lonMin = (lonAbs - lonDeg) * 60.0
        val lonHem = if (pos.lonDeg >= 0) "E" else "W"
        val sentence = String.format(
            Locale.US,
            "\$GPGGA,120000.00,%02d%07.4f,%s,%03d%07.4f,%s,1,08,1.0,%.1f,M,0.0,M,,",
            latDeg, latMin, latHem, lonDeg, lonMin, lonHem, pos.altMsl
        )
        val checksum = sentence.drop(1).fold(0) { acc, c -> acc xor c.code }
        return "$sentence*${checksum.toString(16).uppercase().padStart(2, '0')}\r\n"
    }

    /** Distancia en km entre dos puntos (Haversine) */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
