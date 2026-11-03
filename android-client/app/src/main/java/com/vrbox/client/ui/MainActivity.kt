package com.vrbox.client.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface as AndroidSurface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vrbox.client.network.AndroidSavedConnectionStore
import com.vrbox.client.network.ConnectionInput
import com.vrbox.client.network.SavedConnection
import com.vrbox.client.network.SavedConnectionStore
import com.vrbox.client.network.SessionStatus
import com.vrbox.client.network.VrSession
import com.vrbox.client.protocol.Protocol
import com.vrbox.client.render.VrVideoView
import com.vrbox.client.render.ViewerCalibration
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: VrSession? = null
    private var generation = 0L
    private var alive = true
    private var foreground = false
    private var recenterTask: Runnable? = null
    private var recenterSeconds by mutableStateOf(0)
    private var viewer by mutableStateOf<VrVideoView?>(null)
    private var status by mutableStateOf(SessionStatus())
    private var closing by mutableStateOf(false)
    private var savedConnection by mutableStateOf<SavedConnection?>(null)
    private var storageBusy by mutableStateOf(false)
    private var storageError by mutableStateOf<String?>(null)
    private var inputIp by mutableStateOf("")
    private var inputPin by mutableStateOf("") // Never put the PIN in an Activity saved-state bundle.
    private var calibration by mutableStateOf(ViewerCalibration())
    private var offlineCalibration by mutableStateOf(false)
    private val calibrationPreferences by lazy { getSharedPreferences("viewer_calibration", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        calibration = ViewerCalibration.fromValues(calibrationPreferences.all)
        updateSavedConnection(fillFields = true) { it.load() }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xff80dbd0), secondary = Color(0xffffc977),
                background = Color(0xff11191c), surface = Color(0xff182327)
            )) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val video = viewer
                    BackHandler(enabled = session != null || offlineCalibration) { disconnect("Disconnected") }
                    if (video == null) ConnectScreen(status, closing, savedConnection, storageBusy, storageError,
                        inputIp, inputPin, { inputIp = it }, { inputPin = it }, ::connect, ::pinConnection,
                        onTogglePinned = {
                            savedConnection?.let { saved ->
                                updateSavedConnection { it.save(saved.copy(pinned = !saved.pinned)) }
                            }
                        }, onDelete = {
                            updateSavedConnection(fillFields = true) { it.clear(); null }
                        }, onCalibrate = ::openCalibration)
                    else ViewerScreen(video, status, calibration, offlineCalibration, recenterSeconds,
                        ::changeCalibration, ::saveCalibration, ::requestRecenter, ::cancelRecenter) {
                        disconnect(if (offlineCalibration) "Calibration saved" else "Disconnected")
                    }
                }
            }
        }
    }

    private fun validatedConnection(ip: String, pin: String): SavedConnection? = try {
        SavedConnection(ip.trim(), pin)
    } catch (e: IllegalArgumentException) {
        status = SessionStatus(message = e.message ?: "Invalid IPv4 or PIN")
        null
    }

    private fun updateSavedConnection(fillFields: Boolean = false, action: (SavedConnectionStore) -> SavedConnection?) {
        if (storageBusy) return
        storageBusy = true
        storageError = null
        AndroidSavedConnectionStore.submit(applicationContext, action) { result ->
            if (!alive) return@submit
            result.onSuccess { saved ->
                savedConnection = saved
                if (fillFields) {
                    inputIp = saved?.ip.orEmpty()
                    inputPin = saved?.pin.orEmpty()
                }
            }.onFailure {
                storageError = "Cannot access the saved connection. Connect manually, retry pinning, or delete the saved entry."
            }
            storageBusy = false
        }
    }

    private fun pinConnection(ip: String, pin: String) {
        if (session != null || viewer != null || offlineCalibration || closing || storageBusy) return
        val saved = validatedConnection(ip, pin)?.copy(pinned = true) ?: return
        updateSavedConnection(fillFields = true) { it.save(saved) }
    }

    private fun changeCalibration(value: ViewerCalibration) {
        calibration = value.normalized()
        viewer?.let {
            it.renderer.calibration = calibration
            it.requestRender()
        }
    }

    private fun saveCalibration() {
        val editor = calibrationPreferences.edit()
        calibration.toValues().forEach { (name, value) -> editor.putFloat(name, value) }
        editor.apply()
    }

    private fun cancelRecenter() {
        recenterTask?.let { mainHandler.removeCallbacks(it) }
        recenterTask = null
        recenterSeconds = 0
    }

    private fun requestRecenter(delaySeconds: Int) {
        val connection = session ?: return
        val video = viewer ?: return
        if (!alive || !foreground || closing || offlineCalibration || !status.recenterAvailable || status.recenterPending) return
        cancelRecenter()
        if (delaySeconds == 0) {
            connection.recenter()
            return
        }
        val token = generation
        recenterSeconds = delaySeconds
        val task = object : Runnable {
            override fun run() {
                if (recenterTask !== this) return
                if (!alive || !foreground || generation != token || session !== connection || viewer !== video || closing ||
                    offlineCalibration || !status.recenterAvailable || status.recenterPending) {
                    cancelRecenter()
                    return
                }
                --recenterSeconds
                if (recenterSeconds == 0) {
                    recenterTask = null
                    connection.recenter()
                } else if (!mainHandler.postDelayed(this, 1000)) cancelRecenter()
            }
        }
        recenterTask = task
        if (!mainHandler.postDelayed(task, 1000)) cancelRecenter()
    }

    private fun openCalibration() {
        if (!alive || !foreground || session != null || viewer != null || closing) return
        lateinit var preview: VrVideoView
        preview = VrVideoView(this, onSurfaceReady = {}, onRenderedFrame = {}, onError = { reason ->
            mainHandler.post {
                if (alive && viewer === preview && offlineCalibration) disconnect(reason)
            }
        })
        preview.renderer.calibration = calibration
        preview.renderer.calibrationGrid = true
        offlineCalibration = true
        viewer = preview
        setViewerWindow(true)
    }

    private fun connect(ip: String, pin: String) {
        if (!alive || !foreground || session != null || viewer != null || offlineCalibration || closing || storageBusy) return
        val target = validatedConnection(ip, pin) ?: return
        val address = checkNotNull(ConnectionInput.localIpv4(target.ip))
        val rotationDegrees = when (display?.rotation) {
            AndroidSurface.ROTATION_0 -> 0
            AndroidSurface.ROTATION_90 -> 90
            AndroidSurface.ROTATION_180 -> 180
            AndroidSurface.ROTATION_270 -> 270
            else -> {
                status = SessionStatus(message = "Cannot read this Activity's display rotation. Reopen the app to connect.")
                return
            }
        }
        inputIp = target.ip
        inputPin = target.pin
        val token = ++generation
        val surfaceReady = AtomicBoolean(false)
        status = SessionStatus(message = "Preparing viewer")
        lateinit var video: VrVideoView
        lateinit var connection: VrSession
        connection = VrSession(this, address, pin, rotationDegrees,
            onStatus = { update ->
                mainHandler.post {
                    if (alive && generation == token && session === connection && !closing) status = update
                }
            }, onVideoSettings = { settings ->
                video.renderer.sourceLayout = settings.sourceLayout
                video.requestRender()
                mainHandler.post {
                    // Negotiated video means pairing succeeded; failed attempts must not replace the saved PC.
                    if (alive && generation == token && session === connection && !closing) {
                        updateSavedConnection { it.rememberSuccessful(target) }
                    }
                }
            }, onEnded = { reason ->
                mainHandler.post {
                    if (alive && generation == token && session === connection) disconnect(reason)
                }
            }, onStopped = {
                // The decoder has been stopped before releasing its SurfaceTexture/Surface.
                try {
                    video.renderer.release()
                } finally {
                    mainHandler.post {
                        if (alive && generation == token && session === connection) {
                            ++generation // Invalidate every late callback before the connection form becomes usable again.
                            cancelRecenter()
                            session = null
                            viewer = null
                            closing = false
                            setViewerWindow(false)
                        }
                    }
                }
            })
        video = VrVideoView(this,
            onSurfaceReady = {
                surfaceReady.set(true)
                connection.start(it)
            }, onRenderedFrame = connection::markRenderedFrame, onError = connection::fail)
        video.renderer.calibration = calibration
        session = connection
        viewer = video
        setViewerWindow(true)
        mainHandler.postDelayed({
            if (alive && generation == token && session === connection && !closing && !surfaceReady.get()) {
                connection.fail("OpenGL viewer surface did not become ready")
            }
        }, 5000)
    }

    private fun disconnect(reason: String) {
        cancelRecenter()
        if (offlineCalibration) {
            saveCalibration()
            offlineCalibration = false
            val preview = viewer
            viewer = null
            preview?.onPause()
            preview?.renderer?.release()
            status = SessionStatus(message = reason)
            setViewerWindow(false)
            return
        }
        if (session == null || closing) return
        saveCalibration()
        closing = true
        status = status.copy(message = reason)
        session?.close() // Only signals cancellation; network/codec cleanup runs off the UI thread.
        viewer?.onPause()
        viewer = null
        setViewerWindow(false)
    }

    private fun setViewerWindow(enabled: Boolean) {
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onPause() {
        foreground = false
        saveCalibration() // Also keep a slider drag interrupted before onValueChangeFinished.
        disconnect("Stopped when the app left the foreground. Reconnect to resume.")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onStop() {
        disconnect("Stopped in background. Reconnect to resume.")
        super.onStop()
    }

    override fun onDestroy() {
        alive = false
        foreground = false
        cancelRecenter()
        if (offlineCalibration) viewer?.renderer?.release()
        session?.close()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
private fun ConnectScreen(
    status: SessionStatus, closing: Boolean, saved: SavedConnection?, storageBusy: Boolean, storageError: String?,
    ip: String, pin: String, onIpChange: (String) -> Unit, onPinChange: (String) -> Unit,
    onConnect: (String, String) -> Unit, onPinCurrent: (String, String) -> Unit,
    onTogglePinned: () -> Unit, onDelete: () -> Unit, onCalibrate: () -> Unit
) {
    val focus = LocalFocusManager.current
    val enabled = !closing && !storageBusy
    val connect = {
        focus.clearFocus()
        if (enabled) onConnect(ip, pin)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("PC / HEADSET", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text("Connect your PC", style = MaterialTheme.typography.headlineMedium)
            Text("H.264 desktop MONO or native SteamVR stereo / 3DoF.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(if (saved?.pinned == true) "Pinned connection" else "Last connection",
                        style = MaterialTheme.typography.titleSmall)
                    Text(saved?.ip ?: "No saved connection yet", style = MaterialTheme.typography.bodyLarge)
                    if (saved != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                focus.clearFocus()
                                onConnect(saved.ip, saved.pin)
                            }, enabled = enabled) {
                                Text(if (saved.pinned) "Connect to pinned" else "Connect to last")
                            }
                            TextButton(onClick = onTogglePinned, enabled = enabled) { Text(if (saved.pinned) "Unpin" else "Pin") }
                            TextButton(onClick = onDelete, enabled = enabled) { Text("Delete") }
                        }
                    } else if (storageError != null) {
                        TextButton(onClick = onDelete, enabled = enabled) { Text("Delete saved entry") }
                    }
                    Text(if (saved?.pinned == true) "Other PCs will not replace this pinned connection."
                        else "Successful connections are remembered automatically. Pin one to keep it.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("IPv4 and PIN are stored encrypted on this phone. A restarted PC server may require a new PIN.",
                        style = MaterialTheme.typography.bodySmall)
                    if (storageBusy) Text("Updating saved connection...", style = MaterialTheme.typography.bodySmall)
                    storageError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(ip, { onIpChange(it.take(15)) }, Modifier.weight(1.6f), enabled = enabled,
                    label = { Text("PC IPv4") }, placeholder = { Text("192.168.1.10") }, singleLine = true,
                    supportingText = { Text("IPv4 only, no :port. TCP ${Protocol.PORT_TCP} is automatic.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next))
                OutlinedTextField(pin, { onPinChange(it.take(6)) }, Modifier.weight(1f), enabled = enabled,
                    label = { Text("Pairing PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { connect() }))
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = connect, enabled = enabled) { Text(if (closing) "Stopping..." else "Connect") }
                TextButton(onClick = {
                    focus.clearFocus()
                    onPinCurrent(ip, pin)
                }, enabled = enabled) { Text(if (saved == null) "Pin as last" else "Replace saved with these fields") }
                TextButton(onClick = { focus.clearFocus(); onCalibrate() }, enabled = !closing) { Text("Calibrate headset") }
            }
            Text(status.message, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Text("Local network only. Start the PC server and use its pairing PIN.\n" +
                "Select desktop or SteamVR mode on the PC. MONO is flat video. SteamVR tracks head rotation only, not position or controllers.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ViewerScreen(
    view: VrVideoView, status: SessionStatus, calibration: ViewerCalibration, offline: Boolean, recenterSeconds: Int,
    onCalibration: (ViewerCalibration) -> Unit, onSave: () -> Unit, onRecenter: (Int) -> Unit,
    onCancelRecenter: () -> Unit, onDisconnect: () -> Unit
) {
    var controlsVisible by remember(view) { mutableStateOf(true) }
    var showCalibration by remember(view) { mutableStateOf(offline) }
    var advanced by remember(view) { mutableStateOf(false) }
    var grid by remember(view) { mutableStateOf(offline) }
    var size by remember(view) { mutableStateOf(IntSize.Zero) }
    val calibrationScroll = rememberScrollState()
    val controlsScroll = rememberScrollState()
    val sourceLayout = view.renderer.sourceLayout
    val fit = if (size.width >= 2 && size.height > 0) calibration.layout(size.width, size.height, sourceLayout.eyeAspect).fitScale else 1f
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val panelHeight = maxHeight * 0.62f
        val headerHeight = if (showCalibration) maxHeight - panelHeight else maxHeight * 0.62f
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().onSizeChanged { size = it })
        if (!controlsVisible) {
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "Show viewer controls", onClick = { controlsVisible = true }
            ))
        } else {
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().heightIn(max = headerHeight)
                .background(Color(0xdd10191c)).verticalScroll(controlsScroll).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val mode = when (status.source) {
                            "steamvr" -> "SteamVR / STEREO / 3DoF"
                            "test_pattern" -> "Test pattern / " + if (status.layout == "stereo_sbs") "STEREO" else "MONO"
                            "desktop" -> "Desktop / MONO"
                            else -> "Connecting to PC"
                        }
                        Text(if (grid) "Calibration grid / same in both eyes" else mode,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (offline) Text("Offline: no PC connection", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        else {
                            val rtt = status.rttMs?.let { "${oneDecimal(it)} ms" } ?: "--"
                            Text("RTT $rtt   Decoded ${oneDecimal(status.decodedFps)} fps   Rendered ${oneDecimal(status.renderedFps)} fps",
                                color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TextButton(onClick = { showCalibration = !showCalibration }) { Text("Display") }
                    TextButton(onClick = { onSave(); controlsVisible = false }) { Text("Hide") }
                    TextButton(onClick = onDisconnect) { Text(if (offline) "Done" else "Disconnect") }
                }
                if (!offline) Text("${status.message}   ${status.format}", fontSize = 10.sp,
                    maxLines = if (status.source == "steamvr") 2 else 1, overflow = TextOverflow.Ellipsis)
                if (!offline && status.source == "steamvr") {
                    val recenterEnabled = status.recenterAvailable && !status.recenterPending && recenterSeconds == 0
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onRecenter(0) }, enabled = recenterEnabled,
                            modifier = Modifier.semantics { contentDescription = "Recenter SteamVR view now" }) { Text("Recenter") }
                        if (recenterSeconds > 0) {
                            TextButton(onClick = onCancelRecenter) { Text("Cancel recenter ($recenterSeconds s)") }
                        } else {
                            TextButton(onClick = { onRecenter(3) }, enabled = recenterEnabled,
                                modifier = Modifier.semantics { contentDescription = "Recenter SteamVR view in 3 seconds" }) {
                                Text("Recenter in 3s")
                            }
                        }
                    }
                    val recenterMessage = when {
                        recenterSeconds > 0 -> "Recentering in $recenterSeconds s. Put on the headset and look straight ahead, level."
                        !status.recenterAvailable -> "Recenter is not available in this session yet. The PC must advertise support."
                        else -> status.recenterMessage
                    }
                    if (recenterMessage.isNotEmpty()) Text(recenterMessage, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                    Text(status.sensor, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showCalibration) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = panelHeight)
                    .background(Color(0xf0182327)).verticalScroll(calibrationScroll).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            grid = !grid
                            view.renderer.calibrationGrid = grid
                            view.requestRender()
                        }, enabled = !offline) { Text(if (offline) "Offline grid" else if (grid) "Show video" else "Test grid") }
                        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Less" else "Fine alignment") }
                        TextButton(onClick = { onCalibration(ViewerCalibration()); onSave() }) { Text("Reset") }
                        TextButton(onClick = { onSave(); controlsVisible = false }) { Text("Hide controls") }
                    }
                    Text("Keep the phone flat. Hide controls to compare both eyes; tap the screen to show them again.", fontSize = 11.sp)
                    CalibrationSlider("Image size", calibration.scale, 0.35f..1f, 0.01f, percent(calibration.scale),
                        { onCalibration(calibration.copy(scale = it)) }, onSave)
                    CalibrationSlider("Image spacing", calibration.spacing, 0.6f..1.1f, 0.005f, percent(calibration.spacing),
                        { onCalibration(calibration.copy(spacing = it)) }, onSave)
                    CalibrationSlider("Image height", calibration.offsetY, -0.15f..0.15f, 0.0025f, percent(calibration.offsetY),
                        { onCalibration(calibration.copy(offsetY = it)) }, onSave)
                    if (fit < 0.995f) Text("Safety fit: ${percent(fit)} of requested size to keep both images inside their halves.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                    if (advanced) {
                        CalibrationSlider("Vertical eye balance", calibration.verticalDifference, -0.12f..0.12f, 0.001f,
                            percent(calibration.verticalDifference), { onCalibration(calibration.copy(verticalDifference = it)) }, onSave)
                        CalibrationSlider("Tilt up / down", calibration.tiltX, -12f..12f, 0.1f, degrees(calibration.tiltX),
                            { onCalibration(calibration.copy(tiltX = it)) }, onSave)
                        CalibrationSlider("Tilt left / right", calibration.tiltY, -12f..12f, 0.1f, degrees(calibration.tiltY),
                            { onCalibration(calibration.copy(tiltY = it)) }, onSave)
                        CalibrationSlider("Image rotation", calibration.rotation, -12f..12f, 0.1f, degrees(calibration.rotation),
                            { onCalibration(calibration.copy(rotation = it)) }, onSave)
                        CalibrationSlider("Eye rotation balance", calibration.rotationDifference, -8f..8f, 0.1f,
                            degrees(calibration.rotationDifference), { onCalibration(calibration.copy(rotationDifference = it)) }, onSave)
                        CalibrationSlider("Eye size balance", calibration.sizeDifference, -0.16f..0.16f, 0.005f,
                            percent(calibration.sizeDifference), { onCalibration(calibration.copy(sizeDifference = it)) }, onSave)
                        CalibrationSlider("Horizontal position", calibration.offsetX, -0.05f..0.05f, 0.001f,
                            percent(calibration.offsetX), { onCalibration(calibration.copy(offsetX = it)) }, onSave)
                        CalibrationSlider("Lens distortion", calibration.lensStrength, 0f..0.45f, 0.005f,
                            String.format(Locale.US, "%.3f", calibration.lensStrength),
                            { onCalibration(calibration.copy(lensStrength = it)) }, onSave)
                        Text("Positive eye balances: left higher, larger, or counterclockwise relative to right. " +
                            "Tilts adjust perspective, not lens focus. Leave lens distortion at zero until aligned.", fontSize = 11.sp)
                    }
                    Text("Spacing is screen-relative, not IPD in mm. Settings save after adjustment. Use small steps; stop if uncomfortable.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Display: size, alignment, perspective and test grid. Hide removes every overlay; tap to restore controls.",
                    Modifier.align(Alignment.BottomCenter).background(Color(0xbb10191c)).padding(horizontal = 12.dp, vertical = 3.dp),
                    fontSize = 10.sp, color = Color.LightGray)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CalibrationSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, valueText: String,
    onChange: (Float) -> Unit, onSave: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(0.6f)) {
            Text(label, fontSize = 12.sp)
            Text(valueText, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
        TextButton(onClick = { onChange((value - step).coerceIn(range)); onSave() },
            modifier = Modifier.semantics { contentDescription = "Decrease $label" }) { Text("-") }
        Slider(value, onValueChange = onChange, modifier = Modifier.weight(1f).semantics {
            contentDescription = label
            stateDescription = valueText
        }, valueRange = range,
            onValueChangeFinished = onSave)
        TextButton(onClick = { onChange((value + step).coerceIn(range)); onSave() },
            modifier = Modifier.semantics { contentDescription = "Increase $label" }) { Text("+") }
    }
}

private fun percent(value: Float): String = "${oneDecimal(value * 100.0)}%"
private fun degrees(value: Float): String = "${oneDecimal(value.toDouble())} deg"

private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
