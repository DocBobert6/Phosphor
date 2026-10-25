package com.phosphor.player.ui

import android.content.Context
import android.content.Intent
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

private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "ts", "m2ts", "mpg", "mpeg")
private const val PREFS = "phosphor"
private const val KEY_TREE = "tree_uri"

private fun isVideo(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXTS
}

/**
 * INPUT source browser: pick a single file, or grant a folder and browse it
 * like flipping through tapes on a shelf. No storage permission needed —
 * everything goes through the Storage Access Framework.
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
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(files, key = { _, doc -> doc.uri.toString() }) { index, doc ->
                    val name = doc.name ?: "Unknown"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Crt.ButtonFace)
                            .border(1.dp, Crt.BezelEdge, RoundedCornerShape(10.dp))
                            .clickable { onPlay(doc.uri, name) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // VHS spine: channel number on a tape label.
                        Column(
                            modifier = Modifier
                                .background(Crt.SilverDark, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            OsdText("CH", size = 9, color = Color.Black)
                            OsdText(
                                "%02d".format(index + 2),
                                size = 13,
                                color = Color.Black,
                            )
                        }
                        Spacer(Modifier.padding(4.dp))
                        OsdText("📼", size = 14)
                        Spacer(Modifier.padding(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                color = Crt.TextMain,
                                fontFamily = Crt.Mono,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                name.substringAfterLast('.', "").uppercase() +
                                        (doc.length().takeIf { it > 0 }?.let {
                                            "  •  ${it / 1024 / 1024} MB"
                                        } ?: ""),
                                color = Crt.TextDim,
                                fontFamily = Crt.Mono,
                                fontSize = 11.sp,
                            )
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
