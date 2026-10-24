package com.zerosync.app.alarm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.zerosync.app.music.MusicRepository
import com.zerosync.app.ui.PillSwitch
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZeroSyncTheme
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Time screen, opened from the clock icon in the File section (not in the main nav).
 * Tabs: Calendar · Alarm (default) — tap the pill or swipe sideways.
 */
class TimeActivity : ComponentActivity() {
    companion object { const val EXTRA_TAB = "tab" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val startTab = intent?.getIntExtra(EXTRA_TAB, 1) ?: 1
        setContent { ZeroSyncTheme { TimeScreen(startTab, onClose = ::finish) } }
    }
}

private val Tabs = listOf("Calendar", "Alarm")

@Composable
private fun TimeScreen(startTab: Int, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(startTab) }
    // Back: Calendar → Alarm first; Back on Alarm → leave to the File section.
    BackHandler { if (tab != 1) tab = 1 else onClose() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            // Swipe sideways: right = Calendar side, left = Alarm side.
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                    val limit = 80.dp.toPx()
                    if (dx < -limit) tab = (tab + 1).coerceAtMost(Tabs.lastIndex)
                    if (dx > limit) tab = (tab - 1).coerceAtLeast(0)
                }) { _, amount -> dx += amount }
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ZsIcons.Back, contentDescription = "Back", tint = Color.White,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose).padding(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                PillSwitch(options = Tabs.indices.toList(), selected = tab, label = { Tabs[it] }, onSelect = { tab = it })
            }
            Box(Modifier.size(40.dp))
        }
        when (tab) {
            1 -> AlarmTab()
            else -> com.zerosync.app.calendar.CalendarTab()
        }
    }
}

// ---------------- Alarm tab ----------------

private val DayLetters = listOf(Calendar.SUNDAY to "S", Calendar.MONDAY to "M", Calendar.TUESDAY to "T",
    Calendar.WEDNESDAY to "W", Calendar.THURSDAY to "T", Calendar.FRIDAY to "F", Calendar.SATURDAY to "S")

private fun daysText(days: Set<Int>) = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Every day"
    else -> DayLetters.filter { it.first in days }.joinToString(" ") {
        listOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[it.first]
    }
}

@Composable
private fun AlarmTab() {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var toDelete by remember { mutableStateOf<Alarm?>(null) }
    var resume by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resume++; onPauseOrDispose { } }
    val alarms by produceState(emptyList<Alarm>(), AlarmStore.version) {
        value = withContext(Dispatchers.IO) { AlarmStore.all(context) }
    }

    editing?.let { a -> AlarmEditor(a, onDone = { editing = null }); return }

    Column(Modifier.fillMaxSize()) {
        // Permissions needed for alarms — shown ONLY here (user's choice).
        PermissionCards(resume)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (alarms.isEmpty()) item {
                Text("No alarms. Tap + to add one.", color = ZsColors.Secondary, modifier = Modifier.fillMaxWidth().padding(top = 60.dp), textAlign = TextAlign.Center)
            }
            items(alarms, key = { it.id }) { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(ZsColors.Surface)
                        .combinedClickable(onClick = { editing = a }, onLongClick = { toDelete = a })
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.time12, color = if (a.enabled) Color.White else ZsColors.Secondary, fontSize = 40.sp, fontWeight = FontWeight.Light)
                        Text("${daysText(a.days)} · ${a.soundName}", color = ZsColors.Secondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(
                        checked = a.enabled,
                        onCheckedChange = { on -> AlarmStore.save(context, a.copy(enabled = on)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black, checkedTrackColor = Color.White,
                            uncheckedThumbColor = ZsColors.Secondary, uncheckedTrackColor = Color.Black, uncheckedBorderColor = ZsColors.Secondary,
                        ),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 20.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color.White)
                    .clickable { val c = Calendar.getInstance(); editing = Alarm(0, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) },
                contentAlignment = Alignment.Center,
            ) { Icon(ZsIcons.Add, contentDescription = "Add alarm", tint = Color.Black, modifier = Modifier.size(30.dp)) }
        }
    }

    toDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = ZsColors.Surface,
            title = { Text("Delete alarm ${a.time12}?", color = Color.White) },
            confirmButton = {
                Button(onClick = { AlarmStore.delete(context, a.id); toDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel", color = ZsColors.Secondary) } },
        )
    }
}

