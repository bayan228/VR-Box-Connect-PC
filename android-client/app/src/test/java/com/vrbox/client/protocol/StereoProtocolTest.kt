package com.vrbox.client.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class StereoProtocolTest {
    @Test fun recenterHasItsAssignedIdAndEmptyJsonObjectPayload() {
        val bytes = Protocol.serializeControl(Protocol.CtrlType.RECENTER, "{}")
        assertArrayEquals(byteArrayOf(0x52, 0x56, 1, 0x0f, 2, 0, 0, 0, 0x7b, 0x7d), bytes)
        val decoded = Protocol.readControl(ByteArrayInputStream(bytes))
        assertEquals(Protocol.CtrlType.RECENTER, decoded.type)
        assertEquals("{}", decoded.payload.toString(Charsets.UTF_8))
    }

    @Test fun recenterAcknowledgementAndExtendedTelemetryKeepTheirExistingTypes() {
        val ack = "{\"recentered\":true}"
        val telemetry = "{\"steamvr_connected\":true,\"steamvr_frames\":42}"
        val stream = ByteArrayInputStream(Protocol.serializeControl(Protocol.CtrlType.SETTINGS_ACK, ack) +
            Protocol.serializeControl(Protocol.CtrlType.TELEMETRY, telemetry))
        val first = Protocol.readControl(stream)
        val second = Protocol.readControl(stream)
        assertEquals(Protocol.CtrlType.SETTINGS_ACK, first.type)
        assertEquals(ack, first.payload.toString(Charsets.UTF_8))
        assertEquals(Protocol.CtrlType.TELEMETRY, second.type)
        assertEquals(telemetry, second.payload.toString(Charsets.UTF_8))
        assertEquals(0x0e, Protocol.CtrlType.PING.id)
        assertEquals(Protocol.CtrlType.entries.size, Protocol.CtrlType.entries.map { it.id }.distinct().size)
    }
}
