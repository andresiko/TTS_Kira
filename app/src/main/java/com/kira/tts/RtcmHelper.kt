package com.kira.tts

import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object RtcmHelper {

    data class BasePosition(
        val msgType: Int,
        val latDeg: Double,
        val lonDeg: Double,
        val altM: Double
    )

    /** Parser de stream RTCM3: acumula bytes y emite mensajes completos. */
    class StreamParser {
        private val acc = ByteArrayOutputStream()

        fun feed(data: ByteArray, onMessage: (msgNum: Int, payload: ByteArray) -> Unit) {
            acc.write(data)
            val bytes = acc.toByteArray()
            var i = 0
            var consumed = 0
            while (i < bytes.size) {
                if ((bytes[i].toInt() and 0xFF) != 0xD3) { i++; continue }
                if (i + 3 > bytes.size) break
                val len = ((bytes[i + 1].toInt() and 0x03) shl 8) or (bytes[i + 2].toInt() and 0xFF)
                val frameSize = 3 + len + 3
                if (i + frameSize > bytes.size) break
                if (len >= 2) {
                    val payload = bytes.copyOfRange(i + 3, i + 3 + len)
                    val msgNum = ((payload[0].toInt() and 0xFF) shl 4) or
                                 ((payload[1].toInt() and 0xF0) ushr 4)
                    onMessage(msgNum, payload)
                }
                i += frameSize
                consumed = i
            }
            acc.reset()
            if (consumed < bytes.size) acc.write(bytes, consumed, bytes.size - consumed)
        }
    }

    /** Decodifica 1005 o 1006 a lat/lon/alt WGS84. */
    fun decodeStationaryRefPoint(payload: ByteArray): BasePosition? {
        if (payload.size < 19) return null
        val msgNum = readBits(payload, 0, 12).toInt()
        if (msgNum != 1005 && msgNum != 1006) return null
        val xRaw = readSignedBits(payload, 34, 38)
        val yRaw = readSignedBits(payload, 74, 38)
        val zRaw = readSignedBits(payload, 114, 38)
        val x = xRaw * 0.0001
        val y = yRaw * 0.0001
        val z = zRaw * 0.0001
        val (lat, lon, alt) = ecefToGeodetic(x, y, z)
        return BasePosition(msgNum, Math.toDegrees(lat), Math.toDegrees(lon), alt)
    }

    private fun readBits(buf: ByteArray, bitOffset: Int, numBits: Int): Long {
        var value = 0L
        for (b in 0 until numBits) {
            val byteIdx = (bitOffset + b) ushr 3
            val bitIdx = 7 - ((bitOffset + b) and 7)
            val bit = (buf[byteIdx].toInt() ushr bitIdx) and 1
            value = (value shl 1) or bit.toLong()
        }
        return value
    }

    private fun readSignedBits(buf: ByteArray, bitOffset: Int, numBits: Int): Long {
        var v = readBits(buf, bitOffset, numBits)
        val signBit = 1L shl (numBits - 1)
        if (v and signBit != 0L) v = v or ((-1L) shl numBits)
        return v
    }

    private fun ecefToGeodetic(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val a = 6378137.0
        val f = 1.0 / 298.257223563
        val b = a * (1 - f)
        val e2 = 2 * f - f * f
        val ep2 = (a * a - b * b) / (b * b)
        val p = sqrt(x * x + y * y)
        val theta = atan2(z * a, p * b)
        val lon = atan2(y, x)
        val sinT = sin(theta); val cosT = cos(theta)
        val lat = atan2(
            z + ep2 * b * sinT * sinT * sinT,
            p - e2 * a * cosT * cosT * cosT
        )
        val sinLat = sin(lat)
        val n = a / sqrt(1 - e2 * sinLat * sinLat)
        val alt = p / cos(lat) - n
        return Triple(lat, lon, alt)
    }
}
