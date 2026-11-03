package com.vrbox.client.network

import com.vrbox.client.protocol.AccessUnit
import com.vrbox.client.protocol.Protocol
import com.vrbox.client.protocol.VideoReassembler
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VrUdpClient(
    private val address: InetAddress,
    private val onAccessUnit: (AccessUnit) -> Unit,
    private val onNeedKeyframe: () -> Unit,
    private val onError: (String) -> Unit
) : Closeable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val resetVideo = AtomicBoolean(false)
    private val assembler = VideoReassembler()
    private val pendingTimings = LinkedHashMap<Long, Long>()
    private val timingLock = Any()
    private var timingSequence = 0L
    private var imuSequence = 0L
    private val rttNs = AtomicLong(-1)
    private val lastTimingReplyNs = AtomicLong(0)
    @Volatile private var videoSocket: DatagramSocket? = null
    @Volatile private var imuSocket: DatagramSocket? = null
    @Volatile private var receiver: Thread? = null

    val videoPort get() = videoSocket?.localPort ?: error("Video UDP is not bound")
    val imuPort get() = imuSocket?.localPort ?: error("IMU UDP is not bound")

    /** Both ephemeral ports exist before HELLO. connect() filters packets to this numeric peer. */
    fun open() = synchronized(lock) {
        check(!closed.get()) { "UDP connection cancelled" }
        try {
            val video = DatagramSocket(null)
            videoSocket = video
            video.apply {
                receiveBufferSize = 512 * 1024
                soTimeout = 20
                bind(InetSocketAddress(0))
                connect(address, Protocol.PORT_VIDEO)
            }
            val sensor = DatagramSocket(null)
            imuSocket = sensor
            sensor.apply {
                bind(InetSocketAddress(0))
                connect(address, Protocol.PORT_IMU)
            }
            receiver = Thread(::receiveVideo, "vr-udp-video").also { it.start() }
        } catch (e: Exception) {
            videoSocket?.close()
            imuSocket?.close()
            throw e
        }
    }

    fun requireKeyframe() { resetVideo.set(true) }

    private fun receiveVideo() {
        // One extra byte detects oversized datagrams even when the OS truncates their remainder.
        val buffer = ByteArray(Protocol.MAX_DATAGRAM_SIZE + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (!closed.get()) {
                if (resetVideo.getAndSet(false)) assembler.invalidate()
                dispatch(assembler.poll(System.nanoTime()))
                packet.length = buffer.size
                try {
                    videoSocket?.receive(packet) ?: break
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (closed.get()) break
                val datagram = Protocol.parseDatagram(packet.data, packet.offset, packet.length) ?: continue
                when (datagram.channel) {
                    Protocol.Channel.VIDEO -> {
                        val fragment = Protocol.parseVideo(datagram.payload)
                        if (fragment == null) {
                            assembler.invalidate()
                            onNeedKeyframe()
                        } else {
                            dispatch(assembler.offer(fragment, System.nanoTime()))
                        }
                    }
                    Protocol.Channel.TIMING -> handleTiming(datagram.payload)
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) onError("Video UDP: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            assembler.reset()
        }
    }

    private fun dispatch(result: VideoReassembler.Result) {
        if (result.needsKeyframe && !closed.get()) onNeedKeyframe()
        for (frame in result.frames) {
            if (closed.get()) break
            onAccessUnit(frame) // Bounded queue offer only; never a MediaCodec call.
        }
    }

    fun sendTiming(nowNs: Long) {
        val sequence = timingSequence
        timingSequence = (timingSequence + 1) and Protocol.UINT32_MASK
        synchronized(timingLock) {
            pendingTimings.entries.removeAll { nowNs - it.value > 3_000_000_000L }
            if (pendingTimings.size >= 32) pendingTimings.remove(pendingTimings.keys.first())
            pendingTimings[sequence] = nowNs
        }
        val data = Protocol.buildTimingPacket(nowNs, sequence)
        val socket = videoSocket ?: throw IOException("Video UDP is closed")
        socket.send(DatagramPacket(data, data.size))
    }

    private fun handleTiming(payload: ByteArray) {
        val timing = Protocol.parseTiming(payload) ?: return
        val sent = synchronized(timingLock) {
            if (pendingTimings[timing.sequence] != timing.sendNs) null else pendingTimings.remove(timing.sequence)
        } ?: return
        val now = System.nanoTime()
        val elapsed = now - sent
        if (elapsed in 0..3_000_000_000L) {
            rttNs.set(elapsed)
            lastTimingReplyNs.set(now)
        }
    }

    fun rttMilliseconds(nowNs: Long): Double? =
        rttNs.get().takeIf { it >= 0 && nowNs - lastTimingReplyNs.get() <= 3_000_000_000L }?.div(1_000_000.0)

    fun sendImu(timestampNs: Long, sampleSequence: Long, values: FloatArray) {
        val data = Protocol.buildImuPacket(timestampNs, sampleSequence, values, imuSequence)
        imuSequence = (imuSequence + 1) and Protocol.UINT32_MASK
        val socket = imuSocket ?: throw IOException("IMU UDP is closed")
        socket.send(DatagramPacket(data, data.size))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            videoSocket?.close()
            imuSocket?.close()
        }
        synchronized(timingLock) { pendingTimings.clear() }
        receiver?.takeIf { it !== Thread.currentThread() }?.join(2000)
    }
}
