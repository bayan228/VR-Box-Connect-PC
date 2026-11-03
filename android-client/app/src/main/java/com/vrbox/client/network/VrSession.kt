package com.vrbox.client.network

import android.content.Context
import android.os.Build
import android.view.Surface
import com.vrbox.client.protocol.Protocol
import com.vrbox.client.render.SourceLayout
import com.vrbox.client.render.VideoDecoder
import com.vrbox.client.sensor.ImuSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class VideoSettings(
    val width: Int, val height: Int, val fps: Int, val bitrateKbps: Int, val source: String, val layout: String
) {
    val sourceLayout = SourceLayout(width, height, layout, source)

    companion object {
        fun parse(json: JSONObject): VideoSettings {
            require(json.getString("codec") == "h264") { "Only H.264 video is supported" }
            require(json.getInt("b_frames") == 0 && json.getInt("resilience") == 1 && json.getDouble("gop_s") == 1.0) {
                "Unsupported H.264 stream configuration"
            }
            val width = json.getInt("width")
            val height = json.getInt("height")
            val fps = json.getInt("fps")
            val bitrate = json.getInt("bitrate_kbps")
            val source = json.getString("source")
            require(fps in 1..120 && bitrate in 1..100000) { "Invalid negotiated video rate" }
            return VideoSettings(width, height, fps, bitrate, source, json.getString("layout"))
        }
    }
}

data class SessionStatus(
    val message: String = "Disconnected",
    val source: String = "",
    val layout: String = "",
    val format: String = "",
    val sensor: String = "IMU not started",
    val rttMs: Double? = null,
    val decodedFps: Double = 0.0,
    val renderedFps: Double = 0.0,
    val recenterAvailable: Boolean = false,
    val recenterPending: Boolean = false,
    val recenterMessage: String = ""
)

