package com.kira.tts

/**
 * Lightweight, standalone MAVLink v1/v2 frame scanner used only by the
 * optional MAVLink monitor. It does NOT touch the RTK data path and does
 * NOT validate CRC (permissive sniffer). Kept separate from MavlinkHelper
 * so the core RTK logic is never affected.
 */
object MavlinkScan {

    private const val STX_V1: Byte = 0xFE.toByte()
    private const val STX_V2: Byte = 0xFD.toByte()

    data class Frame(
        val version: Int,
        val sysId: Int,
        val compId: Int,
        val msgId: Int,
        val seq: Int,
        val payload: ByteArray,
    )

    fun scan(buf: ByteArray, length: Int = buf.size): List<Frame> {
        val out = ArrayList<Frame>(4)
        var i = 0
        val end = minOf(length, buf.size)
        while (i < end) {
            val b = buf[i]
            if (b == STX_V1 && i + 8 <= end) {
                val len = buf[i + 1].toInt() and 0xFF
                val frameLen = 6 + len + 2
                if (i + frameLen > end) { i++; continue }
                val seq = buf[i + 2].toInt() and 0xFF
                val sys = buf[i + 3].toInt() and 0xFF
                val comp = buf[i + 4].toInt() and 0xFF
                val msg = buf[i + 5].toInt() and 0xFF
                out.add(Frame(1, sys, comp, msg, seq, buf.copyOfRange(i + 6, i + 6 + len)))
                i += frameLen
                continue
            }
            if (b == STX_V2 && i + 12 <= end) {
                val len = buf[i + 1].toInt() and 0xFF
                val incompat = buf[i + 2].toInt() and 0xFF
                val seq = buf[i + 4].toInt() and 0xFF
                val sys = buf[i + 5].toInt() and 0xFF
                val comp = buf[i + 6].toInt() and 0xFF
                val msg = (buf[i + 7].toInt() and 0xFF) or
                        ((buf[i + 8].toInt() and 0xFF) shl 8) or
                        ((buf[i + 9].toInt() and 0xFF) shl 16)
                val sigLen = if (incompat and 0x01 != 0) 13 else 0
                val frameLen = 10 + len + 2 + sigLen
                if (i + frameLen > end) { i++; continue }
                out.add(Frame(2, sys, comp, msg, seq, buf.copyOfRange(i + 10, i + 10 + len)))
                i += frameLen
                continue
            }
            i++
        }
        return out
    }
}
