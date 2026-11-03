package com.vrbox.client.render

/** Encoded dimensions are for the whole frame, not one eye. Immutable across a render pass. */
data class SourceLayout(val width: Int, val height: Int, val layout: String, val source: String) {
    init {
        require(layout == "mono" || layout == "stereo_sbs") { "Unsupported video layout: $layout" }
        require(width in 16..4096 && height in 16..4096 && width % 2 == 0 && height % 2 == 0 &&
            width.toLong() * height <= 3840L * 2160) { "Invalid negotiated video dimensions" }
        require(layout != "stereo_sbs" || width % 4 == 0) { "Stereo width must contain two even-width eyes" }
        require(source == "desktop" || source == "test_pattern" || source == "steamvr") { "Unknown video source" }
        require(source != "steamvr" || layout == "stereo_sbs") { "SteamVR requires stereo_sbs" }
        require(source != "desktop" || layout == "mono") { "Desktop requires mono" }
    }

    val stereo: Boolean = layout == "stereo_sbs"
    val eyeWidth: Int = if (stereo) width / 2 else width
    val eyeAspect: Float = eyeWidth.toFloat() / height
    val left: EyeTextureCrop = crop(0f)
    val right: EyeTextureCrop = if (stereo) crop(0.5f) else left

    private fun crop(offset: Float): EyeTextureCrop {
        val scale = if (stereo) 0.5f else 1f
        // Round bounds inward: float uniforms must not move a linear sample across the SBS seam.
        return EyeTextureCrop(offset, scale,
            Math.nextUp(offset + 0.5f / width), Math.nextDown(offset + scale - 0.5f / width),
            Math.nextUp(0.5f / height), Math.nextDown(1f - 0.5f / height))
    }
}

/** Eye-local UV is cropped and clamped here, BEFORE the decoder's SurfaceTexture transform. */
data class EyeTextureCrop(
    val offsetU: Float, val scaleU: Float,
    val minU: Float, val maxU: Float, val minV: Float, val maxV: Float
)
