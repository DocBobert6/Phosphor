package com.phosphor.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phosphor.player.vulkan.CrtPreset
import com.phosphor.player.vulkan.VulkanBridge

/**
 * The TV's PICTURE menu: CRT shader preset + tube adjustments.
 * Every change is pushed live into the Vulkan renderer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PictureMenu(
    settings: PictureSettings,
    onSettingsChange: (PictureSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Crt.Bezel,
        contentColor = Crt.TextMain,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OsdText("— PICTURE —", size = 15)
            Spacer(Modifier.height(4.dp))

            SectionLabel("CRT SHADER")
            CrtPreset.entries.forEachIndexed { i, preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSettingsChange(settings.copy(presetIndex = i)) }
                        .background(
                            if (i == settings.presetIndex) Crt.OsdDim.copy(alpha = 0.35f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .border(
                            1.dp,
                            if (i == settings.presetIndex) Crt.OsdGreen else Crt.BezelEdge,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = i == settings.presetIndex,
                        onClick = { onSettingsChange(settings.copy(presetIndex = i)) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Crt.OsdGreen,
                            unselectedColor = Crt.TextDim,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
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
            TubeSlider("Scanlines", settings.scanline, 0f..1f) {
                onSettingsChange(settings.copy(scanline = it))
            }
            TubeSlider("Phosphor mask", settings.mask, 0f..1f) {
                onSettingsChange(settings.copy(mask = it))
            }
            TubeSlider("Curvature", settings.curvature, 0f..1f) {
                onSettingsChange(settings.copy(curvature = it))
            }
            TubeSlider("Brightness", settings.brightness, -0.5f..0.5f) {
                onSettingsChange(settings.copy(brightness = it))
            }
            TubeSlider("Contrast", settings.contrast, 0.5f..1.5f) {
                onSettingsChange(settings.copy(contrast = it))
            }
            TubeSlider("Color", settings.saturation, 0f..2f) {
                onSettingsChange(settings.copy(saturation = it))
            }
            TubeSlider("Vignette", settings.vignette, 0f..1f) {
                onSettingsChange(settings.copy(vignette = it))
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
                "Zoom: ${"%.2f".format(settings.zoom)}x — tap ROTATE to fix orientation",
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
                onClick = { onSettingsChange(PictureSettings()) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TubeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Crt.TextMain, fontFamily = Crt.Mono, fontSize = 13.sp)
            OsdText(String.format(java.util.Locale.US, "%.2f", value), size = 12)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Crt.OsdGreen,
                activeTrackColor = Crt.OsdGreen,
                inactiveTrackColor = Crt.OsdDim,
            ),
        )
    }
}
