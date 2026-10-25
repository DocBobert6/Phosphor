package com.phosphor.player.player

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.phosphor.player.vulkan.VulkanBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Playback engine: Media3 ExoPlayer decodes video into an ImageReader
 * Surface; each decoded frame's HardwareBuffer is handed to the Vulkan
 * renderer, which presents it through the CRT shader pipeline.
 *
 * Formats handled by Media3 core: MP4, MKV, AVI, MOV, WebM, FLV.
 * (WMV/ASF needs the FFmpeg decoder extension — see README.)
 */
@OptIn(UnstableApi::class)
class VideoEngine(private val context: Context) {

    data class UiState(
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val title: String = "NO SIGNAL",
        val error: String? = null,
        val isBuffering: Boolean = false,
        val videoDebug: String = "",
        val framesReceived: Long = 0L,
        val videoRotationDegrees: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private var frameCount = 0L
    private var lastColorMatrix = -1f

    private val frameThread = HandlerThread("PhosphorFrames").apply { start() }
    private val frameHandler = Handler(frameThread.looper)

    // Decoded frames land here at up to 4K; the CRT shader does the rest.
    // (Pixel recordings are 4K-capable, and the old 1080p ImageReader was
    // a suspect in the black-screen reports -- the native staging buffers
    // were already sized for 3840x2160.)
    private val imageReader: ImageReader =
        ImageReader.newInstance(3840, 2160, ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    // GPU planar path: hand the YUV planes to native code for
                    // upload as three R8 textures (decoder AHBs can't be
                    // imported on this device's driver). Conversion to RGB
                    // happens in the fragment shader.
                    val planes = image.planes
                    if (planes.size >= 3) {
                        frameCount++
                        VulkanBridge.nativeOnFrameYUV(
                            planes[0].buffer, planes[0].rowStride, planes[0].pixelStride,
                            planes[1].buffer, planes[1].rowStride, planes[1].pixelStride,
                            planes[2].buffer, planes[2].rowStride, planes[2].pixelStride,
                            image.width, image.height,
                        )
                    }
                } catch (_: Throwable) {
                    // renderer not ready yet — drop the frame
                } finally {
                    image.close()
                }
            }, frameHandler)
        }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            DefaultRenderersFactory(context).setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            )
        )
        .build()
        .apply {
            setVideoSurface(imageReader.surface)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) = refresh()
                override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
                override fun onPlayerError(error: PlaybackException) {
                    _uiState.value = _uiState.value.copy(
                        error = error.errorCodeName ?: "Playback error",
                        isBuffering = false,
                    )
                }
            })
        }

    // Media3 ExoPlayer requires EVERY player call on the application (main)
    // thread — a background timer thread here caused
    // "IllegalStateException: Player is accessed on the wrong thread" crashes.
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            refresh()
            progressHandler.postDelayed(this, 500)
        }
    }

    init {
        progressHandler.post(progressTick)
    }

    fun play(uri: Uri, title: String) {
        _uiState.value = _uiState.value.copy(title = title, error = null)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceIn(0, player.duration.coerceAtLeast(0))
        player.seekTo(target)
        refresh()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        refresh()
    }

    fun stop() {
        player.stop()
        _uiState.value = _uiState.value.copy(
            title = "NO SIGNAL", isPlaying = false, positionMs = 0, durationMs = 0
        )
    }

    private fun refresh() {
        val p = player
        val vf = p.videoFormat
        // Colour matrix follows the video, not a fixed BT.601: HD video
        // (including Pixel recordings) is BT.709, SD is BT.601. This is a
        // width heuristic, not a colour-space parse, so it cannot fail on
        // an unusual colourInfo -- and it fixes the old always-601 look.
        if (vf != null) {
            val matrix = if (vf.width >= 1280 || vf.height >= 720) 1f else 0f
            if (matrix != lastColorMatrix) {
                lastColorMatrix = matrix
                try {
                    VulkanBridge.nativeSetParam(VulkanBridge.PARAM_COLOR_MATRIX, matrix)
                } catch (_: Throwable) {
                }
            }
        }
        _uiState.value = _uiState.value.copy(
            isPlaying = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.coerceAtLeast(0).let { if (it == Long.MAX_VALUE) 0 else it },
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            videoDebug = vf?.let {
                "${it.sampleMimeType?.substringAfter('/') ?: "?"} ${it.width}x${it.height}"
            } ?: "no video track",
            framesReceived = frameCount,
            videoRotationDegrees = vf?.rotationDegrees ?: 0,
        )
    }

    fun release() {
        progressHandler.removeCallbacks(progressTick)
        player.setVideoSurface(null)
        player.release()
        imageReader.close()
        frameThread.quitSafely()
    }
}
