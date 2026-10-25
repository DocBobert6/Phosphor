package com.phosphor.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phosphor.player.vulkan.CrtPreset

/**
 * The TV's PICTURE menu, as an on-tube OSD service menu -- no Material
 * bottom sheet, no radio buttons, no sliders. Dark navy panel, phosphor
 * green monospace text, bracket bars, and chunky ◄ ► keys, the way a
 * 2000s set's own menu looks. Every change is pushed live into the
 * Vulkan renderer.
 */
@Composable
fun PictureMenu(
    settings: PictureSettings,
    onSettingsChange: (PictureSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .background(Color(0xFF0A1428), RoundedCornerShape(8.dp))
                .border(1.dp, Crt.OsdDim, RoundedCornerShape(8.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OsdText("— PICTURE —", size = 15)
                Spacer(Modifier.weight(1f))
                OsdButton("EXIT", onClick = onDismiss)
            }
            SectionLabel("SERVICE MENU • ON-TUBE OSD")

            Spacer(Modifier.height(4.dp))
            SectionLabel("CRT SHADER")
            CrtPreset.entries.forEachIndexed { i, preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSettingsChange(settings.copy(presetIndex = i)) }
                        .background(
                            if (i == settings.presetIndex) Crt.OsdDim.copy(alpha = 0.35f)
                            else Color.Transparent
                        )
                        .border(
                            1.dp,
                            if (i == settings.presetIndex) Crt.OsdGreen else Crt.BezelEdge,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OsdText(if (i == settings.presetIndex) "▶ " else "  ", size = 13)
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            preset.title,
                            color = Crt.TextMain,
                            fontFamily = Crt.Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Text(
                            preset.blurb,
                            color = Crt.TextDim,
                            fontFamily = Crt.Mono,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel("TUBE ADJUSTMENTS")
            BracketRow("Scanlines", settings.scanline, 0f, 1f, 0.05f,
                "%.0f%%".format((settings.scanline * 100).toInt())) {
                onSettingsChange(settings.copy(scanline = it))
            }
            BracketRow("Phosphor mask", settings.mask, 0f, 1f, 0.05f,
                "%.0f%%".format((settings.mask * 100).toInt())) {
                onSettingsChange(settings.copy(mask = it))
            }
            BracketRow("Curvature", settings.curvature, 0f, 1f, 0.05f,
                "%.0f%%".format((settings.curvature * 100).toInt())) {
                onSettingsChange(settings.copy(curvature = it))
            }
            BracketRow("Brightness", settings.brightness, -0.5f, 0.5f, 0.05f,
                "%.2f".format(settings.brightness)) {
                onSettingsChange(settings.copy(brightness = it))
            }
            BracketRow("Contrast", settings.contrast, 0.5f, 1.5f, 0.05f,
                "%.2f".format(settings.contrast)) {
                onSettingsChange(settings.copy(contrast = it))
            }
            BracketRow("Color", settings.saturation, 0f, 2f, 0.1f,
                "%.2f".format(settings.saturation)) {
                onSettingsChange(settings.copy(saturation = it))
            }
            BracketRow("Vignette", settings.vignette, 0f, 1f, 0.05f,
                "%.0f%%".format((settings.vignette * 100).toInt())) {
                onSettingsChange(settings.copy(vignette = it))
            }
            BracketRow("Scanline lines", settings.scanlineTvl, 120f, 480f, 60f,
                "${settings.scanlineTvl.toInt()} TVL") {
                onSettingsChange(settings.copy(scanlineTvl = it))
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel("SCREEN FIT")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Fit" to 0f, "Fill" to 1f, "Stretch" to 2f).forEach { (label, v) ->
                    PanelButton(
                        label,
                        onClick = { onSettingsChange(settings.copy(aspectMode = v)) },
                        accent = settings.aspectMode == v,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel("GEOMETRY")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val rotLabels = listOf("0°", "90°", "180°", "270°")
                PanelButton(
                    "ROTATE: ${rotLabels[settings.rotation.toInt().coerceIn(0, 3)]}",
                    onClick = {
                        val next = (settings.rotation.toInt() + 1) % 4
                        onSettingsChange(settings.copy(rotation = next.toFloat()))
                    },
                    modifier = Modifier.weight(1f),
                )
                PanelButton(
                    if (settings.flip > 0.5f) "FLIP: ON" else "FLIP: OFF",
                    onClick = {
                        onSettingsChange(settings.copy(flip = if (settings.flip > 0.5f) 0f else 1f))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelButton(
                    "ZOOM −",
                    onClick = {
                        val z = (settings.zoom - 0.25f).coerceIn(0.5f, 3f)
                        onSettingsChange(settings.copy(zoom = z))
                    },
                    modifier = Modifier.weight(1f),
                )
                PanelButton(
                    "ZOOM +",
                    onClick = {
                        val z = (settings.zoom + 0.25f).coerceIn(0.5f, 3f)
                        onSettingsChange(settings.copy(zoom = z))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            OsdText(
                "Zoom: ${"%.2f".format(settings.zoom)}x — ROTATE is auto-set from the video; FLIP fixes mirrored video",
                size = 10,
                color = Crt.TextDim,
            )

            Spacer(Modifier.height(8.dp))
            SectionLabel("COLOR TEMPERATURE")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Cool" to 0f, "Medium" to 1f, "Warm" to 2f).forEach { (label, v) ->
                    PanelButton(
                        label,
                        onClick = { onSettingsChange(settings.copy(colorTemp = v)) },
                        accent = settings.colorTemp == v,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (settings.presetIndex == CrtPreset.MONO.ordinal) {
                Spacer(Modifier.height(8.dp))
                SectionLabel("PHOSPHOR TINT")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Green" to 0f, "Amber" to 1f, "White" to 2f).forEach { (label, v) ->
                        PanelButton(
                            label,
                            onClick = { onSettingsChange(settings.copy(phosphor = v)) },
                            accent = settings.phosphor == v,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            PanelButton(
                "RESET PICTURE",
                // Keep the auto-derived colour matrix: it follows the video,
                // it is not a user picture preference.
                onClick = { onSettingsChange(PictureSettings(colorMatrix = settings.colorMatrix)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            PanelButton(
                "DONE",
                onClick = onDismiss,
                accent = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OsdButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Crt.ButtonFace)
            .border(1.dp, Crt.BezelEdge, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Crt.OsdGreen,
            fontFamily = Crt.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun BracketRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    display: String,
    onChange: (Float) -> Unit,
) {
    val filled = ((value - min) / (max - min) * 10f).toInt().coerceIn(0, 10)
    val bar = "■".repeat(filled) + "□".repeat(10 - filled)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = Crt.TextMain,
                fontFamily = Crt.Mono,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            OsdText(display, size = 12)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OsdButton("◄") {
                onChange((value - step).coerceIn(min, max))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "[ $bar ]",
                color = Crt.OsdGreen,
                fontFamily = Crt.Mono,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OsdButton("►") {
                onChange((value + step).coerceIn(min, max))
            }
        }
    }
}
