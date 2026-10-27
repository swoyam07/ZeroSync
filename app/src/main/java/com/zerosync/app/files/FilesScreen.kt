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
    // Hold a file: PDF / Word / PPT / Excel → Rename + Delete (PDF also Add to / Remove from Books);
    // anything else → Delete.
    var options by remember { mutableStateOf<DocFile?>(null) }
    var renaming by remember { mutableStateOf<DocFile?>(null) }
    // All files: hold one file = select mode; then tap files to tick/untick, Delete removes them all.
    var selected by remember { mutableStateOf(emptySet<DocFile>()) }
    var confirmMany by remember { mutableStateOf(false) }
    LaunchedEffect(vm.kind) { selected = emptySet() } // switching type ends select mode
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    options?.let { doc ->
        if (doc.kind in setOf(DocKind.Pdf, DocKind.Word, DocKind.Ppt, DocKind.Excel)) {
            AlertDialog(
                onDismissRequest = { options = null },
                containerColor = ZsColors.Surface,
                title = { Text(doc.name, color = Color.White, maxLines = 2) },
                text = {
                    // One row of icon buttons: Book (PDF only; white, outlined when removing), Rename, Delete (red).
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (doc.kind == DocKind.Pdf) { // only PDFs can be books
                            val inBooks = remember(Books.version) { Books.contains(context, doc.uri) }
                            ActionButton(
                                ZsIcons.Book, if (inBooks) "Remove Book" else "Add Book",
                                fill = if (inBooks) Color.Transparent else Color.White,
                                content = if (inBooks) Color.White else Color.Black,
                                outline = Color.White,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (inBooks) Books.find(context, doc.uri)?.let { Books.remove(context, it.uri) } else Books.add(context, doc)
                                options = null
                            }
                        }
                        ActionButton(ZsIcons.Edit, "Rename", Color.Transparent, Color.White, ZsColors.Secondary, Modifier.weight(1f)) {
                            renaming = doc; options = null
                        }
                        ActionButton(ZsIcons.Delete, "Delete", DeleteRed, Color.White, null, Modifier.weight(1f)) {
                            toDelete = doc; options = null
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { options = null }) { Text("Cancel", color = ZsColors.Secondary) } },
            )
        } else if (vm.kind == DocKind.All) { selected = selected + doc; options = null }
        else { toDelete = doc; options = null }
    }
    // Rename: text box with the name (extension kept as is), Save / Cancel.
    renaming?.let { doc ->
        var name by remember(doc) { mutableStateOf(doc.name.substringBeforeLast('.', doc.name)) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = ZsColors.Surface,
            title = { Text("Rename", color = Color.White) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    suffix = { if (doc.ext.isNotEmpty()) Text(".${doc.ext}", color = ZsColors.Secondary) },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White, unfocusedBorderColor = ZsColors.Secondary, cursorColor = Color.White,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.rename(doc, name) { ok ->
                            if (!ok) android.widget.Toast.makeText(context, "Couldn't rename (name empty, taken, or not allowed)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        renaming = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = ZsColors.Secondary) } },
        )
    }
    if (confirmMany) {
        AlertDialog(
            onDismissRequest = { confirmMany = false },
            containerColor = ZsColors.Surface,
            title = { Text("Delete ${selected.size} files?", color = Color.White) },
            text = { Text("They will be removed from the phone.", color = ZsColors.OnSurface) },
            confirmButton = {
                Button(
                    onClick = {
                        selected.forEach { doc -> Books.find(context, doc.uri)?.let { Books.remove(context, it.uri) } }
                        vm.deleteMany(selected.toList())
                        selected = emptySet(); confirmMany = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeleteRed, contentColor = Color.White),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmMany = false }) { Text("Cancel", color = ZsColors.Secondary) } },
        )
    }
    toDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = ZsColors.Surface,
            title = { Text("Delete this file?", color = Color.White) },
            text = { Text("${doc.name}\nIt will be removed from the phone.", color = ZsColors.OnSurface) },
            confirmButton = {
                Button(
                    onClick = {
                        // A book is taken off the Books list too, so no broken entry is left.
                        Books.find(context, doc.uri)?.let { Books.remove(context, it.uri) }
                        vm.delete(doc); toDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeleteRed, contentColor = Color.White),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel", color = ZsColors.Secondary) } },
        )
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp, end = RailWidth)) {
        // Books are marked inside the PDF list (white border), by file id.
        val bookIds = remember(Books.version) { Books.all(context).map { it.uri.lastPathSegment }.toSet() }
        val shown = when (vm.kind) {
            DocKind.All -> vm.allFiles
            else -> vm.docs.filter { it.kind == vm.kind }
        }
        if (selected.isNotEmpty()) {
            // Select mode bar: ✕ (stop) · "N selected" · red Delete.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { selected = emptySet() }, contentAlignment = Alignment.Center) {
                    Icon(ZsIcons.Close, contentDescription = "Stop selecting", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Text("${selected.size} selected", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(DeleteRed).clickable { confirmMany = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ZsIcons.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("Delete", color = Color.White, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
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
            // Squares, 3 per row; tap = open. Same order as always, but the grid sits at the
            // bottom of the screen (above the nav) and opens scrolled to its end; scroll up for more.
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            LaunchedEffect(vm.kind, shown.size) { if (shown.isNotEmpty()) gridState.scrollToItem(shown.lastIndex) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                // Wider side margins + gaps = slightly smaller squares with a little space between.
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = NavClearance),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            ) {
                gridItems(shown, key = { it.uri.toString() }) { doc ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { vm.open(doc) }, onLongClick = { options = doc })) {
                        Thumbnail(doc.uri, Modifier.fillMaxSize(), sizePx = 360)
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
        // Same order as always; the list sits at the bottom of the screen (above the nav) and
        // opens scrolled to its end, so the rows are in thumb reach. Scroll up for more.
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(vm.kind, shown.size) { if (shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = NavClearance),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            items(shown, key = { it.uri.toString() }) { doc ->
                val ticked = doc in selected
                FileRow(
                    doc,
                    isBook = doc.kind == DocKind.Pdf && doc.uri.lastPathSegment in bookIds,
                    selected = ticked,
                    onLongClick = { options = doc },
                ) {
                    // In select mode a tap ticks / unticks; otherwise it opens the file.
                    if (selected.isNotEmpty()) selected = if (ticked) selected - doc else selected + doc else vm.open(doc)
                }
            }
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
    DocKind.All -> ZsIcons.Folder
    DocKind.Pdf -> ZsIcons.Pdf
    DocKind.Books -> ZsIcons.Book
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
        DocKind.entries.filter { it != DocKind.Books }.forEach { k -> // Books live inside PDF
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
        // Thin line, then the Timer icon (placeholder: does nothing yet).
        Box(Modifier.padding(vertical = 2.dp).size(width = 26.dp, height = 1.dp).background(ZsColors.Secondary).align(Alignment.CenterHorizontally))
        // Opens the Time screen (Calendar · Timer · Alarm).
        val context = androidx.compose.ui.platform.LocalContext.current
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                .clickable { context.startActivity(android.content.Intent(context, com.zerosync.app.alarm.TimeActivity::class.java)) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(ZsIcons.Clock, contentDescription = "Timer, alarm, calendar", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun FileRow(doc: DocFile, onLongClick: () -> Unit, isBook: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ZsColors.Surface)
            // Book = white border (opens at the saved page).
            .then(if (isBook) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier)
            // Selected (All files select mode): lighter card.
            .then(if (selected) Modifier.background(ZsColors.PrimaryContainer) else Modifier)
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
        if (selected) {
            Box(Modifier.padding(start = 8.dp).size(24.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(ZsIcons.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Rounded button: icon on top, short label under it. [outline] null = no border. */
@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    fill: Color,
    content: Color,
    outline: Color?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(fill)
            .then(if (outline != null) Modifier.border(1.5.dp, outline, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        Text(label, color = content, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
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
