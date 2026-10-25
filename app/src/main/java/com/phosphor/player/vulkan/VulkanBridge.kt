package com.phosphor.player.vulkan

import android.content.res.AssetManager
import android.hardware.HardwareBuffer
import android.view.Surface

/**
 * JNI bridge to libphosphor.so — the Vulkan presentation + CRT shader pipeline.
 * Param IDs must match Renderer::setParam in phosphor.cpp.
 */
object VulkanBridge {
    const val PARAM_SCANLINE = 0
    const val PARAM_MASK = 1
    const val PARAM_CURVATURE = 2
    const val PARAM_BRIGHTNESS = 3
    const val PARAM_CONTRAST = 4
    const val PARAM_SATURATION = 5
    const val PARAM_VIGNETTE = 6
    const val PARAM_ASPECT_MODE = 7
    const val PARAM_PHOSPHOR = 8
    const val PARAM_ROTATION = 9
    const val PARAM_ZOOM = 10
    const val PARAM_COLOR_TEMP = 11
    const val PARAM_FLIP = 12
    const val PARAM_COLOR_MATRIX = 13
    const val PARAM_SCANLINE_TVL = 14

    const val ASPECT_FIT = 0f
    const val ASPECT_FILL = 1f
    const val ASPECT_STRETCH = 2f

    init {
        System.loadLibrary("phosphor")
    }

    external fun nativeInit(surface: Surface, assetManager: AssetManager, crashDir: String): Boolean
    external fun nativeOnFrame(hardwareBuffer: HardwareBuffer)

    /**
     * GPU planar path: YUV_420_888 planes are copied (no colour maths) to
     * native code and uploaded as three R8 textures; YUV->RGB conversion
     * happens in the fragment shader (sampleVideo). The AHB import path in
     * C++ is not used by Kotlin -- some drivers report VK_FORMAT_UNDEFINED
     * for decoder AHBs and crash in vkCreateSamplerYcbcrConversion.
     */
    external fun nativeOnFrameYUV(
        yBuf: java.nio.ByteBuffer, yRowStride: Int, yPixStride: Int,
        uBuf: java.nio.ByteBuffer, uRowStride: Int, uPixStride: Int,
        vBuf: java.nio.ByteBuffer, vRowStride: Int, vPixStride: Int,
        width: Int, height: Int,
    )
    external fun nativeResize(w: Int, h: Int)
    external fun nativeSetPreset(index: Int)
    external fun nativeSetParam(id: Int, value: Float)
    external fun nativeRelease()
}

/** The five CRT shader presets, in pipeline order. */
enum class CrtPreset(val title: String, val blurb: String) {
    TRINITRON("Trinitron", "Aperture-grille tube — the flat-screen 2000s look"),
    SHADOW_MASK("Shadow Mask", "Dot-triad curved tube — the classic living-room CRT"),
    SLOT_MASK("Slot Mask", "Late-90s workhorse tube, segmented slots"),
    COMPOSITE("Composite", "RF/composite glow — chroma bleed and dot crawl"),
    MONO("Mono", "Single-phosphor tube — green, amber, or paper white"),
}
