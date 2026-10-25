package com.phosphor.player.ui

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "ts", "m2ts", "mpg", "mpeg")
private const val PREFS = "phosphor"
private const val KEY_TREE = "tree_uri"

private fun isVideo(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXTS
}

private fun fmtDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return "…"
    val s = ms / 1000
    return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format("%d:%02d", s / 60, s % 60)
}

/**
 * INPUT source browser as a retro input shelf: a VHS tape rack, or a
 * TV channel guide, instead of a generic Android file list. Each tape
 * is a channel (CH 02, CH 03, …), its paper label shows the filename,
 * duration, and file size in monospace type. No storage permission
 * needed -- everything goes through the Storage Access Framework.
 */
@Composable
fun BrowserScreen(
    onPlay: (Uri, String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var files by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var guideMode by remember { mutableStateOf(false) }
    var durations by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    fun loadTree(treeUri: Uri) {
        scanning = true
        try {
            val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return
            folderName = doc.name ?: "Folder"
            files = doc.listFiles()
                .filter { it.isFile && isVideo(it.name ?: "") }
                .sortedBy { (it.name ?: "").lowercase() }
        } catch (_: Throwable) {
            files = emptyList()
        } finally {
            scanning = false
        }
    }

    // Durations come from the files themselves, in the background, so
    // the shelf stays responsive while each tape's label fills in.
    LaunchedEffect(files) {
        if (files.isEmpty()) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            files.associate { doc ->
                doc.uri.toString() to try {
                    MediaMetadataRetriever().use { r ->
                        r.setDataSource(context, doc.uri)
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    }
                } catch (_: Throwable) {
                    0L
                }
            }
        }
        durations = found
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            prefs.edit().putString(KEY_TREE, uri.toString()).apply()
            loadTree(uri)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "Video"
            onPlay(uri, name)
        }
    }

    LaunchedEffect(Unit) {
        prefs.getString(KEY_TREE, null)?.let { loadTree(Uri.parse(it)) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Crt.Bezel)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelButton("◀ TV", onClick = onBack)
            Spacer(Modifier.padding(4.dp))
            OsdText("— INPUT SELECT —", size = 15)
        }
        SectionLabel("TAPE SHELF • EACH TAPE IS A CHANNEL")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(
                "📁 CHOOSE FOLDER",
                onClick = { treePicker.launch(null) },
                modifier = Modifier.weight(1f),
            )
            PanelButton(
                "🎞 OPEN FILE",
                onClick = { filePicker.launch(arrayOf("video/*")) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(
                "📼 TAPE RACK",
                onClick = { guideMode = false },
                accent = !guideMode,
                modifier = Modifier.weight(1f),
            )
            PanelButton(
                "📺 CHANNEL GUIDE",
                onClick = { guideMode = true },
                accent = guideMode,
                modifier = Modifier.weight(1f),
            )
        }

        if (folderName != null) {
            SectionLabel("FOLDER: ${folderName!!.uppercase().take(28)}")
        }

        if (scanning) {
            OsdText("TUNING…", color = Crt.TextDim)
        } else if (files.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Crt.BezelEdge, RoundedCornerShape(12.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No folder selected yet.",
                    color = Crt.TextDim, fontFamily = Crt.Mono, fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Choose a folder full of videos and they will list here, like tapes on a shelf.",
                    color = Crt.TextDim, fontFamily = Crt.Mono, fontSize = 12.sp,
                )
            }
        } else if (guideMode) {
            // TV guide / channel grid.
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(files, key = { _, doc -> doc.uri.toString() }) { index, doc ->
                    val name = doc.name ?: "Unknown"
                    val ch = "%02d".format(index + 2)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Crt.ButtonFace)
                            .border(1.dp, Crt.BezelEdge, RoundedCornerShape(8.dp))
                            .clickable { onPlay(doc.uri, name) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OsdText("CH $ch", size = 13)
                        Spacer(Modifier.padding(4.dp))
                        Text(
                            name,
                            color = Crt.TextMain,
                            fontFamily = Crt.Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.padding(4.dp))
                        OsdText(fmtDuration(durations[doc.uri.toString()]), size = 11)
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(files, key = { _, doc -> doc.uri.toString() }) { index, doc ->
                    val name = doc.name ?: "Unknown"
                    val ch = "%02d".format(index + 2)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1C1C22))
                            .border(1.dp, Crt.BezelEdge, RoundedCornerShape(10.dp))
                            .clickable { onPlay(doc.uri, name) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // VHS spine: channel number, vertical.
                        Column(
                            modifier = Modifier
                                .background(Crt.SilverDark, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            OsdText("CH", size = 9, color = Color.Black)
                            OsdText(ch, size = 13, color = Color.Black)
                        }
                        Spacer(Modifier.padding(4.dp))
                        // Paper label on the tape.
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0xFFF2EFE4), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFFB8B0A0), RoundedCornerShape(4.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                name,
                                color = Color(0xFF1A1C20),
                                fontFamily = Crt.Mono,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "CH $ch: $name",
                                color = Color(0xFF3A3D44),
                                fontFamily = Crt.Mono,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                fmtDuration(durations[doc.uri.toString()]) +
                                        "  •  " +
                                        name.substringAfterLast('.', "").uppercase() +
                                        (doc.length().takeIf { it > 0 }?.let {
                                            "  •  ${it / 1024 / 1024} MB"
                                        } ?: ""),
                                color = Color(0xFF3A3D44),
                                fontFamily = Crt.Mono,
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(Modifier.padding(4.dp))
                        // Tape reels, for the shelf look.
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            repeat(2) {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(Color(0xFF3A3D44), RoundedCornerShape(7.dp))
                                        .border(1.dp, Color(0xFF6B7280), RoundedCornerShape(7.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        SectionLabel("MP4 • MKV • AVI • MOV • WEBM • FLV")
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else null
        }
    } catch (_: Throwable) {
        null
    }
}
