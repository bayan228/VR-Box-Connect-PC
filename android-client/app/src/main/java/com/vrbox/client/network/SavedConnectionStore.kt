package com.vrbox.client.network

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedConnection(val ip: String, val pin: String, val pinned: Boolean = false) {
    init {
        require(ConnectionInput.localIpv4(ip) != null) {
            "Enter a private, link-local or loopback IPv4 only, without a hostname or :port."
        }
        require(ConnectionInput.validPin(pin)) { "Enter the six-digit pairing PIN shown by the PC server." }
    }

    override fun toString() = "SavedConnection(ip=$ip, pin=<redacted>, pinned=$pinned)"
}

/** One encrypted record. The storage adapter must replace it atomically or throw. */
class SavedConnectionStore(
    private val read: () -> ByteArray?,
    private val write: (ByteArray?) -> Unit,
    private val key: () -> SecretKey
) {
    @Synchronized
    fun load(): SavedConnection? {
        val bytes = read() ?: return null
        require(bytes.size in 30..MAX_RECORD_BYTES && bytes[0] == VERSION) { "Invalid saved connection record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, 12))
        cipher.updateAAD(byteArrayOf(VERSION))
        val fields = cipher.doFinal(bytes, 13, bytes.size - 13).toString(Charsets.UTF_8).split('\n')
        require(fields.size == 3 && fields[2] in listOf("0", "1")) { "Invalid saved connection fields" }
        return SavedConnection(fields[0], fields[1], fields[2] == "1")
    }

    @Synchronized
    fun save(connection: SavedConnection): SavedConnection {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()) // Let the provider generate a fresh IV for every write.
        require(cipher.iv.size == 12) { "Unexpected saved connection IV size" }
        cipher.updateAAD(byteArrayOf(VERSION))
        val text = "${connection.ip}\n${connection.pin}\n${if (connection.pinned) 1 else 0}"
        write(byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
        return connection
    }

    @Synchronized
    fun rememberSuccessful(connection: SavedConnection): SavedConnection {
        val previous = load()
        if (previous?.pinned == true && previous.ip != connection.ip) return previous
        // A new PIN for the same pinned PC is refreshed only after a successful connection.
        val next = connection.copy(pinned = previous?.pinned == true)
        return if (next == previous) next else save(next)
    }

    @Synchronized
    fun clear() = write(null)

    companion object {
        const val MAX_RECORD_BYTES = 128
        private const val VERSION: Byte = 1
    }
}
