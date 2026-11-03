package com.vrbox.client.network

import com.vrbox.client.protocol.Protocol
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Blocking operations are called only by the session reader/sender workers. */
class VrControlClient(private val address: InetAddress) : Closeable {
    private val socket = Socket()
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var output: OutputStream? = null

    fun connect() {
        if (closed.get()) throw IOException("Connection cancelled")
        socket.connect(InetSocketAddress(address, Protocol.PORT_TCP), 5000)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 6000
        output = socket.getOutputStream()
    }

    fun read(): Protocol.ControlMessage = Protocol.readControl(socket.getInputStream())

    fun send(type: Protocol.CtrlType, payload: ByteArray) {
        val frame = Protocol.serializeControl(type, payload)
        synchronized(writeLock) {
            if (closed.get()) throw IOException("Control connection is closed")
            val stream = output ?: throw IOException("Control connection is not ready")
            stream.write(frame)
            stream.flush()
        }
    }

    fun send(type: Protocol.CtrlType, json: String = "{}") = send(type, json.toByteArray(Charsets.UTF_8))

    // Deliberately does not acquire writeLock: closing must interrupt a blocked writer/readFully.
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { socket.close() } catch (_: IOException) { }
            output = null
        }
    }
}
