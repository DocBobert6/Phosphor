package com.phosphor.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.phosphor.player.player.VideoEngine
import com.phosphor.player.ui.BrowserScreen
import com.phosphor.player.ui.PictureMenu
import com.phosphor.player.ui.PictureSettings
import com.phosphor.player.ui.PlayerScreen
import com.phosphor.player.vulkan.VulkanBridge

class MainActivity : ComponentActivity() {

    private lateinit var engine: VideoEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = VideoEngine(this)
        handleIntent(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0B0B0D),
                    surface = Color(0xFF141416),
                )
            ) {
                Surface(Modifier.fillMaxSize()) {
                    var showBrowser by remember { mutableStateOf(false) }
                    var showPictureMenu by remember { mutableStateOf(false) }
                    var picture by remember { mutableStateOf(PictureSettings()) }

                    fun pushPicture(s: PictureSettings) {
                        picture = s
                        // Safe to call before the surface exists; the
                        // renderer applies them on the next frame / init.
                        try {
                            VulkanBridge.nativeSetPreset(s.presetIndex)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_SCANLINE, s.scanline)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_MASK, s.mask)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_CURVATURE, s.curvature)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_BRIGHTNESS, s.brightness)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_CONTRAST, s.contrast)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_SATURATION, s.saturation)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_VIGNETTE, s.vignette)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_ASPECT_MODE, s.aspectMode)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_PHOSPHOR, s.phosphor)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_ROTATION, s.rotation)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_ZOOM, s.zoom)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_COLOR_TEMP, s.colorTemp)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_FLIP, s.flip)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_COLOR_MATRIX, s.colorMatrix)
                            VulkanBridge.nativeSetParam(VulkanBridge.PARAM_SCANLINE_TVL, s.scanlineTvl)
                        } catch (_: Throwable) {
                            // native lib not loaded yet — settings re-apply on surface init
                        }
                    }

                    if (showBrowser) {
                        BrowserScreen(
                            onPlay = { uri, name ->
                                engine.play(uri, name)
                                showBrowser = false
                            },
                            onBack = { showBrowser = false },
                        )
                    } else {
                        PlayerScreen(
                            engine = engine,
                            settings = picture,
                            onSettingsChange = { pushPicture(it) },
                            onOpenPictureMenu = { showPictureMenu = true },
                            onOpenBrowser = { showBrowser = true },
                        )
                    }

                    if (showPictureMenu) {
                        PictureMenu(
                            settings = picture,
                            onSettingsChange = { pushPicture(it) },
                            onDismiss = { showPictureMenu = false },
                        )
                    }
                }
            }
        }
        // Collect any diagnostics from the previous run (native crash dump,
        // exit reason). If something was captured, tell the user where it is.
        val reports = CrashReporter.checkPreviousRun(this)
        if (reports.isNotEmpty()) {
            Toast.makeText(
                this,
                "Previous run crashed — report saved to Downloads",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Open videos shared from a file manager (ACTION_VIEW for video files). */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) {
            val name = queryName(uri) ?: uri.lastPathSegment ?: "Video"
            if (::engine.isInitialized) engine.play(uri, name)
        }
    }

    private fun queryName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.release()
        super.onDestroy()
    }
}
