package com.vrbox.client.protocol

object AnnexB {
    data class ParameterSets(val sps: ByteArray, val pps: ByteArray)

    fun hasStartCode(data: ByteArray): Boolean = startCodeSize(data, 0) != 0

    /** Retain real SPS/PPS from the first complete IDR, including Annex-B start codes. */
    fun parameterSets(data: ByteArray): ParameterSets? {
        if (!hasStartCode(data)) return null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var idr = false
        var start = 0
        while (start < data.size) {
            val header = start + startCodeSize(data, start)
            if (header >= data.size || data[header].toInt() and 0x80 != 0) return null
            var end = header + 1
            while (end < data.size && startCodeSize(data, end) == 0) end++
            when (data[header].toInt() and 0x1f) {
                5 -> idr = true
                7 -> sps = byteArrayOf(0, 0, 0, 1) + data.copyOfRange(header, end)
                8 -> pps = byteArrayOf(0, 0, 0, 1) + data.copyOfRange(header, end)
            }
            start = end
        }
        return if (idr && sps != null && pps != null) ParameterSets(sps, pps) else null
    }

    private fun startCodeSize(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 >= data.size || data[offset] != 0.toByte() || data[offset + 1] != 0.toByte()) return 0
        if (data[offset + 2] == 1.toByte()) return 3
        return if (offset + 3 < data.size && data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) 4 else 0
    }
}
