package com.vrbox.client.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.vrbox.client.protocol.Protocol
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

/** Real Android device-axis rotations only; no synthetic tracking or game integration. */
class ImuSource(context: Context) : SensorEventListener, Closeable {
    data class Sample(val timestampNs: Long, val sequence: Long, val values: FloatArray)

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val latest = AtomicReference<Sample?>(null)
    private val lock = Any()
    private val gyro = FloatArray(3)
    private val accel = FloatArray(3)
    private val quaternion = FloatArray(4)
    private var gyroNs = 0L
    private var accelNs = 0L
    private var sampleBucket = -1L
    private var sequence = 0L
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var closed = false
    @Volatile private var active = false

    fun start(): String = synchronized(lock) {
        if (closed) return@synchronized "IMU stopped"
        val rotation = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (rotation == null || gyroscope == null || accelerometer == null) {
            return@synchronized "IMU unavailable: rotation, gyro and accelerometer are required. Video still works."
        }
        val thread = HandlerThread("vr-imu-sensors").also { it.start() }
        worker = thread
        val sensorHandler = Handler(thread.looper)
        handler = sensorHandler
        active = true
        val registered = listOf(rotation, gyroscope, accelerometer).map {
            manager.registerListener(this, it, 10_000, 0, sensorHandler)
        }
        if (registered.any { !it }) {
            active = false
            manager.unregisterListener(this)
            thread.quitSafely()
            return@synchronized "IMU unavailable: sensor registration failed. Video still works."
        }
        "IMU ready; waiting for real sensor samples (100 Hz target)"
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active || event.values.any { !it.isFinite() }) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> if (event.values.size >= 3) {
                event.values.copyInto(gyro, endIndex = 3)
                gyroNs = event.timestamp
            }
            Sensor.TYPE_ACCELEROMETER -> if (event.values.size >= 3) {
                event.values.copyInto(accel, endIndex = 3)
                accelNs = event.timestamp
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                if (event.values.size < 3 || gyroNs == 0L || accelNs == 0L ||
                    abs(event.timestamp - gyroNs) > 100_000_000L ||
                    abs(event.timestamp - accelNs) > 100_000_000L) return
                val bucket = event.timestamp / 10_000_000L
                if (bucket <= sampleBucket) return
                SensorManager.getQuaternionFromVector(quaternion, event.values)
                val norm = sqrt(quaternion.sumOf { (it * it).toDouble() }).toFloat()
                if (!norm.isFinite() || norm < 0.001f) return
                sampleBucket = bucket
                val values = floatArrayOf(quaternion[1] / norm, quaternion[2] / norm,
                    quaternion[3] / norm, quaternion[0] / norm,
                    gyro[0], gyro[1], gyro[2], accel[0], accel[1], accel[2])
                latest.set(Sample(event.timestamp, sequence, values))
                sequence = (sequence + 1) and Protocol.UINT32_MASK
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun takeLatest(): Sample? = if (active) latest.getAndSet(null) else null

    override fun close() {
        val thread = synchronized(lock) {
            if (closed) return
            closed = true
            active = false
            manager.unregisterListener(this)
            latest.set(null)
            handler?.removeCallbacksAndMessages(null)
            worker?.also { it.quitSafely() }
        }
        thread?.takeIf { it !== Thread.currentThread() }?.join(2000)
    }
}