/** Activity-owned: nothing survives close/background/configuration change. No service is needed. */
class VrSession(
    context: Context,
    private val addressBytes: ByteArray,
    private val pin: String,
    private val displayRotation: Int,
    private val onStatus: (SessionStatus) -> Unit,
    private val onVideoSettings: (VideoSettings) -> Unit,
    private val onEnded: (String) -> Unit,
    private val onStopped: () -> Unit
) {
    private enum class Phase { HELLO, SETTINGS, VIDEO, STREAMING }
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val resources = Any()
    private val stateLock = Any()
    private var state = SessionStatus(message = "Preparing viewer")
    private val keyframeNeeded = AtomicBoolean(true)
    private val recenterPending = AtomicBoolean(false)
    private val decodedFrames = AtomicLong(0)
    private val renderedFrames = AtomicLong(0)
    private val lastDecodedNs = AtomicLong(0)
    private val sender = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "vr-session-sender") }
    @Volatile private var control: VrControlClient? = null
    @Volatile private var udp: VrUdpClient? = null
    @Volatile private var decoder: VideoDecoder? = null
    @Volatile private var imu: ImuSource? = null
    @Volatile private var reader: Thread? = null
    @Volatile private var phase = Phase.HELLO
    @Volatile private var phaseStartedNs = 0L
    @Volatile private var lastControlNs = 0L
    @Volatile private var videoStartedNs = 0L
    @Volatile private var settings: VideoSettings? = null
    @Volatile private var recenterFeature = false
    @Volatile private var recenterSentNs = 0L
    private var imuAcknowledged = false
    private var imuSamples = 0L
    private var lastTimingNs = 0L
    private var lastPingNs = 0L
    private var lastKeyframeRequestNs = 0L
    private var lastMetricsNs = 0L
    private var previousDecoded = 0L
    private var previousRendered = 0L

    init {
        require(addressBytes.size == 4 && ConnectionInput.validPin(pin))
        require(displayRotation in setOf(0, 90, 180, 270)) { "Display rotation must be in degrees" }
        sender.removeOnCancelPolicy = true
        sender.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        sender.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }

    fun start(surface: Surface) {
        synchronized(resources) {
            if (closed.get()) return
            if (reader != null) {
                fail("Viewer surface changed; reconnect")
                return
            }
            reader = Thread({ run(surface) }, "vr-control-reader").also { it.start() }
        }
    }

    fun markRenderedFrame() { if (!closed.get()) renderedFrames.incrementAndGet() }

    private fun canRecenter(): Boolean = !closed.get() && phase == Phase.STREAMING &&
        settings?.source == "steamvr" && recenterFeature

    /** At most one queued/in-flight request; socket writes never run on the Activity thread. */
    fun recenter(): Boolean {
        if (!canRecenter() || !recenterPending.compareAndSet(false, true)) return false
        update { copy(recenterPending = true, recenterMessage = "Waiting for recenter acknowledgement") }
        return try {
            sender.execute {
                if (!canRecenter()) {
                    recenterSentNs = 0L
                    recenterPending.set(false)
                    update { copy(recenterPending = false) }
                    return@execute
                }
                try {
                    recenterSentNs = System.nanoTime()
                    checkNotNull(control).send(Protocol.CtrlType.RECENTER, "{}")
                } catch (e: Exception) {
                    if (!closed.get()) fail("Recenter send failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            recenterSentNs = 0L
            recenterPending.set(false)
            update { copy(recenterPending = false, recenterMessage = "Session is closing") }
            false
        }
    }

    private fun run(surface: Surface) {
        try {
            val address = InetAddress.getByAddress(addressBytes) // Never hostname DNS.
            val tcp = VrControlClient(address)
            val datagrams = VrUdpClient(address, onAccessUnit = { frame ->
                if (!closed.get() && decoder?.offer(frame) != true) {
                    udp?.requireKeyframe()
                    keyframeNeeded.set(true)
                }
            }, onNeedKeyframe = { keyframeNeeded.set(true) }, onError = ::fail)
            synchronized(resources) {
                if (closed.get()) {
                    tcp.close()
                    datagrams.close()
                    return
                }
                control = tcp
                udp = datagrams
            }
            update { copy(message = "Connecting to PC") }
            datagrams.open()
            if (closed.get()) return
            tcp.connect()
            if (closed.get()) return
            val hello = JSONObject().put("proto", 1).put("device", Build.MODEL.take(80))
                .put("pin", pin).put("video_port", datagrams.videoPort).put("imu_port", datagrams.imuPort)
                .put("layouts", JSONArray().put("mono").put("stereo_sbs"))
                // IMU samples stay in device sensor axes; only the native server maps this rotation.
                .put("display_rotation", displayRotation)
            tcp.send(Protocol.CtrlType.HELLO, hello.toString())
            val now = System.nanoTime()
            phaseStartedNs = now
            lastControlNs = now
            lastPingNs = now
            lastMetricsNs = now
            sender.scheduleAtFixedRate(::tick, 0, 10, TimeUnit.MILLISECONDS)
            update { copy(message = "Pairing with PC") }
            while (!closed.get()) {
                val message = tcp.read()
                lastControlNs = System.nanoTime()
                if (!closed.get()) handle(message, surface)
            }
        } catch (_: EOFException) {
            if (!closed.get()) fail("PC closed the control connection unexpectedly")
        } catch (e: Exception) {
            if (!closed.get()) fail(e.message ?: "Connection failed: ${e.javaClass.simpleName}")
        } finally {
            close()
        }
    }

    private fun handle(message: Protocol.ControlMessage, surface: Surface) {
        val tcp = checkNotNull(control)
        when (message.type) {
            Protocol.CtrlType.ERROR -> {
                val error = json(message)
                fail("PC error ${error.optString("code", "?")}: ${error.optString("msg", "Request rejected").take(300)}")
            }
            Protocol.CtrlType.SESSION_END -> fail("PC ended the session")
            Protocol.CtrlType.PING -> require(message.payload.size == 8) { "Invalid TCP ping echo" }
            Protocol.CtrlType.TELEMETRY -> Unit // Advertised server FPS is not decoded/rendered FPS.
            Protocol.CtrlType.HELLO_ACK -> {
                require(phase == Phase.HELLO) { "Unexpected HELLO_ACK" }
                val ack = json(message)
                require(ack.getInt("proto") == 1) { "Unsupported PC protocol version" }
                val codecs = ack.getJSONArray("codecs")
                require((0 until codecs.length()).any { codecs.getString(it) == "h264" }) { "PC does not offer H.264" }
                val ports = ack.getJSONObject("ports")
                require(ports.getInt("tcp") == Protocol.PORT_TCP && ports.getInt("video") == Protocol.PORT_VIDEO &&
                    ports.getInt("imu") == Protocol.PORT_IMU) { "Unexpected PC port configuration" }
                recenterFeature = ack.optJSONArray("features")?.let { features ->
                    (0 until features.length()).any { features.optString(it) == "recenter" }
                } == true
                setPhase(Phase.SETTINGS)
                tcp.send(Protocol.CtrlType.SETTINGS_REQUEST, JSONObject().put("codec", "h264")
                    .put("width", 1280).put("height", 720).put("fps", 30).put("bitrate_kbps", 8000).toString())
                update { copy(message = "Negotiating H.264 video") }
            }
            Protocol.CtrlType.SETTINGS_ACK -> {
                val ack = json(message)
                if (ack.has("width")) {
                    require(phase == Phase.SETTINGS && settings == null) { "Unexpected/repeated video settings" }
                    val video = VideoSettings.parse(ack)
                    settings = video
                    setPhase(Phase.VIDEO)
                    update { copy(message = if (video.source == "steamvr")
                        "Waiting for SteamVR capture and encoder; no desktop fallback"
                        else "PC is opening capture and encoder", source = video.source, layout = video.layout) }
                } else if (ack.has("recentered")) {
                    require(canRecenter() && recenterPending.get()) { "Unexpected recenter acknowledgement" }
                    val recentered = ack.getBoolean("recentered")
                    recenterSentNs = 0L
                    recenterPending.set(false)
                    update { copy(recenterPending = false, recenterMessage = if (recentered) "View recentered"
                        else "Not recentered: ${ack.optString("reason", "Waiting for fresh head tracking").take(160)}") }
                } else {
                    require(phase == Phase.STREAMING && !imuAcknowledged && ack.getInt("imu_rate_hz") == 100) {
                        "Unexpected IMU settings acknowledgement"
                    }
                    imuAcknowledged = true
                    val sensorStatus = try { imu?.start() ?: "IMU unavailable" } catch (e: Exception) {
                        imu?.close()
                        "IMU unavailable: ${e.message ?: e.javaClass.simpleName}. Video still works."
                    }
                    update { copy(sensor = sensorStatus) }
                }
            }
            Protocol.CtrlType.VIDEO_START -> {
                require(phase == Phase.VIDEO) { "Unexpected VIDEO_START" }
                val actual = VideoSettings.parse(json(message))
                require(actual == settings) { "VIDEO_START does not match negotiated settings" }
                val videoDecoder = VideoDecoder(actual.width, actual.height, actual.fps, surface,
                    onDecodedFrame = {
                        decodedFrames.incrementAndGet()
                        lastDecodedNs.set(System.nanoTime())
                    }, onNeedKeyframe = {
                        udp?.requireKeyframe()
                        keyframeNeeded.set(true)
                    }, onError = ::fail)
                val sensors = ImuSource(appContext)
                synchronized(resources) {
                    if (closed.get()) return
                    decoder = videoDecoder
                    imu = sensors
                    // Publish the complete source geometry before any decoded frame can reach the renderer.
                    onVideoSettings(actual)
                    if (closed.get()) return
                    videoDecoder.start()
                }
                if (closed.get()) return
                videoStartedNs = System.nanoTime()
                udp?.requireKeyframe()
                keyframeNeeded.set(true)
                setPhase(Phase.STREAMING)
                update { copy(message = if (actual.source == "steamvr")
                    "Waiting for SteamVR compositor (up to 65 s); no desktop fallback" else "Waiting for H.264 keyframe",
                    source = actual.source, layout = actual.layout, recenterAvailable = canRecenter(),
                    format = "${actual.width}x${actual.height} total / ${actual.fps} fps target / " +
                        if (actual.sourceLayout.stereo) "${actual.sourceLayout.eyeWidth}x${actual.height} per eye" else "MONO") }
                tcp.send(Protocol.CtrlType.IMU_START, JSONObject().put("imu_rate_hz", 100).toString())
            }
            else -> error("Unexpected PC control message: ${message.type}")
        }
    }

    private fun json(message: Protocol.ControlMessage): JSONObject {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(message.payload)).toString()
        return JSONObject(text)
    }

    private fun setPhase(next: Phase) {
        phaseStartedNs = System.nanoTime()
        phase = next
    }

    private fun tick() {
        if (closed.get()) return
        try {
            val tcp = control ?: return
            val datagrams = udp ?: return
            val now = System.nanoTime()
            val steamVr = settings?.source == "steamvr"
            if (recenterPending.get() && recenterSentNs != 0L && now - recenterSentNs > 5_000_000_000L) {
                // Without request IDs, retrying could mistake a late ACK for the next request.
                fail("Recenter acknowledgement timed out; reconnect to the PC")
                return
            }
            val handshakeTimeout = if (phase == Phase.VIDEO && steamVr) 65_000_000_000L else 8_000_000_000L
            if ((phase != Phase.STREAMING && now - phaseStartedNs > handshakeTimeout) ||
                now - lastControlNs > 10_000_000_000L) {
                fail("PC control/handshake timed out")
                return
            }
            if (now - lastPingNs >= 1_000_000_000L) {
                val payload = ByteArray(8)
                Protocol.put64(payload, 0, now)
                tcp.send(Protocol.CtrlType.PING, payload)
                lastPingNs = now
            }
            if (phase != Phase.HELLO && now - lastTimingNs >= 100_000_000L) {
                datagrams.sendTiming(now)
                lastTimingNs = now
            }
            if (phase == Phase.STREAMING) {
                val lastDecoded = lastDecodedNs.get()
                val lastVideo = lastDecoded.takeIf { it != 0L } ?: videoStartedNs
                val steamVrStartup = steamVr && lastDecoded == 0L
                val videoTimeout = if (steamVrStartup) 65_000_000_000L else 10_000_000_000L
                if (now - lastVideo > videoTimeout) {
                    fail(if (steamVrStartup) "No SteamVR frame after 65 s. Check SteamVR, the VR app and PC driver. No desktop fallback."
                        else "No decoded video for 10 s. Check the PC encoder, UDP ports and firewall.")
                    return
                }
                if (now - lastVideo > 1_000_000_000L) keyframeNeeded.set(true)
                if (keyframeNeeded.get() && (lastKeyframeRequestNs == 0L || now - lastKeyframeRequestNs >= 500_000_000L)) {
                    keyframeNeeded.set(false)
                    tcp.send(Protocol.CtrlType.KEYFRAME_REQUEST)
                    lastKeyframeRequestNs = now
                }
                imu?.takeLatest()?.let {
                    datagrams.sendImu(it.timestampNs, it.sequence, it.values)
                    imuSamples++
                }
            }
            if (now - lastMetricsNs >= 1_000_000_000L) {
                val seconds = (now - lastMetricsNs) / 1_000_000_000.0
                val decoded = decodedFrames.get()
                val rendered = renderedFrames.get()
                update {
                    copy(rttMs = datagrams.rttMilliseconds(now), decodedFps = (decoded - previousDecoded) / seconds,
                        renderedFps = (rendered - previousRendered) / seconds,
                        message = if (phase == Phase.STREAMING) {
                            if (steamVr && decoded == 0L) "Waiting for SteamVR compositor (up to 65 s); no desktop fallback"
                            else if (now - lastDecodedNs.get() <= 1_500_000_000L && decoded > 0) {
                                if (steamVr) "Streaming SteamVR stereo / 3DoF"
                                else if (settings?.sourceLayout?.stereo == true) "Streaming stereo SBS video"
                                else "Streaming MONO video"
                            }
                            else "Waiting for video/keyframe recovery"
                        } else message,
                        sensor = if (imuSamples > 0) "IMU sent: $imuSamples samples (100 Hz target)" else sensor)
                }
                previousDecoded = decoded
                previousRendered = rendered
                lastMetricsNs = now
            }
        } catch (e: Exception) {
            if (!closed.get()) fail("Connection send failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun update(change: SessionStatus.() -> SessionStatus) = synchronized(stateLock) {
        if (!closed.get()) {
            state = state.change()
            onStatus(state)
        }
    }

    fun fail(reason: String) = shutdown(reason)
    fun close() = shutdown(null)

    private fun shutdown(reason: String?) {
        if (!closed.compareAndSet(false, true)) return
        recenterPending.set(false)
        decoder?.requestStop()
        sender.shutdownNow()
        if (reason != null) onEnded(reason)
        Thread({
            try {
                // Socket close must not wait for the synchronized writer, and never runs on the UI.
                runCatching { synchronized(resources) { control?.close() } }
                runCatching { udp?.close() }
                runCatching { imu?.close() }
                runCatching { decoder?.close() }
                runCatching { reader?.takeIf { it !== Thread.currentThread() }?.join(2000) }
                runCatching { sender.awaitTermination(2000, TimeUnit.MILLISECONDS) }
            } finally {
                onStopped()
            }
        }, "vr-session-cleanup").start()
    }
}
