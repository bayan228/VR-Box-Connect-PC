package com.vrbox.client.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolTest {
    @Test fun crcCcittFalseCheckAndNonzeroOffset() {
        assertEquals(0x29b1, Protocol.crc16ccitt("123456789".toByteArray()))
        assertEquals(0xffff, Protocol.crc16ccitt(byteArrayOf()))
        assertEquals(0x29b1, Protocol.crc16ccitt("prefix123456789suffix".toByteArray(), 6, 9))
    }

    @Test fun timingMatchesNativeGoldenExactly() {
        val golden = hex("52 56 01 03 00 00 0D 0C 0B 0A 0E 00 A7 F5 08 07 06 05 04 03 02 01 0D 0C 0B 0A 00 00")
        val encoded = Protocol.buildTimingPacket(0x0102030405060708L, 0x0a0b0c0dL)
        assertArrayEquals(golden, encoded)
        assertEquals(0xf5a7, Protocol.crc16ccitt(golden, 0, 12))
        val packet = Protocol.parseDatagram(byteArrayOf(7, 8, 9) + golden + byteArrayOf(0), 3, golden.size)!!
        assertEquals(Protocol.Channel.TIMING, packet.channel)
        assertEquals(0x0a0b0c0dL, packet.sequence)
        assertEquals(Protocol.Timing(0x0102030405060708L, 0x0a0b0c0dL), Protocol.parseTiming(packet.payload))
        assertEquals(28, encoded.size)
    }

    @Test fun datagramRejectsInvalidHeaderAndAllUnsupportedFlags() {
        val original = Protocol.buildTimingPacket(123, 7)
        for ((offset, value) in listOf(0 to 0, 2 to 2, 3 to 0, 3 to 5, 4 to 1, 4 to 0x80, 5 to 1)) {
            val bad = original.copyOf()
            bad[offset] = value.toByte()
            Protocol.put16(bad, 12, Protocol.crc16ccitt(bad, 0, 12))
            assertNull("header byte $offset=$value", Protocol.parseDatagram(bad))
        }
        val corrupt = original.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
        assertNull(Protocol.parseDatagram(corrupt))
        assertNull(Protocol.parseDatagram(original + byteArrayOf(0)))
        assertNull(Protocol.parseDatagram(original.copyOf(original.size - 1)))
        assertNull(Protocol.parseDatagram(original, -1, original.size))
        assertNull(Protocol.parseDatagram(original, 1, original.size))
        assertNull(Protocol.parseDatagram(original, 0, Int.MAX_VALUE))
        assertNull(Protocol.parseDatagram(byteArrayOf()))
    }

    @Test fun datagramMtuAndTimingReservedAreStrict() {
        val maximum = Protocol.buildDatagram(Protocol.Channel.VIDEO, ByteArray(1186), 0)
        assertEquals(1200, maximum.size)
        assertNotNull(Protocol.parseDatagram(maximum))
        assertNull(Protocol.parseDatagram(maximum + byteArrayOf(0)))
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.buildDatagram(Protocol.Channel.VIDEO, ByteArray(1187), 0)
        }
        val timing = Protocol.parseDatagram(Protocol.buildTimingPacket(1, 2))!!.payload
        assertNull(Protocol.parseTiming(timing.copyOf(12)))
        assertNull(Protocol.parseTiming(timing + byteArrayOf(0)))
        timing[12] = 1
        assertNull(Protocol.parseTiming(timing))
    }

    @Test fun videoHeaderRoundTripsUnsignedFieldsAndFullMtu() {
        val fragment = Protocol.VideoFragment(0xffffffffL, 2047, 2048, false, 0xfedcba98L, ByteArray(1170) { 9 })
        val packet = Protocol.parseDatagram(Protocol.buildVideoPacket(fragment, 0xffffffffL))!!
        val parsed = Protocol.parseVideo(packet.payload)!!
        assertEquals(1186, packet.payload.size)
        assertEquals(fragment.frameId, parsed.frameId)
        assertEquals(fragment.timestampMs, parsed.timestampMs)
        assertEquals(fragment.index, parsed.index)
        assertEquals(fragment.total, parsed.total)
        assertFalse(parsed.keyframe)
        assertArrayEquals(fragment.data, parsed.data)
    }

    @Test fun videoRejectsInvalidMetadataAndBounds() {
        val fragment = Protocol.VideoFragment(5, 0, 1, true, 33, byteArrayOf(0, 0, 1, 0x65))
        val payload = Protocol.parseDatagram(Protocol.buildVideoPacket(fragment, 2))!!.payload
        for ((offset, value) in listOf(8 to 1, 9 to 2, 10 to 1, 11 to 1)) {
            assertNull(Protocol.parseVideo(payload.copyOf().also { it[offset] = value.toByte() }))
        }
        assertNull(Protocol.parseVideo(payload.copyOf().also { Protocol.put16(it, 0, 1) }))
        assertNull(Protocol.parseVideo(payload.copyOf().also { Protocol.put16(it, 2, 0) }))
        assertNull(Protocol.parseVideo(payload.copyOf().also { Protocol.put16(it, 2, 2049) }))
        assertNull(Protocol.parseVideo(payload.copyOf(16)))
        assertNull(Protocol.parseVideo(ByteArray(1187)))
    }

    @Test fun imuHasExactlySixteenHeaderBytesAndFortyBytesPerSample() {
        val values = floatArrayOf(0f, 0f, 0f, 1f, 1f, 2f, 3f, 4f, 5f, 6f)
        val packet = Protocol.buildImuPacket(0x0102030405060708L, 0x11223344L, values, 9)
        assertEquals(70, packet.size)
        val payload = Protocol.parseDatagram(packet)!!.payload
        assertEquals(56, payload.size)
        assertArrayEquals(hex("08 07 06 05 04 03 02 01 44 33 22 11 01 00 00 00"), payload.copyOf(16))
        val bytes = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        values.forEachIndexed { i, value -> assertEquals(value, bytes.getFloat(16 + i * 4), 0f) }
        assertEquals(990, Protocol.buildImuPacket(1, 0, FloatArray(240), 0).size)
        for (invalid in listOf(FloatArray(0), FloatArray(9), FloatArray(250), FloatArray(10) { Float.NaN })) {
            assertThrows(IllegalArgumentException::class.java) { Protocol.buildImuPacket(1, 0, invalid, 0) }
        }
    }

    @Test fun nackAllowsWholeFrameAndBoundsIndicesAndMtu() {
        val whole = Protocol.parseDatagram(Protocol.buildNackPacket(0xffffffffL, intArrayOf(), 6))!!
        assertArrayEquals(hex("FF FF FF FF 00 00"), whole.payload)
        val parts = Protocol.parseDatagram(Protocol.buildNackPacket(1, intArrayOf(0, 2047), 7))!!
        assertArrayEquals(hex("01 00 00 00 02 00 00 00 FF 07"), parts.payload)
        assertEquals(1200, Protocol.buildNackPacket(1, IntArray(590), 0).size)
        assertThrows(IllegalArgumentException::class.java) { Protocol.buildNackPacket(1, IntArray(591), 0) }
        assertThrows(IllegalArgumentException::class.java) { Protocol.buildNackPacket(1, intArrayOf(2048), 0) }
        assertThrows(IllegalArgumentException::class.java) { Protocol.buildNackPacket(1, intArrayOf(-1), 0) }
    }

    @Test fun controlReadFullyHandlesSplitHeadersBodiesAndCoalescedMessages() {
        val hello = Protocol.serializeControl(Protocol.CtrlType.HELLO, "{\"proto\":1}")
        val ping = Protocol.serializeControl(Protocol.CtrlType.PING, hex("08 07 06 05 04 03 02 01"))
        val input = object : FilterInputStream(ByteArrayInputStream(hello + ping)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(length, 2))
        }
        val first = Protocol.readControl(input)
        val second = Protocol.readControl(input)
        assertEquals(Protocol.CtrlType.HELLO, first.type)
        assertEquals("{\"proto\":1}", first.payload.toString(Charsets.UTF_8))
        assertEquals(Protocol.CtrlType.PING, second.type)
        assertArrayEquals(ping.copyOfRange(8, ping.size), second.payload)
        assertThrows(EOFException::class.java) { Protocol.readControl(input) }
    }

    @Test fun controlRejectsInvalidHeadersOversizeAndTruncationBeforeAllocation() {
        val header = Protocol.serializeControl(Protocol.CtrlType.PING, byteArrayOf()).copyOf(8)
        for ((offset, value) in listOf(0 to 0, 2 to 2, 3 to 0, 3 to 16)) {
            val bad = header.copyOf().also { it[offset] = value.toByte() }
            assertThrows(IOException::class.java) { Protocol.readControl(ByteArrayInputStream(bad)) }
        }
        for (length in listOf(65537L, 0xffffffffL)) {
            val bad = header.copyOf().also { Protocol.put32(it, 4, length) }
            assertThrows(IOException::class.java) { Protocol.readControl(ByteArrayInputStream(bad)) }
        }
        val maximum = Protocol.serializeControl(Protocol.CtrlType.TELEMETRY, ByteArray(65536))
        assertEquals(65536, Protocol.readControl(ByteArrayInputStream(maximum)).payload.size)
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.serializeControl(Protocol.CtrlType.TELEMETRY, ByteArray(65537))
        }
        assertThrows(EOFException::class.java) { Protocol.readControl(ByteArrayInputStream(header.copyOf(7))) }
        assertThrows(EOFException::class.java) { Protocol.readControl(ByteArrayInputStream(maximum.copyOf(20))) }
    }

    @Test fun serialNumbersWrapWithoutTreatingDuplicatesAsNew() {
        assertTrue(Protocol.isNewer(0, 0xffffffffL))
        assertTrue(Protocol.isNewer(1, 0xffffffffL))
        assertFalse(Protocol.isNewer(0xffffffffL, 0))
        assertFalse(Protocol.isNewer(7, 7))
        assertFalse(Protocol.isNewer(0x80000000L, 0))
    }

    private fun hex(text: String): ByteArray = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
