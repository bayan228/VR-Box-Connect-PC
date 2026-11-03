package com.vrbox.client.render

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.vrbox.client.protocol.AccessUnit
import com.vrbox.client.protocol.AnnexB
import com.vrbox.client.protocol.DecodeQueue
import com.vrbox.client.protocol.Protocol
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class VideoDecoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val surface: Surface,
    private val onDecodedFrame: () -> Unit,
    private val onNeedKeyframe: () -> Unit,
    private val onError: (String) -> Unit
) : Closeable {
    private val queue = DecodeQueue()
    private val stopped = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var worker: Thread? = null

    fun start() = synchronized(lifecycleLock) {
        if (!stopped.get() && worker == null) {
            worker = Thread(::decode, "vr-h264-decoder").also { it.start() }
        }
    }

    fun offer(frame: AccessUnit): Boolean {
        if (stopped.get()) return false
        val accepted = queue.offer(frame)
        if (!accepted) onNeedKeyframe()
        return accepted
    }

    private fun decode() {
        var codec: MediaCodec? = null
        val info = MediaCodec.BufferInfo()
        var pending: AccessUnit? = null
        var inputWaitNs = 0L
        try {
            while (!stopped.get()) {
                codec?.let { drainOutput(it, info) }
                if (pending == null) {
                    pending = queue.poll(5) ?: continue
                    val frame = pending
                    if (!AnnexB.hasStartCode(frame.data)) {
                        dropInput()
                        pending = null
                        continue
                    }
                    if (codec == null) {
                        val csd = if (frame.keyframe) AnnexB.parameterSets(frame.data) else null
                        if (csd == null) {
                            dropInput()
                            pending = null
                            continue
                        }
                        if (stopped.get()) break
                        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Protocol.MAX_ACCESS_UNIT_SIZE)
                            setByteBuffer("csd-0", ByteBuffer.wrap(csd.sps))
                            setByteBuffer("csd-1", ByteBuffer.wrap(csd.pps))
                        }
                        val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        codec = created
                        created.configure(format, surface, null, 0)
                        created.start()
                    }
                    inputWaitNs = System.nanoTime()
                }
                val activeCodec = checkNotNull(codec)
                val frame = checkNotNull(pending)
                val index = activeCodec.dequeueInputBuffer(1000)
                if (index >= 0) {
                    val input = checkNotNull(activeCodec.getInputBuffer(index))
                    input.clear()
                    if (input.remaining() < frame.data.size) {
                        activeCodec.queueInputBuffer(index, 0, 0, 0, 0)
                        dropInput()
                    } else {
                        input.put(frame.data)
                        // Session-relative PTS, never compared with the phone's wall/monotonic clock.
                        activeCodec.queueInputBuffer(index, 0, frame.data.size, frame.timestampMs * 1000, 0)
                    }
                    pending = null
                } else if (System.nanoTime() - inputWaitNs >= 100_000_000L) {
                    dropInput()
                    pending = null
                }
            }
        } catch (e: Exception) {
            if (!stopped.get()) onError("H.264 decoder: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            queue.close()
            codec?.let {
                try { it.stop() } catch (_: Exception) { }
                try { it.release() } catch (_: Exception) { }
            }
        }
    }

    private fun drainOutput(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        repeat(8) {
            when (val index = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    val render = info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
                    codec.releaseOutputBuffer(index, render)
                    if (render) onDecodedFrame()
                }
            }
        }
    }

    private fun dropInput() {
        queue.invalidate()
        onNeedKeyframe()
        // IDRs carry SPS/PPS and recover the reference chain. Do not flush a healthy codec per IDR.
    }

    fun requestStop() {
        stopped.set(true)
        queue.close()
        worker?.interrupt()
    }

    /** Called by cleanup, never by the UI. Do not release the output Surface until the codec exits. */
    override fun close() {
        val thread = synchronized(lifecycleLock) {
            requestStop()
            worker
        }
        thread?.takeIf { it !== Thread.currentThread() }?.join()
    }
}
