package com.vrbox.client.protocol

data class AccessUnit(val frameId: Long, val timestampMs: Long, val keyframe: Boolean, val data: ByteArray)

/** Single-owner, bounded fragment storage. Completed future P frames wait for their predecessors. */
class VideoReassembler(
    private val maxAccessUnitBytes: Int = Protocol.MAX_ACCESS_UNIT_SIZE,
    private val maxBufferedBytes: Int = 4 * 1024 * 1024,
    private val timeoutNs: Long = 100_000_000L
) {
    data class Result(val frames: List<AccessUnit>, val needsKeyframe: Boolean)

    private class Pending(first: Protocol.VideoFragment, val createdNs: Long) {
        val frameId = first.frameId
        val keyframe = first.keyframe
        val timestampMs = first.timestampMs
        val fragments = arrayOfNulls<ByteArray>(first.total)
        var bytes = 0
        var count = 0
        val complete get() = count == fragments.size
    }

    private val pending = LinkedHashMap<Long, Pending>()
    private var floorId: Long? = null
    var waitingForKeyframe = true
        private set
    var bufferedBytes = 0
        private set
    val inFlightFrames get() = pending.size
    var droppedFrames = 0L
        private set

    init {
        require(maxAccessUnitBytes in 1..Protocol.MAX_ACCESS_UNIT_SIZE)
        require(maxBufferedBytes > 0 && timeoutNs > 0)
    }

    fun offer(fragment: Protocol.VideoFragment, nowNs: Long): Result {
        expire(nowNs)
        if (!Protocol.validFragment(fragment)) {
            invalidate()
            return result()
        }
        if (floorId?.let { !Protocol.isNewer(fragment.frameId, it) } == true) return result()
        var frame = pending[fragment.frameId]
        if (frame == null) {
            if (pending.size == 3) {
                invalidate()
                if (!fragment.keyframe || floorId?.let { !Protocol.isNewer(fragment.frameId, it) } == true) {
                    return result()
                }
            }
            frame = Pending(fragment, nowNs)
            pending[fragment.frameId] = frame
        }
        if (frame.fragments.size != fragment.total || frame.keyframe != fragment.keyframe ||
            frame.timestampMs != fragment.timestampMs) {
            invalidate()
            return result()
        }
        val previous = frame.fragments[fragment.index]
        if (previous != null) {
            if (!previous.contentEquals(fragment.data)) invalidate()
            return result()
        }
        if (fragment.data.size > maxAccessUnitBytes - frame.bytes ||
            fragment.data.size > maxBufferedBytes - bufferedBytes) {
            invalidate()
            return result()
        }
        frame.fragments[fragment.index] = fragment.data.copyOf()
        frame.bytes += fragment.data.size
        frame.count++
        bufferedBytes += fragment.data.size
        return result(drain())
    }

    fun poll(nowNs: Long): Result {
        expire(nowNs)
        return result(drain())
    }

    /** A decoder input drop also invalidates the dependent P chain. Old fragments stay obsolete. */
    fun invalidate() {
        for (id in pending.keys) advanceFloor(id)
        droppedFrames += pending.size.coerceAtLeast(1)
        pending.clear()
        bufferedBytes = 0
        waitingForKeyframe = true
    }

    fun reset() {
        pending.clear()
        bufferedBytes = 0
        floorId = null
        waitingForKeyframe = true
        droppedFrames = 0
    }

    private fun expire(nowNs: Long) {
        if (pending.values.any { nowNs - it.createdNs >= timeoutNs }) invalidate()
    }

    private fun result(frames: List<AccessUnit> = emptyList()) = Result(frames, waitingForKeyframe)

    private fun advanceFloor(id: Long) {
        if (floorId == null || Protocol.isNewer(id, floorId!!)) floorId = id
    }

    private fun drain(): List<AccessUnit> {
        val ready = ArrayList<AccessUnit>(3)
        while (true) {
            val expected = floorId?.let { (it + 1) and Protocol.UINT32_MASK }
            var next = if (!waitingForKeyframe && expected != null) pending[expected]?.takeIf { it.complete } else null
            if (next == null) {
                // An IDR is independent: it may skip an incomplete older access unit, without a codec flush.
                for (candidate in pending.values) {
                    if (candidate.complete && candidate.keyframe &&
                        (next == null || Protocol.isNewer(candidate.frameId, next.frameId))) {
                        next = candidate
                    }
                }
            }
            val frame = next ?: break
            val id = frame.frameId
            val data = ByteArray(frame.bytes)
            var offset = 0
            frame.fragments.forEach { part ->
                checkNotNull(part).copyInto(data, offset)
                offset += part.size
            }
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == id || !Protocol.isNewer(entry.key, id)) {
                    bufferedBytes -= entry.value.bytes
                    if (entry.key != id) droppedFrames++
                    iterator.remove()
                }
            }
            floorId = id
            waitingForKeyframe = false
            ready.add(AccessUnit(id, frame.timestampMs, frame.keyframe, data))
        }
        return ready
    }
}
