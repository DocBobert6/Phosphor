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
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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

    // Auto-rotation: a video's own rotation metadata wins over the
    // default, without fighting the user -- this effect only re-runs when
    // the title or the video's rotation changes, so a manual ROTATE tap
    // afterwards is never overwritten.
    LaunchedEffect(uiState.title, uiState.videoRotationDegrees) {
        val deg = ((uiState.videoRotationDegrees % 360) + 360) % 360
        if (deg != 0) {
            val rot = (deg / 90).toFloat()
            if (settings.rotation != rot) {
                onSettingsChange(settings.copy(rotation = rot))
            }
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
                    .shadow(6.dp, CircleShape)
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

    // ---- cabinet dressing: VFD readout, speaker grilles, RCA jacks ----
    val vfdRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF062A1E), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF0E4A34), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OsdText(
                "${fmtTime(uiState.positionMs)} / ${fmtTime(uiState.durationMs)}",
                size = 12,
                color = Color(0xFF7DFFB8),
            )
            Spacer(Modifier.weight(1f))
            OsdText(
                CrtPreset.entries[settings.presetIndex].title.uppercase(),
                size = 10,
                color = Color(0xFF7DFFB8),
            )
        }
    }
    val speakerGrille: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(9) {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 26.dp)
                        .background(Crt.SilverDark, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
    val cabinetBottom: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                speakerGrille()
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) { vfdRow() }
                Spacer(Modifier.width(8.dp))
                speakerGrille()
            }
            // Decorative front RCA jacks, like a real 2000s set.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    Color(0xFFFFD23D) to "VIDEO",
                    Color.White to "L",
                    Color(0xFFFF4A3D) to "R",
                ).forEach { (c, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(c, CircleShape)
                                .border(1.dp, Color.Black, CircleShape),
                        )
                        Spacer(Modifier.width(4.dp))
                        OsdText(label, size = 9, color = Crt.TextDim)
                        Spacer(Modifier.width(12.dp))
                    }
                }
            }
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
            // seek: custom phosphor bar -- tap to seek, no Material slider.
            val dur = uiState.durationMs
            var seekWidthPx by remember { mutableStateOf(0) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OsdText(fmtTime(uiState.positionMs), size = 12)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .height(24.dp)
                        .background(Crt.OsdDim, RoundedCornerShape(4.dp))
                        .onSizeChanged { seekWidthPx = it.width }
                        .pointerInput(dur) {
                            detectTapGestures { offset ->
                                if (seekWidthPx > 0 && dur > 0) {
                                    val target = (offset.x / seekWidthPx * dur).toLong()
                                        .coerceIn(0L, dur)
                                    engine.seekTo(target)
                                }
                            }
                        },
                ) {
                    val frac = if (dur > 0) {
                        (uiState.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(frac)
                            .background(Crt.OsdGreen, RoundedCornerShape(4.dp)),
                    )
                }
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
                        cabinetBottom()
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
                        .weight(1f)
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
                        cabinetBottom()
                        Spacer(Modifier.weight(1f))
                    }
                }
                transportCluster(Modifier.fillMaxWidth())
            }
        }
    }
}
