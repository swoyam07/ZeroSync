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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        PillSwitch(
            options = DocKind.entries,
            selected = vm.kind,
            label = { k -> "${k.label} ${vm.docs.count { it.kind == k }}" },
            onSelect = { vm.kind = it },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 12.dp, bottom = 8.dp)
                .horizontalScroll(rememberScrollState()),
        )
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
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = NavClearance),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.uri.toString() }) { doc -> FileRow(doc) { vm.open(doc) } }
        }
    }
}

@Composable
private fun FileRow(doc: DocFile, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ZsColors.Surface)
            .clickable(onClick = onClick)
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
