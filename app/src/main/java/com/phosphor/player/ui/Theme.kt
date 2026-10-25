package com.phosphor.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** 2000s CRT palette: charcoal bezel, silver trim, green phosphor OSD. */
object Crt {
    val Bezel = Color(0xFF141416)
    val BezelEdge = Color(0xFF26262B)
    val Screen = Color(0xFF000000)
    val Silver = Color(0xFFB9BDC7)
    val SilverDark = Color(0xFF7E838E)
    val ButtonFace = Color(0xFF2C2C31)
    val ButtonTop = Color(0xFF3A3A41)
    val OsdGreen = Color(0xFF46FF7A)
    val OsdDim = Color(0xFF1D5A32)
    val OsdAmber = Color(0xFFFFB13D)
    val LedOn = Color(0xFF3DFF74)
    val TextMain = Color(0xFFE8E8EA)
    val TextDim = Color(0xFF9A9AA2)

    val Mono = FontFamily.Monospace
}

/** Persisted picture settings; applied straight into the Vulkan renderer. */
data class PictureSettings(
    val presetIndex: Int = 2,       // SLOT_MASK: Toshiba used slot-mask tubes, not Trinitron
    val scanline: Float = 0.35f,    // consumer 480i: soft, blended scanlines
    val mask: Float = 0.5f,         // 27" ~350-450 TVL: mask barely visible at distance
    val curvature: Float = 0.05f,   // "Flat" tube: near-flat glass
    val brightness: Float = 0.0f,   // -0.5..0.5
    val contrast: Float = 1.05f,    // 0.5..1.5
    val saturation: Float = 1.0f,   // 0..2
    val vignette: Float = 0.15f,    // flat CRT: mild edge darkening only
    val aspectMode: Float = 0f,     // 0 fit, 1 fill, 2 stretch
    val phosphor: Float = 0f,       // mono tint: 0 green, 1 amber, 2 white
    val rotation: Float = 0f,       // 0/1/2/3 = 0/90/180/270 deg clockwise
    val zoom: Float = 1f,           // 1 = normal, 0.5..3
    val colorTemp: Float = 0f,      // 0 cool (factory), 1 medium, 2 warm
    val flip: Float = 0f,           // 0 normal, 1 mirror horizontally
    val colorMatrix: Float = 1f,    // 0 BT.601 (SD), 1 BT.709 (HD)
    val scanlineTvl: Float = 240f,  // virtual CRT lines for scanlines
)
