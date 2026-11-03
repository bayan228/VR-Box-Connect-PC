package com.vrbox.client.protocol

import org.junit.Assert.*
import org.junit.Test

class AnnexBTest {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1f)
    private val pps = byteArrayOf(0, 0, 1, 0x68, 0x11, 0x22)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x33, 0x44)

    @Test fun extractsRealParameterSetsFromAWholeIdrWithMixedStartCodes() {
        val aud = byteArrayOf(0, 0, 1, 9, 0x10)
        val sets = AnnexB.parameterSets(aud + sps + pps + idr)!!
        assertArrayEquals(sps, sets.sps)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x68, 0x11, 0x22), sets.pps)
    }

    @Test fun missingConfigMissingIdrAndLengthPrefixedInputAreNotConfigurable() {
        for (data in listOf(sps + idr, pps + idr, sps + pps, idr,
            byteArrayOf(0, 0, 0, 3, 0x65, 0x33, 0x44), byteArrayOf(), byteArrayOf(0, 0, 1))) {
            assertNull(AnnexB.parameterSets(data))
        }
        val forbiddenBit = sps.copyOf().also { it[4] = 0xe7.toByte() }
        assertNull(AnnexB.parameterSets(forbiddenBit + pps + idr))
    }

    @Test fun emulationPreventionBytesAreNotMistakenForStartCodes() {
        val escapedSps = sps + byteArrayOf(0, 0, 3, 1, 0x55)
        assertArrayEquals(escapedSps, AnnexB.parameterSets(escapedSps + pps + idr)!!.sps)
    }
}
