package com.phosphor.player.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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

private fun fmtVfd(ms: Long): String {
    if (ms <= 0) return "00:00:00"
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

private val SilverGradient = Brush.verticalGradient(
    listOf(Color(0xFFD6DAE3), Color(0xFFB4B8C5), Color(0xFF7E838F))
)
private val SilverDarkGradient = Brush.verticalGradient(
    listOf(Color(0xFF9AA0AD), Color(0xFF7E838F), Color(0xFF5F636D))
)

/**
 * The TV itself, as a full molded cabinet -- not a flat rectangle.
 * Portrait follows the user's cabinet drawing: silver/charcoal housing
 * with vent slots and extruded lines, a deep multi-layer curved bezel,
 * PHOSPHOR TRINITUBE badge, jewel power LED, dot-matrix speaker
 * grilles, a front control cluster, and a flip-down compartment with
 * RCA jacks, VCR transport, seek bar, and a VFD tape counter.
 * Landscape keeps the tube on the left and the controls on the right,
 * so nothing stretches.
 */
@Composable
fun PlayerScreen(
    engine: VideoEngine,
    settings: PictureSettings,
    onSettingsChange: (PictureSettings) -> Unit,
    onOpenPictureMenu: () -> Unit,
    onOpenBrowser: () -> Unit,
    showPictureMenu: Boolean = false,
    onDismissPictureMenu: () -> Unit = {},
) {
    val uiState by engine.uiState.collectAsState()
    val context = LocalContext.current
    var volume by remember { mutableFloatStateOf(engine.player.volume) }
    var lastVolume by remember { mutableFloatStateOf(0.5f) }
    var showVolumeOsd by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf("") }

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

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        engine.player.volume = volume
        showVolumeOsd = true
    }
    fun cyclePreset(delta: Int) {
        val next = (settings.presetIndex + delta + CrtPreset.entries.size) %
                CrtPreset.entries.size
        onSettingsChange(settings.copy(presetIndex = next))
    }

    // ---- the tube, recessed in a deep multi-layer bezel ----
    val tubeBox: @Composable (Modifier) -> Unit = { tubeMod ->
        Box(
            modifier = tubeMod
                .clip(RoundedCornerShape(18.dp))
                .background(Crt.Bezel)
                .border(1.dp, Crt.BezelEdge, RoundedCornerShape(18.dp))
                .padding(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Crt.Screen)
                    .border(2.dp, Color.Black, RoundedCornerShape(12.dp))
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

                // Anti-glare coating + glass glare arc.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.10f),
                                    Color.Transparent,
                                    Color.Transparent,
                                )
                            )
                        ),
                )

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
                AnimatedVisibility(
                    visible = osdVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    OsdText(clock, modifier = Modifier.padding(10.dp))
                }
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

                // OSD service menu, drawn directly over the tube.
                if (showPictureMenu) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0A1428).copy(alpha = 0.94f))
                            .padding(8.dp),
                    ) {
                        PictureMenuContent(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onDismiss = onDismissPictureMenu,
                        )
                    }
                }
            }
        }
    }

    val brandRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandBadge()
            Spacer(Modifier.weight(1f))
            PowerLed(isOn = uiState.isPlaying)
            Spacer(Modifier.width(8.dp))
            Text(
                "立体音响 Hi-Fi",
                color = Crt.Silver,
                fontFamily = Crt.Mono,
                fontSize = 10.sp,
            )
        }
    }

    // No weight on the root here: the same grid is used in the portrait
    // control row (wrapped in a weighted Box) and in the landscape
    // scrollable remote column, where a weight would be illegal.
    val buttonGrid: @Composable () -> Unit = {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChunkyButton("PWR", onClick = { engine.togglePlayPause() },
                    accent = uiState.isPlaying, modifier = Modifier.weight(1f))
                ChunkyButton("CH+", onClick = { cyclePreset(1) },
                    modifier = Modifier.weight(1f))
                ChunkyButton("CH−", onClick = { cyclePreset(-1) },
                    modifier = Modifier.weight(1f))
                ChunkyButton("VOL+", onClick = { setVolume(volume + 0.1f) },
                    modifier = Modifier.weight(1f))
                ChunkyButton("VOL−", onClick = { setVolume(volume - 0.1f) },
                    modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChunkyButton("MENU", onClick = onOpenPictureMenu,
                    modifier = Modifier.weight(1f))
                ChunkyButton("INPUT", onClick = onOpenBrowser,
                    modifier = Modifier.weight(1f))
                ChunkyButton("MUTE", onClick = {
                    if (volume > 0f) {
                        lastVolume = volume
                        setVolume(0f)
                    } else {
                        setVolume(if (lastVolume > 0f) lastVolume else 0.5f)
                    }
                }, modifier = Modifier.weight(1f))
            }
        }
    }

    val controlsRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotGrille()
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { buttonGrid() }
            Spacer(Modifier.width(8.dp))
            DotGrille()
        }
    }

    val vcrRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChunkyButton("⏮ REW", onClick = { engine.seekBy(-10_000) })
            ChunkyButton(
                if (uiState.isPlaying) "❚❚ PAUSE" else "▶ PLAY",
                onClick = { engine.togglePlayPause() },
                accent = true,
            )
            ChunkyButton("FF ▶▶", onClick = { engine.seekBy(10_000) })
            ChunkyButton("■ STOP", onClick = { engine.stop() })
        }
    }

    val compartment: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SilverDarkGradient, RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF4A4E57), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Hinge line of the flip-down compartment door.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color(0xFF3A3D44), RoundedCornerShape(1.dp)),
            )
            RcaRow()
            SeekBar(engine = engine, positionMs = uiState.positionMs, durationMs = uiState.durationMs)
            vcrRow()
            VfdPanel(
                positionMs = uiState.positionMs,
                durationMs = uiState.durationMs,
                presetTitle = CrtPreset.entries[settings.presetIndex].title.uppercase(),
            )
        }
    }

    val ventSlots: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(14) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(Color(0xFF232428), RoundedCornerShape(2.dp)),
                )
            }
        }
    }

    val extrudedLines: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Crt.SilverDark.copy(alpha = 0.6f)),
                )
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0D))
            .padding(12.dp),
    ) {
        val isLandscape = maxWidth > maxHeight
        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: the cabinet itself.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(SilverGradient, RoundedCornerShape(22.dp))
                        .border(1.dp, Crt.SilverDark, RoundedCornerShape(22.dp))
                        .padding(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ventSlots()
                        tubeBox(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        )
                        brandRow()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DotGrille()
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f)) {
                                VfdPanel(
                                    positionMs = uiState.positionMs,
                                    durationMs = uiState.durationMs,
                                    presetTitle = CrtPreset.entries[settings.presetIndex].title.uppercase(),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            DotGrille()
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
                // Right: the remote / compartment column.
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .background(Crt.Bezel, RoundedCornerShape(18.dp))
                        .border(1.dp, Crt.BezelEdge, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        buttonGrid()
                        compartment()
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
        } else {
            // Portrait: one cabinet, everything inside it, as drawn.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SilverGradient, RoundedCornerShape(24.dp))
                    .border(1.dp, Crt.SilverDark, RoundedCornerShape(24.dp))
                    .padding(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ventSlots()
                    tubeBox(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    )
                    brandRow()
                    controlsRow()
                    compartment()
                    extrudedLines()
                    Spacer(Modifier.weight(1f))
                    OsdText(
                        "CRT: ${CrtPreset.entries[settings.presetIndex].title.uppercase()}  •  ${uiState.videoDebug}",
                        size = 10,
                        color = Crt.TextDim.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

/** A chunky tactile key: light top edge, dark bottom edge, and a real
 * depression on tap (it sinks a few pixels while pressed). */
@Composable
private fun ChunkyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .graphicsLayer { translationY = if (pressed) 3f else 0f }
            .shadow(if (pressed) 1.dp else 4.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.40f),
                        if (accent) Color(0xFF1F6B3A) else Crt.ButtonTop,
                        if (accent) Color(0xFF134A27) else Crt.ButtonFace,
                        Color.Black.copy(alpha = 0.60f),
                    )
                )
            )
            .border(1.dp, Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (accent) Crt.OsdGreen else Crt.TextMain,
            fontFamily = Crt.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

/** Perforated acoustic dot matrix, as on a real speaker grille. */
@Composable
private fun DotGrille() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(7) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(5) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(Color(0xFF3A3D44), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandBadge() {
    Box(
        modifier = Modifier
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFE8EAEE), Color(0xFFB4B8C5), Color(0xFF7E838F))
                ),
                RoundedCornerShape(6.dp),
            )
            .border(1.dp, Color(0xFF4A4E57), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PHOSPHOR",
                color = Color(0xFF1A1C20),
                fontFamily = Crt.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                " TRINITUBE",
                color = Color(0xFF3A3D44),
                fontFamily = Crt.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "FD",
                color = Color(0xFF1A1C20),
                fontFamily = Crt.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun PowerLed(isOn: Boolean) {
    Box(
        modifier = Modifier
            .shadow(8.dp, CircleShape)
            .size(16.dp)
            .background(
                Brush.radialGradient(
                    listOf(
                        if (isOn) Color(0xFF9DFFC0) else Color(0xFFFF9A8E),
                        if (isOn) Crt.LedOn else Color(0xFF7A1F1A),
                    )
                ),
                CircleShape,
            )
            .border(1.dp, Color.Black.copy(alpha = 0.4f), CircleShape),
    )
}

@Composable
private fun RcaRow() {
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
                        .size(18.dp)
                        .background(c, CircleShape)
                        .border(2.dp, Color.Black, CircleShape),
                )
                Spacer(Modifier.width(4.dp))
                OsdText(label, size = 9, color = Color(0xFF232428))
                Spacer(Modifier.width(14.dp))
            }
        }
    }
}

