package com.vrbox.client.render

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

class SourceLayoutTest {
    @Test fun acceptsOnlySupportedSourceLayoutCombinations() {
        for ((source, layout) in listOf("desktop" to "mono", "test_pattern" to "mono",
            "test_pattern" to "stereo_sbs", "steamvr" to "stereo_sbs")) {
            assertEquals(source, SourceLayout(1280, 720, layout, source).source)
        }
        for ((source, layout) in listOf("desktop" to "stereo_sbs", "steamvr" to "mono",
            "camera" to "mono", "desktop" to "stereo", "steamvr" to "STEREO_SBS", "" to "mono")) {
            assertThrows(IllegalArgumentException::class.java) { SourceLayout(1280, 720, layout, source) }
        }
    }

    @Test fun preservesExistingEncodedDimensionAndPixelCountBounds() {
        for ((width, height) in listOf(16 to 16, 3840 to 2160, 4096 to 2024, 16 to 4096)) {
            assertEquals(width, SourceLayout(width, height, "mono", "desktop").width)
            assertEquals(height, SourceLayout(width, height, "stereo_sbs", "steamvr").height)
        }
        for ((width, height) in listOf(0 to 720, 14 to 720, 1280 to 14, 1281 to 720, 1280 to 721,
            4098 to 16, 16 to 4098, 4096 to 2160, Int.MAX_VALUE to Int.MAX_VALUE, -16 to 720)) {
            for (layout in listOf("mono", "stereo_sbs")) {
                assertThrows("$width x $height / $layout", IllegalArgumentException::class.java) {
                    SourceLayout(width, height, layout, "test_pattern")
                }
            }
        }
    }

    @Test fun stereoRequiresEvenWidthForEachEyeButMonoStillAcceptsTwoModuloFour() {
        for (width in listOf(18, 642, 1278, 1918, 4094)) {
            assertEquals(width, SourceLayout(width, 720, "mono", "desktop").eyeWidth)
            assertThrows(IllegalArgumentException::class.java) { SourceLayout(width, 720, "stereo_sbs", "steamvr") }
        }
        for (width in listOf(16, 20, 640, 1280, 1920, 3840, 4096)) {
            assertEquals(0, SourceLayout(width, 720, "stereo_sbs", "steamvr").eyeWidth % 2)
        }
    }

    @Test fun aspectUsesFullFrameForMonoAndHalfFrameForStereo() {
        val mono = SourceLayout(2560, 720, "mono", "desktop")
        val stereo = SourceLayout(2560, 720, "stereo_sbs", "steamvr")
        assertEquals(2560, mono.eyeWidth)
        assertEquals(1280, stereo.eyeWidth)
        assertEquals(32f / 9f, mono.eyeAspect, 0f)
        assertEquals(16f / 9f, stereo.eyeAspect, 0f)
        assertEquals(mono.eyeAspect / 2f, stereo.eyeAspect, 0f)
        assertEquals(1f, SourceLayout(1440, 720, "stereo_sbs", "steamvr").eyeAspect, 0f)
        assertEquals(8f / 9f, SourceLayout(1280, 720, "stereo_sbs", "steamvr").eyeAspect, 0f)
    }

    @Test fun monoUsesTheSameFullTextureForBothEyes() {
        val image = SourceLayout(1920, 1080, "mono", "desktop")
        assertFalse(image.stereo)
        assertEquals(image.left, image.right)
        assertEquals(0f, image.left.offsetU, 0f)
        assertEquals(1f, image.left.scaleU, 0f)
        assertEquals(0.5f, sampleU(image.left, 0.5f), 0f)
    }

