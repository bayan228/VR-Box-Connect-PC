package com.vrbox.client.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SavedConnectionStoreTest {
    private class Storage {
        var bytes: ByteArray? = null
        var failWrites = false
        var writes = 0
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        fun open(secret: SecretKey = key) = SavedConnectionStore(
            read = { bytes?.copyOf() },
            write = {
                if (failWrites) throw IOException("Test storage failure")
                writes++
                bytes = it?.copyOf()
            },
            key = { secret }
        )
    }

    @Test fun emptyStorageDoesNotRequestAKey() {
        val store = SavedConnectionStore({ null }, { error("Unexpected write") }, { error("Unexpected key access") })
        assertNull(store.load())
    }

    @Test fun restoresAddressPinAndPinnedFlagAfterReopening() {
        val storage = Storage()
        val expected = SavedConnection("192.168.1.10", "001234", pinned = true)
        storage.open().save(expected)
        assertEquals(expected, storage.open().load())
        assertFalse(expected.toString().contains(expected.pin))
        assertTrue(storage.bytes!!.size <= SavedConnectionStore.MAX_RECORD_BYTES)
    }

    @Test fun successfulConnectionsReplaceTheUnpinnedLastConnection() {
        val storage = Storage()
        val store = storage.open()
        store.rememberSuccessful(SavedConnection("10.0.0.1", "123456"))
        val latest = SavedConnection("192.168.1.2", "654321")
        assertEquals(latest, store.rememberSuccessful(latest))
        assertEquals(latest, storage.open().load())
        store.rememberSuccessful(latest)
        assertEquals(2, storage.writes)
    }

    @Test fun anotherPcDoesNotReplaceThePinnedConnection() {
        val storage = Storage()
        val pinned = SavedConnection("10.0.0.1", "123456", pinned = true)
        val store = storage.open()
        store.save(pinned)
        assertEquals(pinned, store.rememberSuccessful(SavedConnection("192.168.1.2", "654321")))
        assertEquals(pinned, storage.open().load())
        assertEquals(1, storage.writes)
    }

    @Test fun successfulPairingRefreshesThePinOfTheSamePinnedPc() {
        val storage = Storage()
        val store = storage.open()
        store.save(SavedConnection("10.0.0.1", "123456", pinned = true))
        val updated = SavedConnection("10.0.0.1", "000001")
        assertEquals(updated.copy(pinned = true), store.rememberSuccessful(updated))
        assertEquals(updated.copy(pinned = true), storage.open().load())
    }

    @Test fun explicitSaveCanReplaceAPinnedConnection() {
        val storage = Storage()
        val store = storage.open()
        store.save(SavedConnection("10.0.0.1", "123456", pinned = true))
        val replacement = SavedConnection("172.16.1.5", "654321", pinned = true)
        store.save(replacement)
        assertEquals(replacement, storage.open().load())
    }

    @Test fun unpinningAllowsFutureSuccessfulConnectionsToBecomeLast() {
        val storage = Storage()
        val store = storage.open()
        val pinned = SavedConnection("10.0.0.1", "123456", pinned = true)
        store.save(pinned)
        store.save(pinned.copy(pinned = false))
        val latest = SavedConnection("172.16.1.5", "654321")
        store.rememberSuccessful(latest)
        assertEquals(latest, storage.open().load())
    }

    @Test fun deletionSurvivesReopeningAndAllowsANewLastConnection() {
        val storage = Storage()
        val store = storage.open()
        store.save(SavedConnection("10.0.0.1", "123456", pinned = true))
        store.clear()
        assertNull(storage.bytes)
        assertNull(storage.open().load())
        val latest = SavedConnection("192.168.1.2", "654321")
        storage.open().rememberSuccessful(latest)
        assertEquals(latest, storage.open().load())
    }

    @Test fun corruptOrUnreadableCredentialsCanBeDeletedWithoutAKey() {
        val storage = Storage()
        storage.bytes = byteArrayOf(99, 0)
        val store = SavedConnectionStore({ storage.bytes }, { storage.bytes = it }, { error("Key unavailable") })
        store.clear()
        assertNull(storage.bytes)
        assertNull(store.load())
    }

    @Test fun encryptionUsesFreshIvsAndDoesNotStorePlaintextCredentials() {
        val storage = Storage()
        val store = storage.open()
        val saved = SavedConnection("192.168.123.234", "009876")
        store.save(saved)
        val first = storage.bytes!!.copyOf()
        store.save(saved)
        assertFalse(first.copyOfRange(1, 13).contentEquals(storage.bytes!!.copyOfRange(1, 13)))
        for (bytes in listOf(first, storage.bytes!!)) {
            val text = bytes.toString(Charsets.ISO_8859_1)
            assertFalse(text.contains(saved.ip))
            assertFalse(text.contains(saved.pin))
        }
        assertEquals(saved, store.load())
    }

    @Test fun alteredCiphertextAndWrongKeysAreRejected() {
        val storage = Storage()
        storage.open().save(SavedConnection("10.0.0.1", "123456"))
        val original = storage.bytes!!.copyOf()
        storage.bytes!![14] = (storage.bytes!![14].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { storage.open().load() }
        storage.bytes = original
        assertThrows(GeneralSecurityException::class.java) { storage.open(Storage().key).load() }
    }

    @Test fun malformedRecordsAreRejectedAndCanBeExplicitlyReplaced() {
        val storage = Storage()
        val store = storage.open()
        val saved = SavedConnection("10.0.0.1", "123456")
        store.save(saved)
        val unknownVersion = storage.bytes!!.copyOf().apply { this[0] = 2 }
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(29), ByteArray(129), unknownVersion)) {
            storage.bytes = bytes
            assertThrows(IllegalArgumentException::class.java) { store.load() }
        }
        store.save(saved.copy(pinned = true))
        assertEquals(saved.copy(pinned = true), storage.open().load())
    }

    @Test fun invalidFieldsNeverOverwriteAValidRecord() {
        val storage = Storage()
        val store = storage.open()
        val saved = SavedConnection("10.0.0.1", "123456")
        store.save(saved)
        for (ip in listOf("", "example.com", "10.0.0.1:8765", "8.8.8.8", "192.168.01.1")) {
            assertThrows(IllegalArgumentException::class.java) { store.save(SavedConnection(ip, "123456")) }
        }
        for (pin in listOf("", "12345", "1234567", "12345a")) {
            assertThrows(IllegalArgumentException::class.java) { store.save(SavedConnection(saved.ip, pin)) }
        }
        assertEquals(saved, storage.open().load())
        assertEquals(1, storage.writes)
    }

    @Test fun failedWritesAndDeletesAreReportedWithoutPretendingToChangeTheRecord() {
        val storage = Storage()
        val store = storage.open()
        val saved = SavedConnection("10.0.0.1", "123456")
        store.save(saved)
        storage.failWrites = true
        assertThrows(IOException::class.java) { store.save(saved.copy(pin = "654321")) }
        assertThrows(IOException::class.java) { store.clear() }
        assertEquals(saved, storage.open().load())
        assertEquals(1, storage.writes)
    }

    @Test fun keyFailureNeverFallsBackToPlaintextStorage() {
        val store = SavedConnectionStore({ null }, { error("Must not write without encryption") },
            { throw GeneralSecurityException("Key unavailable") })
        assertThrows(GeneralSecurityException::class.java) { store.save(SavedConnection("10.0.0.1", "123456")) }
    }
}