@Composable
private fun VfdPanel(positionMs: Long, durationMs: Long, presetTitle: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF062A1E), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF0E4A34), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OsdText(
                "[ ${fmtVfd(positionMs)} / ${fmtVfd(durationMs)} • SP MODE ]",
                size = 11,
                color = Color(0xFF7DFFB8),
            )
            Spacer(Modifier.weight(1f))
            OsdText(presetTitle, size = 9, color = Color(0xFF7DFFB8))
        }
    }
}

@Composable
private fun SeekBar(engine: VideoEngine, positionMs: Long, durationMs: Long) {
    var seekWidthPx by remember { mutableStateOf(0) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OsdText(fmtTime(positionMs), size = 12, color = Color(0xFF232428))
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(24.dp)
                .background(Color(0xFF232428), RoundedCornerShape(4.dp))
                .onSizeChanged { seekWidthPx = it.width }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (seekWidthPx > 0 && durationMs > 0) {
                            val target = (offset.x / seekWidthPx * durationMs).toLong()
                                .coerceIn(0L, durationMs)
                            engine.seekTo(target)
                        }
                    }
                },
        ) {
            val frac = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(frac)
                    .background(Crt.OsdGreen, RoundedCornerShape(4.dp)),
            )
        }
        OsdText(fmtTime(durationMs), size = 12, color = Color(0xFF232428))
    }
}
