package com.vrbox.client.render

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class ViewerCalibrationTest {
    private val example = ViewerCalibration(
        scale = 0.67f, spacing = 0.93f, offsetX = 0.03f, offsetY = -0.09f,
        verticalDifference = 0.07f, rotation = -7f, rotationDifference = 3.5f,
        tiltX = 10f, tiltY = -8f, sizeDifference = 0.11f, lensStrength = 0.32f
    )
    private val minimum = ViewerCalibration(
        scale = 0.35f, spacing = 0.6f, offsetX = -0.05f, offsetY = -0.15f,
        verticalDifference = -0.12f, rotation = -12f, rotationDifference = -8f,
        tiltX = -12f, tiltY = -12f, sizeDifference = -0.16f, lensStrength = 0f
    )
    private val maximum = ViewerCalibration(
        scale = 1f, spacing = 1.1f, offsetX = 0.05f, offsetY = 0.15f,
        verticalDifference = 0.12f, rotation = 12f, rotationDifference = 8f,
        tiltX = 12f, tiltY = 12f, sizeDifference = 0.16f, lensStrength = 0.45f
    )

    @Test fun everyFieldRoundTripsWithStablePersistenceKeys() {
        val values = mapOf(
            "scale" to 0.67f, "spacing" to 0.93f, "offsetX" to 0.03f, "offsetY" to -0.09f,
            "verticalDifference" to 0.07f, "rotation" to -7f, "rotationDifference" to 3.5f,
            "tiltX" to 10f, "tiltY" to -8f, "sizeDifference" to 0.11f, "lensStrength" to 0.32f
        )
        assertEquals(values, example.toValues())
        assertEquals(example, ViewerCalibration.fromValues(values))
        assertEquals(example, ViewerCalibration.fromValues(example.toValues()))
        assertEquals(example, example.normalized())
    }

    @Test fun missingUnknownAndCorruptSettingsUseIndividualDefaults() {
        val defaults = ViewerCalibration()
        assertEquals(defaults, ViewerCalibration.fromValues(emptyMap<String, Any?>()))
        assertEquals(defaults, ViewerCalibration.fromValues(mapOf("unknown" to 123f)))
        assertEquals(example, ViewerCalibration.fromValues(example.toValues() + ("unknown" to false)))
        val values = example.toValues()
        for (key in values.keys) {
            val expected = values + (key to defaults.toValues().getValue(key))
            assertEquals("missing $key", expected, ViewerCalibration.fromValues(values - key).toValues())
            for (corrupt in listOf(null, "0.5", "broken", true, listOf(0.5f), mapOf("value" to 0.5f))) {
                val input: Map<String, Any?> = values + (key to corrupt)
                assertEquals("$key=$corrupt", expected, ViewerCalibration.fromValues(input).toValues())
            }
        }
    }

    @Test fun persistenceAcceptsOtherNumericTypes() {
        val values = mapOf(
            "scale" to 0.625, "spacing" to 1, "offsetX" to 0.03125, "offsetY" to -0.125,
            "verticalDifference" to 0.0625, "rotation" to -7L, "rotationDifference" to 4.toShort(),
            "tiltX" to 8.toByte(), "tiltY" to -3.0, "sizeDifference" to -0.125, "lensStrength" to 0.25
        )
        assertEquals(ViewerCalibration(
            scale = 0.625f, spacing = 1f, offsetX = 0.03125f, offsetY = -0.125f,
            verticalDifference = 0.0625f, rotation = -7f, rotationDifference = 4f,
            tiltX = 8f, tiltY = -3f, sizeDifference = -0.125f, lensStrength = 0.25f
        ), ViewerCalibration.fromValues(values))
    }

    @Test fun nonfiniteSettingsFallBackRatherThanClampOrAffectOtherFields() {
        val defaults = ViewerCalibration()
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(defaults, filledWith(invalid).normalized())
            assertEquals(defaults.toValues(), filledWith(invalid).toValues())
        }
        val values = example.toValues()
        for (key in values.keys) {
            for (invalid in listOf<Number>(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE, -Double.MAX_VALUE)) {
                val expected = values + (key to defaults.toValues().getValue(key))
                assertEquals("$key=$invalid", expected,
                    ViewerCalibration.fromValues(values + (key to invalid)).toValues())
            }
        }
    }

    @Test fun finiteOutOfRangeSettingsClampAtBothBoundsWithoutMutation() {
        for ((value, expected) in listOf(-Float.MAX_VALUE to minimum, Float.MAX_VALUE to maximum)) {
            val raw = filledWith(value)
            val snapshot = raw.copy()
            assertEquals(expected, raw.normalized())
            assertEquals(expected.toValues(), raw.toValues())
            assertEquals(expected, ViewerCalibration.fromValues(example.toValues().mapValues { value }))
            assertEquals(expected, expected.normalized())
            assertEquals(snapshot, raw)
        }
    }

    @Test fun layoutUsesNormalizedSettingsWithoutMutatingTheInput() {
        for (value in listOf(-Float.MAX_VALUE, Float.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY)) {
            val raw = filledWith(value)
            val snapshot = raw.copy()
            assertLayoutEquals(raw.normalized().layout(2401, 1080, 16f / 9f),
                raw.layout(2401, 1080, 16f / 9f))
            assertEquals(snapshot, raw)
        }
    }

    @Test fun defaultsUseEightyPercentSizeAndEightyFivePercentCenterSpacing() {
        val settings = ViewerCalibration()
        assertEquals(0.8f, settings.scale, 0f)
        assertEquals(0.85f, settings.spacing, 0f)
        val layout = settings.layout(2400, 1080, 16f / 9f)
        assertEquals(1f, layout.fitScale, 0f)
        assertEquals(690.0, center(layout.left, 1080).x, PIXEL_EPSILON)
        assertEquals(1710.0, center(layout.right, 1080).x, PIXEL_EPSILON)
        assertEquals(0.85 * 1200.0,
            center(layout.right, 1080).x - center(layout.left, 1080).x, PIXEL_EPSILON)
        for (eye in listOf(layout.left, layout.right)) {
            val quad = corners(eye, 1080)
            assertEquals(960.0, distance(quad[0], quad[1]), PIXEL_EPSILON)
            assertEquals(540.0, distance(quad[1], quad[2]), PIXEL_EPSILON)
            assertEquals(540.0, center(eye, 1080).y, PIXEL_EPSILON)
        }
        assertContained(layout, 2400, 1080, "defaults")
    }

    @Test fun oddWidthsKeepEqualPhysicalImagesAndSymmetricCenters() {
        val cases = listOf(ViewerCalibration(),
            ViewerCalibration(scale = 1f, spacing = 0.6f, rotation = 9f, tiltX = 12f, tiltY = -12f),
            ViewerCalibration(scale = 1f, spacing = 1.1f))
        for ((width, height) in listOf(2401 to 1080, 2713 to 1220)) {
            for (aspect in listOf(16f / 9f, 9f / 16f)) for (settings in cases) {
                val layout = settings.layout(width, height, aspect)
                val leftCenter = center(layout.left, height)
                val rightCenter = center(layout.right, height)
                val label = "${width}x$height aspect=$aspect $settings"
                assertEquals(label, width.toDouble(), leftCenter.x + rightCenter.x, PIXEL_EPSILON)
                assertEquals(label, width * settings.spacing.toDouble() / 2.0,
                    rightCenter.x - leftCenter.x, PIXEL_EPSILON)
                assertEquals(label, leftCenter.y, rightCenter.y, PIXEL_EPSILON)
                val left = corners(layout.left, height)
                val right = corners(layout.right, height)
                for (i in left.indices) {
                    assertEquals(label, left[i].x - leftCenter.x, right[i].x - rightCenter.x, PIXEL_EPSILON)
                    assertEquals(label, left[i].y - leftCenter.y, right[i].y - rightCenter.y, PIXEL_EPSILON)
                }
                assertContained(layout, width, height, label)
            }
        }
    }

    @Test fun unrotatedUntiltedImagesPreserveSourceAspectInPhysicalPixels() {
        val settings = ViewerCalibration(scale = 0.7f, spacing = 1f)
        for ((width, height) in listOf(2400 to 1080, 2401 to 1080, 1080 to 2400, 3840 to 720)) {
            for (aspect in listOf(1f / 256f, 9f / 16f, 1f, 4f / 3f, 16f / 9f, 256f)) {
                val layout = settings.layout(width, height, aspect)
                assertEquals(1f, layout.fitScale, 0f)
                for (eye in listOf(layout.left, layout.right)) {
                    val quad = corners(eye, height)
                    val label = "${width}x$height aspect=$aspect eye=${eye.x}"
                    assertEquals(label, aspect.toDouble(), distance(quad[0], quad[1]) / distance(quad[1], quad[2]),
                        aspect * VALUE_EPSILON)
                    assertEquals(label, quad[0].y, quad[1].y, PIXEL_EPSILON)
                    assertEquals(label, quad[1].x, quad[2].x, PIXEL_EPSILON)
                    assertTrue("$label source orientation", quad[1].x > quad[0].x && quad[2].y > quad[1].y)
                }
            }
        }
    }

    @Test fun spacingMovesBothEyesEquallyAndOppositely() {
        val settings = ViewerCalibration(scale = 0.35f, spacing = 0.85f)
        val baseline = settings.layout(2401, 1080, 16f / 9f)
        for (spacing in listOf(0.6f, 0.75f, 1f, 1.1f)) {
            val layout = settings.copy(spacing = spacing).layout(2401, 1080, 16f / 9f)
            val movement = 2401.0 * (spacing.toDouble() - settings.spacing) / 4.0
            assertEquals(1f, layout.fitScale, 0f)
            assertEquals(center(baseline.left, 1080).x - movement, center(layout.left, 1080).x, PIXEL_EPSILON)
            assertEquals(center(baseline.right, 1080).x + movement, center(layout.right, 1080).x, PIXEL_EPSILON)
            assertEquals(2401.0, center(layout.left, 1080).x + center(layout.right, 1080).x, PIXEL_EPSILON)
            assertArrayEquals(baseline.left.inverse, layout.left.inverse, 0f)
            assertArrayEquals(baseline.right.inverse, layout.right.inverse, 0f)
        }
    }

    @Test fun commonOffsetsTranslateBothEyesWithoutChangingSpacingOrShape() {
        val settings = ViewerCalibration(scale = 0.4f, rotation = 4f, tiltX = 6f, tiltY = -5f)
        val baseline = settings.layout(2713, 1220, 16f / 9f)
        for (offsetX in listOf(-0.05f, 0.05f)) for (offsetY in listOf(-0.15f, 0.15f)) {
            val layout = settings.copy(offsetX = offsetX, offsetY = offsetY).layout(2713, 1220, 16f / 9f)
            assertEquals(1f, baseline.fitScale, 0f)
            assertEquals(1f, layout.fitScale, 0f)
            for ((before, after) in listOf(baseline.left to layout.left, baseline.right to layout.right)) {
                assertEquals(center(before, 1220).x + 2713.0 * offsetX, center(after, 1220).x, PIXEL_EPSILON)
                assertEquals(center(before, 1220).y + 1220.0 * offsetY, center(after, 1220).y, PIXEL_EPSILON)
                val oldCorners = corners(before, 1220)
                val newCorners = corners(after, 1220)
                for (i in oldCorners.indices) {
                    assertEquals(oldCorners[i].x + 2713.0 * offsetX, newCorners[i].x, PIXEL_EPSILON)
                    assertEquals(oldCorners[i].y + 1220.0 * offsetY, newCorners[i].y, PIXEL_EPSILON)
                }
            }
            assertEquals(center(baseline.right, 1220).x - center(baseline.left, 1220).x,
                center(layout.right, 1220).x - center(layout.left, 1220).x, PIXEL_EPSILON)
        }
    }

    @Test fun verticalDifferenceBalancesEyesAroundTheCommonVerticalOffset() {
        val settings = ViewerCalibration(scale = 0.4f, offsetY = 0.08f)
        for (difference in listOf(-0.12f, 0f, 0.12f)) {
            val layout = settings.copy(verticalDifference = difference).layout(2400, 1080, 16f / 9f)
            val left = center(layout.left, 1080)
            val right = center(layout.right, 1080)
            val mean = 1080.0 * (0.5 + settings.offsetY)
            assertEquals(1f, layout.fitScale, 0f)
            assertEquals(mean + 540.0 * difference, left.y, PIXEL_EPSILON)
            assertEquals(mean - 540.0 * difference, right.y, PIXEL_EPSILON)
            assertEquals(mean, (left.y + right.y) / 2.0, PIXEL_EPSILON)
            assertEquals(1080.0 * difference, left.y - right.y, PIXEL_EPSILON)
        }
    }

    @Test fun rotationIsAspectCorrectWithEqualAndOppositeRelativeEyeRotation() {
        for ((width, height) in listOf(2400 to 1080, 1080 to 2400, 3840 to 720)) {
            val reference = ViewerCalibration(scale = 0.35f, spacing = 1f).layout(width, height, 16f / 9f)
            val unrotated = corners(reference.left, height)
            for (rotation in listOf(-12f, 0f, 12f)) for (difference in listOf(-8f, 0f, 8f)) {
                val settings = ViewerCalibration(scale = 0.35f, spacing = 1f,
                    rotation = rotation, rotationDifference = difference)
                val layout = settings.layout(width, height, 16f / 9f)
                assertEquals(1f, layout.fitScale, 0f)
                for ((eye, sign) in listOf(layout.left to 1.0, layout.right to -1.0)) {
                    val quad = corners(eye, height)
                    val horizontal = Point(quad[1].x - quad[0].x, quad[1].y - quad[0].y)
                    val vertical = Point(quad[2].x - quad[1].x, quad[2].y - quad[1].y)
                    val imageWidth = distance(quad[0], quad[1])
                    val imageHeight = distance(quad[1], quad[2])
                    val label = "${width}x$height eye=${eye.x} $settings"
                    assertEquals(label, rotation + sign * difference / 2.0,
                        Math.toDegrees(atan2(horizontal.y, horizontal.x)), VALUE_EPSILON)
                    assertEquals(label, distance(unrotated[0], unrotated[1]), imageWidth, PIXEL_EPSILON)
                    assertEquals(label, distance(unrotated[1], unrotated[2]), imageHeight, PIXEL_EPSILON)
                    assertEquals(label, (16f / 9f).toDouble(), imageWidth / imageHeight, VALUE_EPSILON)
                    assertEquals(label, 0.0, (horizontal.x * vertical.x + horizontal.y * vertical.y) /
                        (imageWidth * imageHeight), VALUE_EPSILON)
                }
            }
        }
    }

    @Test fun relativeEyeSizesUseOneSharedSafetyFitEvenWhenOnlyOneEyeIsConstrained() {
        for (width in listOf(2400, 2401, 2713)) for (difference in listOf(-0.16f, 0f, 0.16f)) {
            val settings = ViewerCalibration(scale = 1f, spacing = 0.6f, offsetX = 0.05f,
                offsetY = 0.04f, sizeDifference = difference)
            val layout = settings.layout(width, 1080, 16f / 9f)
            assertTrue("safety fit must be active", layout.fitScale > 0f && layout.fitScale < 0.9f)
            val left = corners(layout.left, 1080)
            val right = corners(layout.right, 1080)
            val leftWidth = distance(left[0], left[1])
            val rightWidth = distance(right[0], right[1])
            assertEquals((1.0 + difference / 2.0) / (1.0 - difference / 2.0),
                leftWidth / rightWidth, VALUE_EPSILON)
            assertEquals((width / 2) * (1.0 + difference / 2.0) * layout.fitScale, leftWidth, PIXEL_EPSILON)
            assertEquals((width / 2) * (1.0 - difference / 2.0) * layout.fitScale, rightWidth, PIXEL_EPSILON)
            assertEquals(leftWidth / (16f / 9f), distance(left[1], left[2]), PIXEL_EPSILON)
            assertEquals(rightWidth / (16f / 9f), distance(right[1], right[2]), PIXEL_EPSILON)
            assertEquals(layout.left.width.toDouble(), left.maxOf { it.x }, PIXEL_EPSILON)
            assertTrue("the other eye has spare room, not its own fit", right.minOf { it.x } > layout.right.x + 1.0)
            assertContained(layout, width, 1080, "shared fit width=$width difference=$difference")
        }
    }

    @Test fun pitchProducesPerspectiveByChangingOpposingHorizontalEdgeSizes() {
        for (pitch in listOf(-12f, 12f)) {
            val layout = ViewerCalibration(scale = 0.6f, spacing = 1f, tiltX = pitch).layout(2400, 1080, 16f / 9f)
            assertEquals(1f, layout.fitScale, 0f)
            for (eye in listOf(layout.left, layout.right)) {
                val quad = corners(eye, 1080)
                val bottom = distance(quad[0], quad[1])
                val top = distance(quad[3], quad[2])
                assertTrue("pitch=$pitch must change opposite edge sizes, not just rotate", pitch * (top - bottom) > 1.0)
                assertEquals(quad[0].y, quad[1].y, PIXEL_EPSILON)
                assertEquals(quad[3].y, quad[2].y, PIXEL_EPSILON)
            }
        }
    }

    @Test fun yawProducesPerspectiveByChangingOpposingVerticalEdgeSizes() {
        for (yaw in listOf(-12f, 12f)) {
            val layout = ViewerCalibration(scale = 0.6f, spacing = 1f, tiltY = yaw).layout(2400, 1080, 16f / 9f)
            assertEquals(1f, layout.fitScale, 0f)
            for (eye in listOf(layout.left, layout.right)) {
                val quad = corners(eye, 1080)
                val left = distance(quad[0], quad[3])
                val right = distance(quad[1], quad[2])
                assertTrue("yaw=$yaw must change opposite edge sizes, not just rotate", yaw * (left - right) > 1.0)
                assertEquals(quad[0].x, quad[3].x, PIXEL_EPSILON)
                assertEquals(quad[1].x, quad[2].x, PIXEL_EPSILON)
            }
        }
    }

    @Test fun extremeSettingsKeepEveryCornerInsideItsOwnEyeViewport() {
        val cases = mutableListOf(ViewerCalibration(), minimum, maximum)
        val defaults = ViewerCalibration().toValues()
        val upperValues = maximum.toValues()
        for ((key, low) in minimum.toValues()) for (value in listOf(low, upperValues.getValue(key))) {
            cases += ViewerCalibration.fromValues(defaults + (key to value))
        }
        for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (spacing in listOf(0.6f, 1.1f)) {
            cases += ViewerCalibration(scale = 1f, spacing = spacing, offsetX = x * 0.05f, offsetY = y * 0.15f,
                verticalDifference = y * 0.12f, rotation = x * 12f, rotationDifference = y * 8f,
                tiltX = y * 12f, tiltY = x * 12f, sizeDifference = x * 0.16f, lensStrength = 0.45f)
        }
        for ((width, height) in listOf(2400 to 1080, 2401 to 1080, 2713 to 1220, 1080 to 2400)) {
            for (aspect in listOf(1f / 256f, 9f / 16f, 16f / 9f, 256f)) for (settings in cases) {
                assertContained(settings.layout(width, height, aspect), width, height,
                    "extreme ${width}x$height aspect=$aspect $settings")
            }
        }
    }

    @Test fun deterministicRandomCombinationsKeepPositiveFinitePerspectiveAndStayContained() {
        val seed = 0x5642524F58L
        val random = Random(seed)
        val dimensions = listOf(2400 to 1080, 2401 to 1080, 2712 to 1220, 2713 to 1220, 1080 to 2400, 3840 to 720)
        val aspects = floatArrayOf(1f / 256f, 9f / 16f, 1f, 4f / 3f, 16f / 9f, 21f / 9f, 256f)
        val lowerValues = minimum.toValues()
        val upperValues = maximum.toValues()
        repeat(300) { index ->
            val values = lowerValues.mapValues { (key, low) -> low + random.nextFloat() * (upperValues.getValue(key) - low) }
            val settings = ViewerCalibration.fromValues(values)
            val (width, height) = dimensions[random.nextInt(dimensions.size)]
            val aspect = if (index % 2 == 0) aspects[random.nextInt(aspects.size)] else 0.25f + random.nextFloat() * 4f
            assertContained(settings.layout(width, height, aspect), width, height,
                "seed=$seed case=$index ${width}x$height aspect=$aspect $settings")
        }
    }

    @Test fun smallValidDisplaysKeepPositiveFiniteContainedGeometry() {
        val settings = ViewerCalibration(scale = 1f, spacing = 0.6f, offsetX = 0.05f)
        for (width in 2..6) for (height in 1..2) {
            assertContained(settings.layout(width, height, 16f / 9f), width, height, "small valid ${width}x$height $settings")
        }
    }

    @Test fun invalidDimensionsAreRejected() {
        for ((width, height) in listOf(-1 to 1080, 0 to 1080, 1 to 1080, 2400 to 0, 2400 to -1,
            Int.MIN_VALUE to 1080, 2400 to Int.MIN_VALUE, 0 to 0)) {
            assertThrows("${width}x$height", IllegalArgumentException::class.java) {
                ViewerCalibration().layout(width, height, 16f / 9f)
            }
        }
    }

    @Test fun invalidVideoAspectsSafelyFallBackToWidescreen() {
        val expected = example.layout(2401, 1080, 16f / 9f)
        for (aspect in listOf(0f, -0.0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val actual = example.layout(2401, 1080, aspect)
            assertLayoutEquals(expected, actual)
            assertContained(actual, 2401, 1080, "invalid aspect=$aspect")
        }
    }

    @Test fun finiteVideoAspectsClampToSafePositiveLimits() {
        for ((aspect, bounded) in listOf(Float.MIN_VALUE to (1f / 256f), 0.001f to (1f / 256f),
            512f to 256f, Float.MAX_VALUE to 256f)) {
            val actual = example.layout(2713, 1220, aspect)
            assertLayoutEquals(example.layout(2713, 1220, bounded), actual)
            assertContained(actual, 2713, 1220, "bounded aspect=$aspect")
        }
    }

    private fun filledWith(value: Float) = ViewerCalibration(
        scale = value, spacing = value, offsetX = value, offsetY = value, verticalDifference = value,
        rotation = value, rotationDifference = value, tiltX = value, tiltY = value,
        sizeDifference = value, lensStrength = value
    )

    private data class Point(val x: Double, val y: Double)

    private fun center(eye: EyeCalibration, height: Int) = Point(
        eye.x + (eye.centerX.toDouble() + 1.0) * eye.width / 2.0,
        (eye.centerY.toDouble() + 1.0) * height / 2.0
    )

    private fun distance(a: Point, b: Point) = hypot(b.x - a.x, b.y - a.y)

    // Source order: bottom-left, bottom-right, top-right, top-left; positive Y is up.
    private fun corners(eye: EyeCalibration, height: Int, label: String = "") =
        listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (u, v) ->
            project(eye, height, u, v, label)
        }

    private fun project(eye: EyeCalibration, height: Int, u: Double, v: Double, label: String): Point {
        val matrix = invert3x3(eye.inverse)
        val denominator = matrix[2][0] * u + matrix[2][1] * v + matrix[2][2]
        assertTrue("$label source=($u,$v) denominator=$denominator", denominator.isFinite() && denominator > 0.0)
        val x = (matrix[0][0] * u + matrix[0][1] * v + matrix[0][2]) / denominator
        val y = (matrix[1][0] * u + matrix[1][1] * v + matrix[1][2]) / denominator
        val center = center(eye, height)
        // Both aspect-correct axes use H/2 physical pixels, not each viewport's width.
        val point = Point(center.x + x * height / 2.0, center.y + y * height / 2.0)
        assertTrue("$label source=($u,$v) point=$point", point.x.isFinite() && point.y.isFinite())
        return point
    }

    private fun invert3x3(columnMajor: FloatArray): Array<DoubleArray> {
        assertEquals("mat3 size", 9, columnMajor.size)
        assertTrue("nonfinite inverse ${columnMajor.contentToString()}", columnMajor.all { it.isFinite() })
        // Generic pivoted Gauss-Jordan inversion, independent of the production homography derivation.
        val rows = Array(3) { row -> DoubleArray(6) { column ->
            if (column < 3) columnMajor[column * 3 + row].toDouble() else if (column - 3 == row) 1.0 else 0.0
        } }
        for (column in 0..2) {
            val pivot = (column..2).maxByOrNull { abs(rows[it][column]) }!!
            assertTrue("singular inverse ${columnMajor.contentToString()}", abs(rows[pivot][column]) > 1e-12)
            val swap = rows[column]
            rows[column] = rows[pivot]
            rows[pivot] = swap
            val divisor = rows[column][column]
            for (entry in 0..5) rows[column][entry] /= divisor
            for (row in 0..2) if (row != column) {
                val factor = rows[row][column]
                for (entry in 0..5) rows[row][entry] -= factor * rows[column][entry]
            }
        }
        return Array(3) { row -> DoubleArray(3) { column -> rows[row][column + 3] } }
    }

    private fun assertContained(layout: StereoCalibration, width: Int, height: Int, label: String) {
        assertTrue("$label fitScale=${layout.fitScale}", layout.fitScale.isFinite() && layout.fitScale > 0f && layout.fitScale <= 1f)
        assertEquals(label, 0, layout.left.x)
        assertEquals(label, width / 2, layout.left.width)
        assertEquals(label, layout.left.width, layout.right.x)
        assertEquals(label, width - layout.left.width, layout.right.width)
        for (eye in listOf(layout.left, layout.right)) {
            assertTrue("$label nonfinite center", eye.centerX.isFinite() && eye.centerY.isFinite())
            assertEquals(label, eye.width.toFloat() / height, eye.aspect, 0f)
            val origin = project(eye, height, 0.0, 0.0, "$label eye=${eye.x} origin")
            assertEquals(label, center(eye, height).x, origin.x, PIXEL_EPSILON)
            assertEquals(label, center(eye, height).y, origin.y, PIXEL_EPSILON)
            for ((index, point) in corners(eye, height, "$label eye=${eye.x}").withIndex()) {
                assertTrue("$label eye=${eye.x} corner=$index x=${point.x} outside [${eye.x},${eye.x + eye.width}]",
                    point.x >= eye.x - PIXEL_EPSILON && point.x <= eye.x + eye.width + PIXEL_EPSILON)
                assertTrue("$label eye=${eye.x} corner=$index y=${point.y} outside [0,$height]",
                    point.y >= -PIXEL_EPSILON && point.y <= height + PIXEL_EPSILON)
            }
        }
    }

    private fun assertLayoutEquals(expected: StereoCalibration, actual: StereoCalibration) {
        assertEquals(expected.fitScale, actual.fitScale, 0f)
        for ((a, b) in listOf(expected.left to actual.left, expected.right to actual.right)) {
            assertEquals(a.x, b.x)
            assertEquals(a.width, b.width)
            assertEquals(a.aspect, b.aspect, 0f)
            assertEquals(a.centerX, b.centerX, 0f)
            assertEquals(a.centerY, b.centerY, 0f)
            assertArrayEquals(a.inverse, b.inverse, 0f)
        }
    }

    private companion object {
        // Subpixel tolerance allows Float roundoff without hiding a one-pixel odd-width error.
        const val PIXEL_EPSILON = 0.002
        const val VALUE_EPSILON = 1e-5
    }
}
