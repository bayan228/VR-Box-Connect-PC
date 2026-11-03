package com.vrbox.client.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VrVideoView(
    context: Context,
    onSurfaceReady: (Surface) -> Unit,
    onRenderedFrame: () -> Unit,
    onError: (String) -> Unit
) : GLSurfaceView(context) {
    val renderer = VrRenderer({ requestRender() }, onSurfaceReady, onRenderedFrame, onError)

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}

/** One decoded MONO or SBS texture, with source crops separate from calibrated screen geometry. */
class VrRenderer(
    private val requestRender: () -> Unit,
    private val onSurfaceReady: (Surface) -> Unit,
    private val onRenderedFrame: () -> Unit,
    private val onError: (String) -> Unit
) : GLSurfaceView.Renderer {
    private val lock = Any()
    private val frameAvailable = AtomicBoolean(false)
    private var released = false
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var textureId = 0
    private var program = 0
    private var positionLocation = 0
    private var inverseLocation = 0
    private var centerLocation = 0
    private var eyeAspectLocation = 0
    private var videoAspectLocation = 0
    private var gridLocation = 0
    private var matrixLocation = 0
    private var lensLocation = 0
    private var cropLocation = 0
    private var cropBoundsLocation = 0
    private var width = 1
    private var height = 1
    private var hasFrame = false
    private var lastTimestamp = Long.MIN_VALUE
    private var cachedLayout: StereoCalibration? = null
    private var cachedCalibration: ViewerCalibration? = null
    private var cachedAspect = 0f
    private var cachedWidth = 0
    private var cachedHeight = 0
    private val textureMatrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }
    @Volatile var calibration = ViewerCalibration()
    @Volatile var calibrationGrid = false
    @Volatile var sourceLayout = SourceLayout(1280, 720, "mono", "desktop")

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        var ready: Surface? = null
        try {
            synchronized(lock) {
                if (released) return
                check(surfaceTexture == null) { "Graphics surface changed; reconnect to restart the decoder" }
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                program = createProgram()
                positionLocation = GLES20.glGetAttribLocation(program, "a_position")
                inverseLocation = GLES20.glGetUniformLocation(program, "u_inverse")
                centerLocation = GLES20.glGetUniformLocation(program, "u_center")
                eyeAspectLocation = GLES20.glGetUniformLocation(program, "u_eye_aspect")
                videoAspectLocation = GLES20.glGetUniformLocation(program, "u_video_aspect")
                gridLocation = GLES20.glGetUniformLocation(program, "u_grid")
                matrixLocation = GLES20.glGetUniformLocation(program, "u_texture_matrix")
                lensLocation = GLES20.glGetUniformLocation(program, "u_k1")
                cropLocation = GLES20.glGetUniformLocation(program, "u_crop")
                cropBoundsLocation = GLES20.glGetUniformLocation(program, "u_crop_bounds")
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                textureId = textures[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                surfaceTexture = SurfaceTexture(textureId).also { texture ->
                    texture.setOnFrameAvailableListener {
                        frameAvailable.set(true)
                        requestRender()
                    }
                }
                surface = Surface(surfaceTexture).also { ready = it }
                GLES20.glUseProgram(program)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_video"), 0)
            }
            ready?.let(onSurfaceReady)
        } catch (e: Exception) {
            onError("OpenGL viewer: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(2)
        this.height = height.coerceAtLeast(1)
        requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        try {
            synchronized(lock) {
                if (released) return
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val texture = surfaceTexture ?: return
                var newFrame = false
                if (frameAvailable.getAndSet(false)) {
                    texture.updateTexImage()
                    texture.getTransformMatrix(textureMatrix)
                    newFrame = !hasFrame || texture.timestamp != lastTimestamp
                    lastTimestamp = texture.timestamp
                    hasFrame = true
                }
                val grid = calibrationGrid
                if (!hasFrame && !grid) return
                val settings = calibration.normalized()
                val source = sourceLayout // Both eyes use this same layout/dimension snapshot.
                val aspect = source.eyeAspect
                if (cachedCalibration != settings || cachedAspect != aspect || cachedWidth != width || cachedHeight != height) {
                    cachedLayout = settings.layout(width, height, aspect)
                    cachedCalibration = settings
                    cachedAspect = aspect
                    cachedWidth = width
                    cachedHeight = height
                }
                val layout = checkNotNull(cachedLayout)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix, 0)
                GLES20.glUniform1f(lensLocation, settings.lensStrength)
                GLES20.glUniform1f(videoAspectLocation, aspect)
                GLES20.glUniform1i(gridLocation, if (grid) 1 else 0)
                vertices.position(0)
                GLES20.glEnableVertexAttribArray(positionLocation)
                GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                try {
                    drawEye(layout.left, source.left)
                    drawEye(layout.right, source.right)
                } finally {
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                    GLES20.glDisableVertexAttribArray(positionLocation)
                }
                if (newFrame && !grid) onRenderedFrame()
            }
        } catch (e: Exception) {
            onError("Video texture: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun drawEye(eye: EyeCalibration, crop: EyeTextureCrop) {
        GLES20.glViewport(eye.x, 0, eye.width, height)
        GLES20.glScissor(eye.x, 0, eye.width, height)
        GLES20.glUniformMatrix3fv(inverseLocation, 1, false, eye.inverse, 0)
        GLES20.glUniform2f(centerLocation, eye.centerX, eye.centerY)
        GLES20.glUniform1f(eyeAspectLocation, eye.aspect)
        GLES20.glUniform2f(cropLocation, crop.offsetU, crop.scaleU)
        GLES20.glUniform4f(cropBoundsLocation, crop.minU, crop.minV, crop.maxU, crop.maxV)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** After decoder cleanup. GL objects belong to the view's EGL context, destroyed on detach. */
    fun release() = synchronized(lock) {
        if (released) return@synchronized
        released = true
        surfaceTexture?.setOnFrameAvailableListener(null)
        surface?.release()
        surfaceTexture?.release()
        surface = null
        surfaceTexture = null
    }

    private fun createProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 a_position;
            varying mediump vec2 v_screen;
            void main() {
                v_screen = a_position;
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        """.trimIndent())
        val fragment = try {
            compileShader(GLES20.GL_FRAGMENT_SHADER, """
                #extension GL_OES_EGL_image_external : require
                #ifdef GL_FRAGMENT_PRECISION_HIGH
                precision highp float;
                #else
                precision mediump float;
                #endif
                uniform samplerExternalOES u_video;
                uniform mat4 u_texture_matrix;
                uniform mat3 u_inverse;
                uniform vec2 u_center;
                uniform float u_eye_aspect;
                uniform float u_video_aspect;
                uniform float u_k1;
                uniform int u_grid;
                uniform vec2 u_crop;
                uniform vec4 u_crop_bounds;
                varying mediump vec2 v_screen;
                void main() {
                    // Apply radial correction about the calibrated lens center in aspect-correct screen coordinates.
                    vec2 centered = (v_screen - u_center) * vec2(u_eye_aspect, 1.0);
                    vec2 corrected = centered * (1.0 + u_k1 * dot(centered, centered));
                    vec3 source = u_inverse * vec3(corrected, 1.0);
                    if (source.z <= 0.0001) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                        return;
                    }
                    vec2 uv = source.xy / source.z * 0.5 + 0.5;
                    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    } else if (u_grid != 0) {
                        vec2 p = (uv - 0.5) * vec2(u_video_aspect, 1.0);
                        vec2 cells = abs(fract(p * 8.0 + 0.5) - 0.5);
                        vec3 color = mix(vec3(0.025, 0.045, 0.055), vec3(0.25, 0.55, 0.55),
                            1.0 - step(0.025, min(cells.x, cells.y)));
                        if (min(abs(p.x), abs(p.y)) < 0.003 || abs(length(p) - 0.12) < 0.004) {
                            color = vec3(1.0, 0.8, 0.35);
                        }
                        if (min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y)) < 0.005) {
                            color = vec3(0.5, 0.9, 0.85);
                        }
                        gl_FragColor = vec4(color, 1.0);
                    } else {
                        // Crop in decoded-image coordinates, not in the transformed external texture.
                        vec2 cropped_uv = vec2(u_crop.x + uv.x * u_crop.y, uv.y);
                        cropped_uv = clamp(cropped_uv, u_crop_bounds.xy, u_crop_bounds.zw);
                        #ifndef GL_FRAGMENT_PRECISION_HIGH
                        // A mediump-only GPU can round a half-texel bound onto the seam at large widths.
                        if (u_crop.y < 1.0) {
                            cropped_uv.x = clamp(cropped_uv.x, u_crop.x + 0.001953125,
                                u_crop.x + u_crop.y - 0.001953125);
                        }
                        #endif
                        vec2 sample_uv = (u_texture_matrix * vec4(cropped_uv, 0.0, 1.0)).xy;
                        gl_FragColor = texture2D(u_video, sample_uv);
                    }
                }
            """.trimIndent())
        } catch (e: Exception) {
            GLES20.glDeleteShader(vertex)
            throw e
        }
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val reason = GLES20.glGetProgramInfoLog(result)
            GLES20.glDeleteProgram(result)
            error(reason)
        }
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val reason = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error(reason)
        }
        return shader
    }
}
