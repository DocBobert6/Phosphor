package com.phosphor.player.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import com.phosphor.player.player.VideoEngine
import com.phosphor.player.vulkan.CrtPreset
import com.phosphor.player.vulkan.VulkanBridge
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "--:--"
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/**
 * The TV itself: bezel, tube (Vulkan SurfaceView), front panel, and a
 * remote-style transport cluster. OSD readouts mimic a 2000s Toshiba set.
 *
 * Layout adapts to orientation: portrait stacks the cabinet above the
 * transport; landscape puts the tube on the left and a scrollable control
 * column on the right so nothing stretches.
 */
@Composable
fun PlayerScreen(
    engine: VideoEngine,
    settings: PictureSettings,
    onSettingsChange: (PictureSettings) -> Unit,
    onOpenPictureMenu: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val uiState by engine.uiState.collectAsState()
    val context = LocalContext.current
    var volume by remember { mutableFloatStateOf(engine.player.volume) }
    var showVolumeOsd by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf("") }

    // On-screen display auto-hide: visible when paused/loading, fades after
    // 3s of playback. Tapping the tube brings it back.
    var osdVisible by remember { mutableStateOf(true) }
    var osdTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(uiState.isPlaying, osdTrigger) {
        if (uiState.isPlaying) {
            delay(3000)
            osdVisible = false
        } else {
            osdVisible = true
        }
    }

    LaunchedEffect(Unit) {
        val f = SimpleDateFormat("h:mm a", Locale.US)
        while (true) {
            clock = f.format(Date())
            delay(1000)
        }
    }
    LaunchedEffect(showVolumeOsd) {
        if (showVolumeOsd) {
            delay(1500)
            showVolumeOsd = false
        }
    }

    // ---- the tube (shared by both orientations) ----
    // tubeMod controls sizing: portrait = fill width, landscape = fit height.
    val tubeBox: @Composable (Modifier) -> Unit = { tubeMod ->
        Box(
            modifier = tubeMod
                .clip(RoundedCornerShape(14.dp))
                .background(Crt.Screen)
                .border(2.dp, Color(0xFF000000), RoundedCornerShape(14.dp))
                .clickable {
                    osdVisible = true
                    osdTrigger++
                },
        ) {
            var vkReady by remember { mutableStateOf(false) }
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                val ok = try {
                                    VulkanBridge.nativeInit(
                                        h.surface, context.assets,
                                        context.getExternalFilesDir(null)?.absolutePath
                                            ?: "",
                                    )
                                } catch (_: Throwable) {
                                    false
                                }
                                vkReady = ok
                                if (ok) onSettingsChange(settings)
                            }

                            override fun surfaceChanged(
                                h: SurfaceHolder, f: Int, w: Int, hh: Int,
                            ) {
                                if (vkReady) VulkanBridge.nativeResize(w, hh)
                            }

                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                if (vkReady) VulkanBridge.nativeRelease()
                                vkReady = false
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            DisposableEffect(Unit) {
                onDispose {
                    try {
                        VulkanBridge.nativeRelease()
                    } catch (_: Throwable) {
                    }
                }
            }

            // curved-glass sheen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.06f),
                                Color.Transparent,
                                Color.Transparent,
                            )
                        )
                    ),
            )

            // OSD: input + title, top-left (auto-hides during playback)
            AnimatedVisibility(
                visible = osdVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    OsdText("VIDEO 1")
                    val title = uiState.title.take(28)
                    OsdText(
                        if (uiState.error != null) "NO SIGNAL"
                        else title.ifBlank { "NO SIGNAL" },
                        color = if (uiState.error != null) Crt.OsdAmber else Crt.OsdGreen,
                    )
                    if (uiState.error != null) {
                        OsdText("FORMAT NOT SUPPORTED", color = Crt.OsdAmber, size = 11)
                    }
                }
            }
            // OSD: clock, top-right (auto-hides during playback)
            AnimatedVisibility(
                visible = osdVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                OsdText(clock, modifier = Modifier.padding(10.dp))
            }
            // OSD: playback state, bottom-left (auto-hides during playback)
            AnimatedVisibility(
                visible = osdVisible && (uiState.isPlaying || uiState.isBuffering),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                OsdText(
                    if (uiState.isBuffering) "LOADING…" else "▶ PLAY",
                    modifier = Modifier.padding(10.dp),
                )
            }
            // OSD: volume bar
            if (showVolumeOsd) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OsdText("VOL ", size = 12)
                    repeat(10) { i ->
                        Box(
                            modifier = Modifier
                                .padding(end = 3.dp)
                                .size(width = 10.dp, height = 14.dp)
                                .background(
                                    if (i < (volume * 10).toInt()) Crt.OsdGreen
                                    else Crt.OsdDim,
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
            }
        }
    }

    // ---- front panel: brand plate, LED, panel keys ----
    val frontPanel: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (uiState.isPlaying) Crt.LedOn else Color(0xFF1E3A26),
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            androidx.compose.material3.Text(
                text = "P H O S P H O R",
                color = Crt.Silver,
                fontFamily = Crt.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.weight(1f))
            PanelButton("MENU", onClick = onOpenPictureMenu)
            Spacer(Modifier.width(8.dp))
            PanelButton("INPUT", onClick = onOpenBrowser)
        }
    }

    // ---- transport cluster (the remote) ----
    val transportCluster: @Composable (Modifier) -> Unit = { transMod ->
        Column(
            modifier = transMod
                .background(Crt.Bezel, RoundedCornerShape(18.dp))
                .border(1.dp, Crt.BezelEdge, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // seek
            val pos = uiState.positionMs.toFloat()
            val dur = uiState.durationMs.toFloat().coerceAtLeast(1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OsdText(fmtTime(uiState.positionMs), size = 12)
                Slider(
                    value = pos.coerceIn(0f, dur),
                    onValueChange = { engine.seekTo(it.toLong()) },
                    valueRange = 0f..dur,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Crt.OsdGreen,
                        activeTrackColor = Crt.OsdGreen,
                        inactiveTrackColor = Crt.OsdDim,
                    ),
                )
                OsdText(fmtTime(uiState.durationMs), size = 12)
            }
            // chunky keys
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelButton("⏮", onClick = { engine.seekBy(-10_000) })
                PanelButton("◀◀", onClick = { engine.seekBy(-10_000) })
                PanelButton(
                    if (uiState.isPlaying) "❚❚" else "▶",
                    onClick = { engine.togglePlayPause() },
                    accent = true,
                )
                PanelButton("▶▶", onClick = { engine.seekBy(10_000) })
                PanelButton("⏭", onClick = { engine.seekBy(30_000) })
                PanelButton("■", onClick = { engine.stop() })
            }
            // volume / shader, like a real remote
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                SectionLabel("VOL", modifier = Modifier.width(56.dp))
                PanelButton("−", onClick = {
                    volume = (volume - 0.1f).coerceIn(0f, 1f)
                    engine.player.volume = volume
                    showVolumeOsd = true
                })
                Spacer(Modifier.width(8.dp))
                PanelButton("+", onClick = {
                    volume = (volume + 0.1f).coerceIn(0f, 1f)
                    engine.player.volume = volume
                    showVolumeOsd = true
                })
                Spacer(Modifier.width(28.dp))
                SectionLabel("SHADER", modifier = Modifier.width(72.dp))
                PanelButton("−", onClick = {
                    val next = (settings.presetIndex + CrtPreset.entries.size - 1) %
                            CrtPreset.entries.size
                    onSettingsChange(settings.copy(presetIndex = next))
                })
                Spacer(Modifier.width(8.dp))
                PanelButton("+", onClick = {
                    val next = (settings.presetIndex + 1) % CrtPreset.entries.size
                    onSettingsChange(settings.copy(presetIndex = next))
                })
            }
            // current shader readout + video debug info
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OsdText(
                    "CRT: ${CrtPreset.entries[settings.presetIndex].title.uppercase()}",
                    size = 12,
                    color = Crt.TextDim,
                )
                if (uiState.videoDebug.isNotEmpty()) {
                    OsdText(
                        "${uiState.videoDebug}  •  ${uiState.framesReceived} frames",
                        size = 10,
                        color = Crt.TextDim.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }

    // ---- orientation-aware arrangement ----
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0B0B0D), Color(0xFF151517)))
            )
            .padding(12.dp),
    ) {
        val isLandscape = maxWidth > maxHeight
        if (isLandscape) {
            // Landscape: tube on the left (height-constrained, 16:9),
            // scrollable control column on the right.
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Crt.Bezel, RoundedCornerShape(22.dp))
                        .border(1.dp, Crt.BezelEdge, RoundedCornerShape(22.dp))
                        .padding(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tubeBox(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        )
                        frontPanel()
                    }
                }
                transportCluster(
                    Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                )
            }
        } else {
            // Portrait: cabinet above, transport below (the classic layout).
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Crt.Bezel, RoundedCornerShape(22.dp))
                        .border(1.dp, Crt.BezelEdge, RoundedCornerShape(22.dp))
                        .padding(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tubeBox(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        )
                        frontPanel()
                    }
                }
                transportCluster(Modifier.fillMaxWidth())
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
