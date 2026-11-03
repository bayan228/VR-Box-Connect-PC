package com.vrbox.client.protocol

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Protocol v1. All multibyte fields are little-endian; timestamps are not clock sync. */
object Protocol {
    const val MAGIC = 0x5652
    const val VERSION = 1
    const val PORT_TCP = 8765
    const val PORT_VIDEO = 8766
    const val PORT_IMU = 8767
    const val UDP_HEADER_SIZE = 14
    const val VIDEO_HEADER_SIZE = 16
    const val MAX_DATAGRAM_SIZE = 1200
    const val MAX_FRAGMENT_SIZE = 1170
    const val MAX_FRAGMENTS = 2048
    const val MAX_ACCESS_UNIT_SIZE = 2 * 1024 * 1024
    const val MAX_CONTROL_PAYLOAD = 65536
    const val UINT32_MASK = 0xffffffffL

    enum class Channel(val id: Int) { VIDEO(1), IMU(2), TIMING(3), NACK(4) }

    enum class CtrlType(val id: Int) {
        HELLO(0x01), HELLO_ACK(0x02), SETTINGS_REQUEST(0x03), SETTINGS_ACK(0x04),
        VIDEO_START(0x05), VIDEO_STOP(0x06), IMU_START(0x07), IMU_STOP(0x08),
        TELEMETRY(0x09), CLIENT_TELEMETRY(0x0a), ERROR(0x0b), KEYFRAME_REQUEST(0x0c),
        SESSION_END(0x0d), PING(0x0e), RECENTER(0x0f)
    }

    data class Datagram(val channel: Channel, val sequence: Long, val payload: ByteArray)
    data class ControlMessage(val type: CtrlType, val payload: ByteArray)
    data class Timing(val sendNs: Long, val sequence: Long)
    data class VideoFragment(
        val frameId: Long,
        val index: Int,
        val total: Int,
        val keyframe: Boolean,
        val timestampMs: Long,
        val data: ByteArray
    )

    fun buildDatagram(channel: Channel, payload: ByteArray, sequence: Long): ByteArray {
        require(payload.size <= MAX_DATAGRAM_SIZE - UDP_HEADER_SIZE)
        require(sequence in 0..UINT32_MASK)
        val out = ByteArray(UDP_HEADER_SIZE + payload.size)
        put16(out, 0, MAGIC)
        out[2] = VERSION.toByte()
        out[3] = channel.id.toByte()
        put32(out, 6, sequence)
        put16(out, 10, payload.size)
        put16(out, 12, crc16ccitt(out, 0, 12))
        payload.copyInto(out, UDP_HEADER_SIZE)
        return out
    }

    fun parseDatagram(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset
    ): Datagram? {
        if (offset < 0 || length !in UDP_HEADER_SIZE..MAX_DATAGRAM_SIZE ||
            offset > data.size - length) return null
        if (u16(data, offset) != MAGIC || u8(data, offset + 2) != VERSION) return null
        val channel = Channel.entries.firstOrNull { it.id == u8(data, offset + 3) } ?: return null
        // This slice explicitly rejects payload-CRC bit 0 as well as unknown flags.
        if (u8(data, offset + 4) != 0 || u8(data, offset + 5) != 0) return null
        if (u16(data, offset + 10) != length - UDP_HEADER_SIZE) return null
        if (u16(data, offset + 12) != crc16ccitt(data, offset, 12)) return null
        return Datagram(channel, u32(data, offset + 6),
            data.copyOfRange(offset + UDP_HEADER_SIZE, offset + length))
    }

    fun parseVideo(payload: ByteArray): VideoFragment? {
        if (payload.size !in (VIDEO_HEADER_SIZE + 1)..(VIDEO_HEADER_SIZE + MAX_FRAGMENT_SIZE)) return null
        val index = u16(payload, 0)
        val total = u16(payload, 2)
        val type = u8(payload, 9)
        if (total !in 1..MAX_FRAGMENTS || index >= total || u8(payload, 8) != 0 ||
            type !in 0..1 || u8(payload, 10) != 0 ||
            u8(payload, 11) != (if (type == 0) 5 else 1)) return null
        return VideoFragment(u32(payload, 4), index, total, type == 0,
            u32(payload, 12), payload.copyOfRange(VIDEO_HEADER_SIZE, payload.size))
    }

    fun buildVideoPacket(fragment: VideoFragment, sequence: Long): ByteArray {
        require(validFragment(fragment))
        val payload = ByteArray(VIDEO_HEADER_SIZE + fragment.data.size)
        put16(payload, 0, fragment.index)
        put16(payload, 2, fragment.total)
        put32(payload, 4, fragment.frameId)
        payload[9] = if (fragment.keyframe) 0 else 1
        payload[11] = if (fragment.keyframe) 5 else 1
        put32(payload, 12, fragment.timestampMs)
        fragment.data.copyInto(payload, VIDEO_HEADER_SIZE)
        return buildDatagram(Channel.VIDEO, payload, sequence)
    }

