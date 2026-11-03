package com.vrbox.client.render

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class EyeCalibration(
    val x: Int, val width: Int, val aspect: Float,
    val centerX: Float, val centerY: Float,
    val inverse: FloatArray
)

data class StereoCalibration(val left: EyeCalibration, val right: EyeCalibration, val fitScale: Float)

/** Screen-relative adjustments, not a measured optical profile or a physical IPD in millimeters. */
data class ViewerCalibration(
    val scale: Float = 0.8f,
    val spacing: Float = 0.85f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val verticalDifference: Float = 0f,
    val rotation: Float = 0f,
    val rotationDifference: Float = 0f,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val sizeDifference: Float = 0f,
    val lensStrength: Float = 0f
) {
    fun normalized(): ViewerCalibration {
        val defaults = ViewerCalibration()
        fun bound(value: Float, low: Float, high: Float, fallback: Float) =
            if (value.isFinite()) value.coerceIn(low, high) else fallback
        return copy(
            scale = bound(scale, 0.35f, 1f, defaults.scale),
            spacing = bound(spacing, 0.6f, 1.1f, defaults.spacing),
            offsetX = bound(offsetX, -0.05f, 0.05f, 0f),
            offsetY = bound(offsetY, -0.15f, 0.15f, 0f),
            verticalDifference = bound(verticalDifference, -0.12f, 0.12f, 0f),
            rotation = bound(rotation, -12f, 12f, 0f),
            rotationDifference = bound(rotationDifference, -8f, 8f, 0f),
            tiltX = bound(tiltX, -12f, 12f, 0f),
            tiltY = bound(tiltY, -12f, 12f, 0f),
            sizeDifference = bound(sizeDifference, -0.16f, 0.16f, 0f),
            lensStrength = bound(lensStrength, 0f, 0.45f, 0f)
        )
    }

    fun toValues(): Map<String, Float> = normalized().run { mapOf(
        "scale" to scale, "spacing" to spacing, "offsetX" to offsetX, "offsetY" to offsetY,
        "verticalDifference" to verticalDifference, "rotation" to rotation,
        "rotationDifference" to rotationDifference, "tiltX" to tiltX, "tiltY" to tiltY,
        "sizeDifference" to sizeDifference, "lensStrength" to lensStrength
    ) }

    fun layout(width: Int, height: Int, videoAspect: Float): StereoCalibration {
        require(width >= 2 && height > 0) { "Invalid viewer dimensions" }
        val settings = normalized()
        val aspect = if (videoAspect.isFinite() && videoAspect > 0f) videoAspect.coerceIn(1f / 256f, 256f) else 16f / 9f
        val leftWidth = width / 2
        val widths = intArrayOf(leftWidth, width - leftWidth)
        // Use the same physical size in both eyes, including surfaces with an odd pixel width.
        val halfWidth = min(leftWidth.toFloat() / height, aspect)
        val halfHeight = halfWidth / aspect
        val pitch = Math.toRadians(settings.tiltX.toDouble())
        val yaw = Math.toRadians(settings.tiltY.toDouble())
        val cp = cos(pitch).toFloat(); val sp = sin(pitch).toFloat()
        val cy = cos(yaw).toFloat(); val sy = sin(yaw).toFloat()
        val distance = 3f * max(1f, halfWidth)
        val centers = Array(2) { FloatArray(2) }
        val transforms = Array(2) { FloatArray(9) }
        var fit = 1f
        for (eye in 0..1) {
            val side = if (eye == 0) 1f else -1f
            val eyeAspect = widths[eye].toFloat() / height
            val x = if (eye == 0) 0 else leftWidth
            val centerPx = width * (0.5f - side * settings.spacing * 0.25f + settings.offsetX)
            // Tiny interim surfaces during resizing still need a center strictly inside each viewport.
            val centerX = ((centerPx - x) * 2f / widths[eye] - 1f).coerceIn(-0.95f, 0.95f)
            val centerY = 2f * settings.offsetY + side * settings.verticalDifference
            centers[eye][0] = centerX
            centers[eye][1] = centerY
            val roll = Math.toRadians((settings.rotation + side * settings.rotationDifference * 0.5f).toDouble())
            val cr = cos(roll).toFloat(); val sr = sin(roll).toFloat()
            val size = settings.scale * (1f + side * settings.sizeDifference * 0.5f)
            val w = halfWidth * size
            val h = halfHeight * size
            // Column-major homography: rotate the plane around X, Y, then Z, with positive perspective W.
            val m = floatArrayOf(
                cr * cy * w, sr * cy * w, sy * w / distance,
                (cr * sy * sp - sr * cp) * h, (sr * sy * sp + cr * cp) * h, -cy * sp * h / distance,
                0f, 0f, 1f
            )
            transforms[eye] = m
            for (u in floatArrayOf(-1f, 1f)) for (v in floatArrayOf(-1f, 1f)) {
                val z = m[2] * u + m[5] * v + 1f
                val dx = (m[0] * u + m[3] * v) / (z * eyeAspect)
                val dy = (m[1] * u + m[4] * v) / z
                if (dx > 0f) fit = min(fit, (1f - centerX) / dx)
                if (dx < 0f) fit = min(fit, (-1f - centerX) / dx)
                if (dy > 0f) fit = min(fit, (1f - centerY) / dy)
                if (dy < 0f) fit = min(fit, (-1f - centerY) / dy)
            }
        }
        // One safety fit for BOTH eyes preserves their intended relative sizes instead of clipping one eye.
        val eyes = (0..1).map { eye ->
            val m = transforms[eye]
            val a = m[0] * fit; val b = m[3] * fit
            val c = m[1] * fit; val d = m[4] * fit
            val determinant = a * d - b * c
            val ia = d / determinant; val ib = -b / determinant
            val ic = -c / determinant; val id = a / determinant
            EyeCalibration(if (eye == 0) 0 else leftWidth, widths[eye], widths[eye].toFloat() / height,
                centers[eye][0], centers[eye][1], floatArrayOf(
                    ia, ic, -(m[2] * ia + m[5] * ic),
                    ib, id, -(m[2] * ib + m[5] * id),
                    0f, 0f, 1f
                ))
        }
        return StereoCalibration(eyes[0], eyes[1], fit)
    }

    companion object {
        fun fromValues(values: Map<String, *>): ViewerCalibration {
            val defaults = ViewerCalibration()
            fun value(name: String, fallback: Float = 0f) = (values[name] as? Number)?.toFloat() ?: fallback
            return ViewerCalibration(value("scale", defaults.scale), value("spacing", defaults.spacing),
                value("offsetX"), value("offsetY"), value("verticalDifference"), value("rotation"),
                value("rotationDifference"), value("tiltX"), value("tiltY"), value("sizeDifference"),
                value("lensStrength")).normalized()
        }
    }
}
