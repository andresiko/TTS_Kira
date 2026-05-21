package com.kira.tts

object MavlinkParser {

    data class Frame(
        val version: Int,
        val sysId: Int,
        val compId: Int,
        val msgId: Int,
        val seq: Int,
        val payload: ByteArray,
    )

    private const val STX_V1: Byte = 0xFE.toByte()
    private const val STX_V2: Byte = 0xFD.toByte()

    fun parseAll(buf: ByteArray, length: Int = buf.size): List<Frame> {
        val out = ArrayList<Frame>(4)
        var i = 0
        val end = length
        while (i < end) {
            val b = buf[i]
            if (b == STX_V1 && i + 8 <= end) {
                val len = buf[i + 1].toInt() and 0xFF
                val frameLen = 6 + len + 2
                if (i + frameLen > end) break
                val seq = buf[i + 2].toInt() and 0xFF
                val sys = buf[i + 3].toInt() and 0xFF
                val comp = buf[i + 4].toInt() and 0xFF
                val msg = buf[i + 5].toInt() and 0xFF
                val payload = buf.copyOfRange(i + 6, i + 6 + len)
                out.add(Frame(1, sys, comp, msg, seq, payload))
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
                if (i + frameLen > end) break
                val payload = buf.copyOfRange(i + 10, i + 10 + len)
                out.add(Frame(2, sys, comp, msg, seq, payload))
                i += frameLen
                continue
            }
            i++
        }
        return out
    }

    class StreamParser {
        private var buffer = ByteArray(0)
        private val maxBufferSize = 4096

        fun feed(chunk: ByteArray, length: Int = chunk.size): List<Frame> {
            buffer = if (buffer.isEmpty()) chunk.copyOf(length)
            else buffer + chunk.copyOfRange(0, length)

            val frames = parseAll(buffer, buffer.size)
            if (frames.isEmpty()) {
                if (buffer.size > maxBufferSize) {
                    buffer = buffer.copyOfRange(buffer.size - maxBufferSize, buffer.size)
                }
                return emptyList()
            }
            val last = frames.last()
            val consumedEnd = locateConsumedEnd(buffer, last)
            buffer = if (consumedEnd >= buffer.size) ByteArray(0)
            else buffer.copyOfRange(consumedEnd, buffer.size)
            return frames
        }

        private fun locateConsumedEnd(buf: ByteArray, last: Frame): Int {
            var i = 0
            var lastEnd = 0
            while (i < buf.size) {
                val b = buf[i]
                if (b == STX_V1 && i + 8 <= buf.size) {
                    val len = buf[i + 1].toInt() and 0xFF
                    val frameLen = 6 + len + 2
                    if (i + frameLen > buf.size) break
                    lastEnd = i + frameLen
                    i += frameLen; continue
                }
                if (b == STX_V2 && i + 12 <= buf.size) {
                    val len = buf[i + 1].toInt() and 0xFF
                    val incompat = buf[i + 2].toInt() and 0xFF
                    val sigLen = if (incompat and 0x01 != 0) 13 else 0
                    val frameLen = 10 + len + 2 + sigLen
                    if (i + frameLen > buf.size) break
                    lastEnd = i + frameLen
                    i += frameLen; continue
                }
                i++
            }
            return lastEnd
        }
    }
}
