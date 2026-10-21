package com.zerosync.app.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.layout.aspectRatio
import com.zerosync.app.gallery.Thumbnail
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.zerosync.app.ui.PillSwitch
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors
import java.text.DateFormat
import java.util.Date

/** Red delete: the one color exception, user-approved (danger action). */
private val DeleteRed = Color(0xFFD32F2F)

// Room at the bottom so the list clears the nav strip.
private val NavClearance = 88.dp

@Composable
fun FilesScreen(vm: FilesViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleResumeEffect(Unit) {
        granted = Environment.isExternalStorageManager()
        if (granted) vm.load()
        onPauseOrDispose { }
    }

    vm.opened?.let { doc ->
        BackHandler { vm.close() }
        DocViewer(doc, vm.content, onBack = vm::close)
        return
    }

    if (!granted) {
        AccessPrompt { openAllFilesSettings(context) }
        return
    }

    // Delete, two steps: hold an item -> confirm box -> red Delete removes the file from the phone.
    var toDelete by remember { mutableStateOf<DocFile?>(null) }
    toDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = ZsColors.Surface,
            title = { Text("Delete this file?", color = Color.White) },
            text = { Text("${doc.name}\nIt will be removed from the phone.", color = ZsColors.OnSurface) },
            confirmButton = {
                Button(
                    onClick = { vm.delete(doc); toDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = DeleteRed, contentColor = Color.White),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel", color = ZsColors.Secondary) } },
        )
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp, end = RailWidth)) {
        val shown = vm.docs.filter { it.kind == vm.kind }
        if (shown.isEmpty()) {
            Text(
                if (vm.loading) "Looking for files…" else "No ${vm.kind.label} files found",
                color = ZsColors.OnSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 120.dp),
            )
            return@Column
        }
        if (vm.kind == DocKind.Images || vm.kind == DocKind.Videos) {
            // Small squares, 5 per row; tap = open.
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = NavClearance),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                gridItems(shown, key = { it.uri.toString() }) { doc ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).combinedClickable(onClick = { vm.open(doc) }, onLongClick = { toDelete = doc })) {
                        Thumbnail(doc.uri, Modifier.fillMaxSize(), sizePx = 192)
                        if (doc.kind == DocKind.Videos) {
                            Icon(
                                ZsIcons.Play,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.Center).size(18.dp),
                            )
                        }
                    }
                }
            }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = NavClearance),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.uri.toString() }) { doc -> FileRow(doc, onLongClick = { toDelete = doc }) { vm.open(doc) } }
        }
    }
    // Type picker: icon column at the bottom-right corner, in reach of the right thumb.
    KindRail(
        selected = vm.kind,
        onSelect = { vm.kind = it },
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = NavClearance + 40.dp),
    )
    }
}

private val RailWidth = 64.dp

private fun DocKind.icon() = when (this) {
    DocKind.Pdf -> ZsIcons.Pdf
    DocKind.Word -> ZsIcons.Doc
    DocKind.Ppt -> ZsIcons.Slides
    DocKind.Excel -> ZsIcons.Sheet
    DocKind.Text -> ZsIcons.Lines
    DocKind.Images -> ZsIcons.Image
    DocKind.Videos -> ZsIcons.Movie
}

/** Vertical panel of type icons (no text, no counts). Selected = white rounded square, black icon. */
@Composable
private fun KindRail(selected: DocKind, onSelect: (DocKind) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.92f))
            .border(1.dp, ZsColors.Secondary, RoundedCornerShape(16.dp))
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DocKind.entries.forEach { k ->
            val active = k == selected
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp)) // rounded square, not a circle
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable { onSelect(k) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(k.icon(), contentDescription = k.label, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun FileRow(doc: DocFile, onLongClick: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ZsColors.Surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // Short type tag instead of a stock icon: PDF, DOCX, PPTX ...
            Text(doc.ext.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(doc.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Formatter.formatShortFileSize(context, doc.size) + " · " +
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(doc.modified)) +
                    if (doc.canPreview) "" else " · no preview",
                style = MaterialTheme.typography.labelSmall,
                color = ZsColors.Secondary,
            )
        }
    }
}

@Composable
private fun AccessPrompt(onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(ZsIcons.File, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        Text(
            "To list PDF, Word, PowerPoint and Excel files from the whole phone, turn on " +
                "\"All files access\" for ZeroSync. Files are only read, never changed or sent.",
            color = ZsColors.OnSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Button(
            onClick = onOpen,
            modifier = Modifier.padding(top = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        ) { Text("Open settings") }
    }
}

private fun openAllFilesSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
}