/** Cards for anything alarms need that isn't allowed yet. Each opens the right settings page. */
@Composable
private fun PermissionCards(refresh: Int) {
    val context = LocalContext.current
    val nm = context.getSystemService(NotificationManager::class.java)
    val needs = remember(refresh) {
        buildList {
            if (!nm.areNotificationsEnabled()) add("Notifications are off — the alarm can't show." to Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            if (!nm.canUseFullScreenIntent()) add("Allow full-screen alerts so the alarm fills the lock screen." to Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            if (!Settings.canDrawOverlays(context)) add("Allow \"Display over other apps\" for the small alarm pop-up." to Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        }
    }
    needs.forEach { (text, action) ->
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(ZsColors.Surface).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = ZsColors.OnSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val i = if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    else Intent(action, Uri.parse("package:${context.packageName}"))
                    runCatching { context.startActivity(i) }
                },
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Text("Allow") }
        }
    }
}

/** Create / edit: time, repeat days (7 checkboxes), sound (default or a song). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(start: Alarm, onDone: () -> Unit) {
    val context = LocalContext.current
    val time = rememberTimePickerState(start.hour, start.minute, is24Hour = false)
    var days by remember { mutableStateOf(start.days) }
    var sound by remember { mutableStateOf(start.soundUri to start.soundName) }
    var phraseOn by remember { mutableStateOf(start.stopPhrase.isNotBlank()) }
    var phrase by remember { mutableStateOf(start.stopPhrase) }
    var pickSound by remember { mutableStateOf(false) }
    BackHandler(onBack = onDone)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TimePicker(
            state = time,
            colors = TimePickerDefaults.colors(
                clockDialColor = ZsColors.Surface, selectorColor = Color.White, containerColor = Color.Black,
                clockDialSelectedContentColor = Color.Black, clockDialUnselectedContentColor = Color.White,
                periodSelectorSelectedContainerColor = Color.White, periodSelectorSelectedContentColor = Color.Black,
                periodSelectorUnselectedContentColor = Color.White, periodSelectorBorderColor = ZsColors.Secondary,
                timeSelectorSelectedContainerColor = Color.White, timeSelectorSelectedContentColor = Color.Black,
                timeSelectorUnselectedContainerColor = ZsColors.Surface, timeSelectorUnselectedContentColor = Color.White,
            ),
        )
        // Repeat: tap days to check them; none = rings once.
        Text("Repeat", color = ZsColors.Secondary, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayLetters.forEach { (d, letter) ->
                val on = d in days
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (on) Color.White else Color.Black)
                        .border(1.dp, ZsColors.Secondary, CircleShape)
                        .clickable { days = if (on) days - d else days + d },
                    contentAlignment = Alignment.Center,
                ) { Text(letter, color = if (on) Color.Black else Color.White) }
            }
        }
        Text(daysText(days), color = ZsColors.Secondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))

        // Sound.
        Row(
            Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ZsColors.Surface)
                .clickable { pickSound = true }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sound", color = ZsColors.Secondary, modifier = Modifier.weight(1f))
            Text(sound.second, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Optional: type a text to stop (swipe can only snooze).
        Row(
            Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ZsColors.Surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Type to stop", color = Color.White)
                Text("Swipe only snoozes; typing the text stops it", color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall)
            }
            Switch(
                checked = phraseOn,
                onCheckedChange = { phraseOn = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black, checkedTrackColor = Color.White,
                    uncheckedThumbColor = ZsColors.Secondary, uncheckedTrackColor = Color.Black, uncheckedBorderColor = ZsColors.Secondary,
                ),
            )
        }
        if (phraseOn) {
            androidx.compose.foundation.text.BasicTextField(
                value = phrase,
                onValueChange = { phrase = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(ZsColors.Surface).padding(16.dp),
                decorationBox = { inner -> Box { if (phrase.isEmpty()) Text("Text to type, e.g. I am awake", color = ZsColors.Secondary); inner() } },
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Cancel", color = ZsColors.Secondary) }
            Button(
                onClick = {
                    AlarmStore.save(context, start.copy(hour = time.hour, minute = time.minute, days = days, enabled = true, soundUri = sound.first, soundName = sound.second, stopPhrase = if (phraseOn) phrase.trim() else ""))
                    onDone()
                },
                enabled = !phraseOn || phrase.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Text("Save") }
        }
    }

    if (pickSound) SoundPicker(onPick = { uri, name -> sound = uri to name; pickSound = false }, onDismiss = { pickSound = false })
}

/** "Default alarm" or any song from your music folders. */
@Composable
private fun SoundPicker(onPick: (String?, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val songs by produceState(emptyList<Pair<String, String>>()) {
        value = runCatching {
            MusicRepository(context.contentResolver).loadSongs().map { it.uri.toString() to it.title }
        }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZsColors.Surface,
        title = { Text("Alarm sound", color = Color.White) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text("Default alarm", color = Color.White, modifier = Modifier.fillMaxWidth().clickable { onPick(null, "Default alarm") }.padding(vertical = 12.dp))
                }
                items(songs) { (uri, title) ->
                    Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(uri, title) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ZsColors.Secondary) } },
    )
}
