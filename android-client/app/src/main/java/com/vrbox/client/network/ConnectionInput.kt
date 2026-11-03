package com.vrbox.client.network

object ConnectionInput {
    /** Numeric IPv4 only. getByAddress() can use the result without any DNS lookup. */
    fun localIpv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                (part.length > 1 && part[0] == '0')) return null
            octets[i] = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        val local = octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254)
        return if (local) ByteArray(4) { octets[it].toByte() } else null
    }

    fun validPin(pin: String): Boolean = pin.length == 6 && pin.all { it in '0'..'9' }
}
