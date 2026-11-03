package com.vrbox.client.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object AndroidSavedConnectionStore {
    private var instance: SavedConnectionStore? = null
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "vr-saved-connection") }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun submit(context: Context, action: (SavedConnectionStore) -> SavedConnection?, onComplete: (Result<SavedConnection?>) -> Unit) {
        val appContext = context.applicationContext
        // Queue before returning to the Activity. Recreation cannot cancel or reorder a requested write/delete.
        worker.execute {
            val result = runCatching { action(open(appContext)) }
            mainHandler.post { onComplete(result) }
        }
    }

    @Synchronized
    private fun open(context: Context): SavedConnectionStore {
        instance?.let { return it }
        val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "saved_connection.bin"))
        // Share the synchronized store across Activity recreation, including in-flight disk writes.
        return SavedConnectionStore(
            read = {
                val input = try { file.openRead() } catch (_: FileNotFoundException) { null }
                input?.use {
                    require(it.channel.size() <= SavedConnectionStore.MAX_RECORD_BYTES) { "Saved connection is too large" }
                    it.readBytes()
                }
            },
            write = { bytes ->
                if (bytes == null) {
                    file.delete()
                    if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                        throw IOException("Could not delete saved connection")
                    }
                } else {
                    val output = file.startWrite()
                    try {
                        output.write(bytes)
                        file.finishWrite(output)
                        if (!file.openRead().use { it.readBytes() }.contentEquals(bytes)) {
                            throw IOException("Could not persist saved connection")
                        }
                    } catch (e: Exception) {
                        file.failWrite(output)
                        throw e
                    }
                }
            },
            key = {
                val alias = "vrbox_saved_connection_v1"
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
                ).run {
                    init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build())
                    generateKey()
                }
            }
        ).also { instance = it }
    }
}