    @Test fun stereoUsesDifferentHalvesWithMatchingLocalCoordinates() {
        val image = SourceLayout(2560, 720, "stereo_sbs", "steamvr")
        assertTrue(image.stereo)
        assertNotEquals(image.left, image.right)
        assertEquals(0f, image.left.offsetU, 0f)
        assertEquals(0.5f, image.right.offsetU, 0f)
        assertEquals(0.5f, image.left.scaleU, 0f)
        assertEquals(image.left.scaleU, image.right.scaleU, 0f)
        assertEquals(0.25f, sampleU(image.left, 0.5f), 0f)
        assertEquals(0.75f, sampleU(image.right, 0.5f), 0f)
        for (local in listOf(0.125f, 0.25f, 0.5f, 0.75f, 0.875f)) {
            assertEquals(0.5f, sampleU(image.right, local) - sampleU(image.left, local), 0f)
        }
        assertEquals(image.left.minV, image.right.minV, 0f)
        assertEquals(image.left.maxV, image.right.maxV, 0f)
    }

    @Test fun seamAndOuterEdgesStayWithinOwnTexelCenters() {
        for (width in listOf(16, 20, 24, 640, 1280, 1920, 2560, 3840, 4096)) {
            val image = SourceLayout(width, 720, "stereo_sbs", "steamvr")
            val halfTexel = 0.5 / width
            assertTrue(image.left.minU.toDouble() >= halfTexel)
            assertTrue(image.left.maxU.toDouble() <= 0.5 - halfTexel)
            assertTrue(image.right.minU.toDouble() >= 0.5 + halfTexel)
            assertTrue(image.right.maxU.toDouble() <= 1.0 - halfTexel)
            assertEquals(0.5 - halfTexel, image.left.maxU.toDouble(), 0.0000001)
            assertEquals(0.5 + halfTexel, image.right.minU.toDouble(), 0.0000001)
            for ((eye, crop) in listOf(image.left, image.right).withIndex()) {
                for (local in listOf(-1f, 0f, 0.00001f, 0.25f, 0.5f, 0.75f, 0.99999f, 1f, 2f)) {
                    val texel = sampleU(crop, local).toDouble() * width - 0.5
                    assertTrue("$width / eye $eye / $local: $texel", floor(texel) >= eye * width / 2)
                    assertTrue("$width / eye $eye / $local: $texel", ceil(texel) < (eye + 1) * width / 2)
                }
            }
        }
    }

    @Test fun verticalClampProtectsFirstAndLastRows() {
        for (height in listOf(16, 18, 720, 1080, 2160, 4096)) {
            val image = SourceLayout(1280, height, "stereo_sbs", "steamvr")
            for (crop in listOf(image.left, image.right)) {
                assertTrue(crop.minV.toDouble() >= 0.5 / height)
                assertTrue(crop.maxV.toDouble() <= 1.0 - 0.5 / height)
                for (local in listOf(-1f, 0f, 0.5f, 1f, 2f)) {
                    val texel = local.coerceIn(crop.minV, crop.maxV).toDouble() * height - 0.5
                    assertTrue(floor(texel) >= 0)
                    assertTrue(ceil(texel) < height)
                }
            }
        }
    }

    @Test fun testPatternAndSteamVrHaveExactlyTheSameStereoGeometry() {
        val test = SourceLayout(2560, 1440, "stereo_sbs", "test_pattern")
        val vr = test.copy(source = "steamvr")
        assertEquals(test.left, vr.left)
        assertEquals(test.right, vr.right)
        assertEquals(test.eyeAspect, vr.eyeAspect, 0f)
    }

    @Test fun replacingLayoutDoesNotMutateAnExistingRenderSnapshot() {
        val before = SourceLayout(2560, 720, "mono", "test_pattern")
        val after = before.copy(layout = "stereo_sbs")
        assertEquals(before.left, before.right)
        assertEquals(32f / 9f, before.eyeAspect, 0f)
        assertNotEquals(after.left, after.right)
        assertEquals(16f / 9f, after.eyeAspect, 0f)
    }

    private fun sampleU(crop: EyeTextureCrop, local: Float): Float =
        (crop.offsetU + local * crop.scaleU).coerceIn(crop.minU, crop.maxU)
}
