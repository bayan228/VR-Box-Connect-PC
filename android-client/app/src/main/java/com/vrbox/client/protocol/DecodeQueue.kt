package com.vrbox.client.protocol

import java.util.ArrayDeque

/** Network producers never wait for MediaCodec. Overflow discards the dependent chain, not just one P. */
class DecodeQueue(private val maxFrames: Int = 3, private val maxBytes: Int = 4 * 1024 * 1024) {
    private val lock = Object()
    private val frames = ArrayDeque<AccessUnit>()
    private var bytes = 0
    private var closed = false
    private var needsIdr = true
    private var lastAcceptedId: Long? = null

    init { require(maxFrames > 0 && maxBytes > 0) }

    fun offer(frame: AccessUnit): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (frame.data.isEmpty() || frame.data.size > Protocol.MAX_ACCESS_UNIT_SIZE || frame.data.size > maxBytes) {
            invalidateLocked()
            return@synchronized false
        }
        if (!frame.keyframe && (needsIdr || lastAcceptedId?.let {
                frame.frameId != ((it + 1) and Protocol.UINT32_MASK)
            } == true)) {
            invalidateLocked()
            return@synchronized false
        }
        if (frames.size >= maxFrames || frame.data.size > maxBytes - bytes) {
            invalidateLocked()
            if (!frame.keyframe) return@synchronized false
        }
        frames.addLast(frame)
        bytes += frame.data.size
        needsIdr = false
        lastAcceptedId = frame.frameId
        lock.notifyAll()
        true
    }

    @Throws(InterruptedException::class)
    fun poll(timeoutMs: Long = 0): AccessUnit? = synchronized(lock) {
        if (frames.isEmpty() && !closed && timeoutMs > 0) lock.wait(timeoutMs)
        val frame = frames.pollFirst()
        if (frame != null) bytes -= frame.data.size
        frame
    }

    fun invalidate() = synchronized(lock) { invalidateLocked() }

    fun close() = synchronized(lock) {
        closed = true
        invalidateLocked()
        lock.notifyAll()
    }

    val size get() = synchronized(lock) { frames.size }
    val bufferedBytes get() = synchronized(lock) { bytes }
    val waitingForKeyframe get() = synchronized(lock) { needsIdr }

    private fun invalidateLocked() {
        frames.clear()
        bytes = 0
        needsIdr = true
    }
}
