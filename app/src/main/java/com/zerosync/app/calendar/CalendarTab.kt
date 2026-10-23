package com.zerosync.app.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

private val WeekLetters = listOf("S", "M", "T", "W", "T", "F", "S")
private val MonthNames = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val ReminderChoices = listOf(0 to "None", 1 to "1 day", 3 to "3 days", 7 to "1 week")

/** Does [e] happen on this date (yearly events match every year)? */
private fun Event.isOn(y: Int, m: Int, d: Int) = month == m && day == d && (yearly || year == y)

private fun time12(h: Int, m: Int) = "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")

/** Calendar tab: month grid (dots on event days) + the selected day's events + "+". */
@Composable
fun CalendarTab() {
    val context = LocalContext.current
    val today = remember { Calendar.getInstance() }
    var year by remember { mutableIntStateOf(today.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(today.get(Calendar.MONTH)) }
    var day by remember { mutableIntStateOf(today.get(Calendar.DAY_OF_MONTH)) }
    var editing by remember { mutableStateOf<Event?>(null) }
    var toDelete by remember { mutableStateOf<Event?>(null) }
    val events by produceState(emptyList<Event>(), EventStore.version) {
        value = withContext(Dispatchers.IO) { EventStore.cleanup(context); EventStore.all(context) }
    }

    editing?.let { EventEditor(it, onDone = { editing = null }); return }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Month header: ‹ October 2026 ›
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clip(CircleShape).clickable { if (month == 0) { month = 11; year-- } else month--; day = 1 }.padding(horizontal = 16.dp, vertical = 4.dp))
            Text("${MonthNames[month]} $year", color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("›", color = Color.White, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clip(CircleShape).clickable { if (month == 11) { month = 0; year++ } else month++; day = 1 }.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        // Week letters.
        Row(Modifier.fillMaxWidth()) {
            WeekLetters.forEach { Text(it, color = ZsColors.Secondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        // Day grid.
        val first = Calendar.getInstance().apply { clear(); set(year, month, 1) }
        val offset = first.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val cells = offset + daysInMonth
        for (row in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val d = row * 7 + col - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (d in 1..daysInMonth) {
                            val selected = d == day
                            val isToday = year == today.get(Calendar.YEAR) && month == today.get(Calendar.MONTH) && d == today.get(Calendar.DAY_OF_MONTH)
                            val has = events.any { it.isOn(year, month, d) }
                            Column(
                                Modifier.fillMaxSize().clip(CircleShape)
                                    .background(if (selected) Color.White else Color.Transparent)
                                    .then(if (isToday && !selected) Modifier.border(1.dp, Color.White, CircleShape) else Modifier)
                                    .clickable { day = d },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("$d", color = if (selected) Color.Black else Color.White)
                                // Dot = this day has an event.
                                Box(Modifier.size(5.dp).clip(CircleShape).background(if (has) (if (selected) Color.Black else Color.White) else Color.Transparent))
                            }
                        }
                    }
                }
            }
        }

        // Upcoming: nearest first. Finished one-time events are gone (auto-deleted);
        // a yearly event that has passed moves down to its date next year.
        val upcoming = events.mapNotNull { e -> EventScheduler.occurrence(e)?.let { e to it } }.sortedBy { it.second }
        Text("Upcoming", color = ZsColors.Secondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (upcoming.isEmpty()) item { Text("No upcoming events", color = ZsColors.Secondary) }
            items(upcoming, key = { it.first.id }) { (e, at) ->
                val today = Calendar.getInstance()
                val c = Calendar.getInstance().apply { timeInMillis = at }
                val isToday = c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ZsColors.Surface)
                        .combinedClickable(onClick = { editing = e }, onLongClick = { toDelete = e })
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Date badge: "Today" or e.g. "24 Oct".
                    Column(
                        Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(if (isToday) Color.White else Color.Black),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val ink = if (isToday) Color.Black else Color.White
                        Text(if (isToday) "Today" else "${c.get(Calendar.DAY_OF_MONTH)}", color = ink, style = if (isToday) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium)
                        if (!isToday) Text(MonthNames[c.get(Calendar.MONTH)].take(3), color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(e.title.ifBlank { "Event" }, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val reminder = ReminderChoices.firstOrNull { it.first == e.remindDays }?.second ?: "None"
                        Text(
                            time12(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) + (if (e.yearly) " · Every year" else "") + " · Remind: $reminder",
                            color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 20.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color.White)
                    .clickable { editing = Event(year = year, month = month, day = day) },
                contentAlignment = Alignment.Center,
            ) { Icon(ZsIcons.Add, contentDescription = "Add event", tint = Color.Black, modifier = Modifier.size(30.dp)) }
        }
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = ZsColors.Surface,
            title = { Text("Delete \"${e.title.ifBlank { "Event" }}\"?", color = Color.White) },
            text = { Text("Its reminder and ring are removed too.", color = ZsColors.OnSurface) },
            confirmButton = {
                Button(onClick = { EventStore.delete(context, e.id); toDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel", color = ZsColors.Secondary) } },
        )
    }
}

/** Create / edit an event: title, date, time (default 8:00 AM), note, every year, reminder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditor(start: Event, onDone: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(start.title) }
    var note by remember { mutableStateOf(start.note) }
    var yearly by remember { mutableStateOf(start.yearly) }
    var remind by remember { mutableIntStateOf(start.remindDays) }
    var date by remember { mutableStateOf(Triple(start.year, start.month, start.day)) }
    var pickDate by remember { mutableStateOf(false) }
    // Old "all day" events open at 8:00 AM (what they rang at).
    val time = rememberTimePickerState(if (start.allDay) MORNING_HOUR else start.hour, if (start.allDay) 0 else start.minute, is24Hour = false)
    BackHandler(onBack = onDone)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Field(title, { title = it }, "Title", big = true)

        // Date row (tap to change).
        Row(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ZsColors.Surface)
                .clickable { pickDate = true }.padding(16.dp),
        ) {
            Text("Date", color = ZsColors.Secondary, modifier = Modifier.weight(1f))
            Text("${date.third} ${MonthNames[date.second]} ${date.first}", color = Color.White)
        }

        // Time: starts at 8:00 AM for new events; change it if needed, or just save.
        run {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
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
            }
        }

        Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ZsColors.Surface).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Every year", color = Color.White, modifier = Modifier.weight(1f))
            WhiteSwitch(yearly) { yearly = it }
        }

        // Reminder: None / 1 day / 3 days / 1 week before.
        Text("Remind me before", color = ZsColors.Secondary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReminderChoices.forEach { (days, label) ->
                val on = remind == days
                Text(
                    label,
                    color = if (on) Color.Black else Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else ZsColors.Surface)
                        .clickable { remind = days }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        Box(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ZsColors.Surface).padding(16.dp)) {
            Field(note, { note = it }, "Note (optional)", singleLine = false)
        }

        Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Cancel", color = ZsColors.Secondary) }
            Button(
                onClick = {
                    EventStore.save(
                        context,
                        start.copy(
                            title = title.trim(), year = date.first, month = date.second, day = date.third,
                            hour = time.hour, minute = time.minute, allDay = false, note = note.trim(),
                            yearly = yearly, remindDays = remind, doneFor = 0L, // edited = active again
                        ),
                    )
                    onDone()
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Text("Save") }
        }
    }

    if (pickDate) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(date.first, date.second, date.third) }.timeInMillis,
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
                        date = Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
                    }
                    pickDate = false
                }) { Text("OK", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel", color = ZsColors.Secondary) } },
            colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = ZsColors.Surface),
        ) {
            androidx.compose.material3.DatePicker(
                state = state,
                colors = androidx.compose.material3.DatePickerDefaults.colors(
                    containerColor = ZsColors.Surface, selectedDayContainerColor = Color.White, selectedDayContentColor = Color.Black,
                    todayDateBorderColor = Color.White, todayContentColor = Color.White, dayContentColor = Color.White,
                    weekdayContentColor = ZsColors.Secondary, titleContentColor = Color.White, headlineContentColor = Color.White,
                    navigationContentColor = Color.White, yearContentColor = Color.White, selectedYearContainerColor = Color.White,
                    selectedYearContentColor = Color.Black, currentYearContentColor = Color.White,
                ),
            )
        }
    }
}

@Composable
private fun WhiteSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.Black, checkedTrackColor = Color.White,
            uncheckedThumbColor = ZsColors.Secondary, uncheckedTrackColor = Color.Black, uncheckedBorderColor = ZsColors.Secondary,
        ),
    )
}

/** Borderless text field with a gray placeholder. */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, big: Boolean = false, singleLine: Boolean = true) {
    val style = (if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge).copy(color = Color.White)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = style,
        cursorBrush = SolidColor(Color.White),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, style = style.copy(color = ZsColors.Secondary, fontWeight = FontWeight.Normal))
                inner()
            }
        },
    )
}