    fun validFragment(fragment: VideoFragment): Boolean =
        fragment.frameId in 0..UINT32_MASK && fragment.timestampMs in 0..UINT32_MASK &&
            fragment.total in 1..MAX_FRAGMENTS && fragment.index in 0 until fragment.total &&
            fragment.data.size in 1..MAX_FRAGMENT_SIZE

    fun buildTimingPacket(sendNs: Long, sequence: Long): ByteArray {
        val payload = ByteArray(14)
        put64(payload, 0, sendNs)
        put32(payload, 8, sequence)
        return buildDatagram(Channel.TIMING, payload, sequence)
    }

    fun parseTiming(payload: ByteArray): Timing? {
        if (payload.size != 14 || u16(payload, 12) != 0) return null
        return Timing(i64(payload, 0), u32(payload, 8))
    }

    /** Samples are quaternion x,y,z,w, gyro xyz (rad/s), accel xyz (m/s^2). */
    fun buildImuPacket(timestampNs: Long, sampleSeq: Long, samples: FloatArray, sequence: Long): ByteArray {
        require(samples.isNotEmpty() && samples.size % 10 == 0 && samples.size / 10 <= 24)
        require(samples.all { it.isFinite() })
        val payload = ByteArray(16 + samples.size * 4)
        put64(payload, 0, timestampNs)
        put32(payload, 8, sampleSeq)
        payload[12] = (samples.size / 10).toByte()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(16)
        samples.forEach { buffer.putFloat(it) }
        return buildDatagram(Channel.IMU, payload, sequence)
    }

    fun buildNackPacket(frameId: Long, fragments: IntArray, sequence: Long): ByteArray {
        require(fragments.size <= (MAX_DATAGRAM_SIZE - UDP_HEADER_SIZE - 6) / 2)
        require(fragments.all { it in 0 until MAX_FRAGMENTS })
        val payload = ByteArray(6 + fragments.size * 2)
        put32(payload, 0, frameId)
        put16(payload, 4, fragments.size) // Zero means the whole access unit.
        fragments.forEachIndexed { i, index -> put16(payload, 6 + i * 2, index) }
        return buildDatagram(Channel.NACK, payload, sequence)
    }

    fun serializeControl(type: CtrlType, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_CONTROL_PAYLOAD)
        val out = ByteArray(8 + payload.size)
        put16(out, 0, MAGIC)
        out[2] = VERSION.toByte()
        out[3] = type.id.toByte()
        put32(out, 4, payload.size.toLong())
        payload.copyInto(out, 8)
        return out
    }

    fun serializeControl(type: CtrlType, json: String): ByteArray =
        serializeControl(type, json.toByteArray(Charsets.UTF_8))

    /** No resynchronizing or unbounded accumulator: partial/coalesced TCP reads are normal. */
    @Throws(IOException::class)
    fun readControl(input: InputStream): ControlMessage {
        val stream = DataInputStream(input)
        val header = ByteArray(8)
        stream.readFully(header)
        if (u16(header, 0) != MAGIC || u8(header, 2) != VERSION) {
            throw IOException("Invalid control magic or version")
        }
        val type = CtrlType.entries.firstOrNull { it.id == u8(header, 3) }
            ?: throw IOException("Unknown control message type")
        val length = u32(header, 4)
        if (length > MAX_CONTROL_PAYLOAD) throw IOException("Control payload exceeds 65536 bytes")
        val payload = ByteArray(length.toInt())
        stream.readFully(payload)
        return ControlMessage(type, payload)
    }

    /** CRC16-CCITT-FALSE: poly 0x1021, init FFFF, nonreflected, xorout 0000. */
    fun crc16ccitt(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset <= data.size - length)
        var crc = 0xffff
        for (i in offset until offset + length) {
            crc = crc xor (u8(data, i) shl 8)
            repeat(8) {
                crc = (if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1) and 0xffff
            }
        }
        return crc
    }

    fun isNewer(candidate: Long, reference: Long): Boolean =
        ((candidate - reference) and UINT32_MASK) in 1..0x7fffffffL

    fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xff
    fun u16(data: ByteArray, offset: Int): Int = u8(data, offset) or (u8(data, offset + 1) shl 8)
    fun u32(data: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, i -> value or (u8(data, offset + i).toLong() shl (i * 8)) }
    fun i64(data: ByteArray, offset: Int): Long =
        (0 until 8).fold(0L) { value, i -> value or (u8(data, offset + i).toLong() shl (i * 8)) }

    fun put16(data: ByteArray, offset: Int, value: Int) {
        require(value in 0..0xffff)
        repeat(2) { data[offset + it] = (value ushr (it * 8)).toByte() }
    }

    fun put32(data: ByteArray, offset: Int, value: Long) {
        require(value in 0..UINT32_MASK)
        repeat(4) { data[offset + it] = (value ushr (it * 8)).toByte() }
    }

    fun put64(data: ByteArray, offset: Int, value: Long) {
        repeat(8) { data[offset + it] = (value ushr (it * 8)).toByte() }
    }
}
