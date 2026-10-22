package com.zerosync.app.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Arrow colors in Recents (user-approved exception to black/white). */
private val CalledGreen = Color(0xFF34A853)
private val MissedRed = Color(0xFFD32F2F)

/** Most recent calls we read from the phone's history. */
private const val MAX_CALLS = 500

/** One line in Recents: back-to-back calls with the same number and type are grouped. */
data class RecentCall(val number: String, val name: String?, val type: Int, val time: Long, val count: Int, val contact: Contact? = null, val spam: Boolean = false)

/**
 * Recents = Android's own call history (the same list every phone app shows).
 * Read-only; nothing is copied. Tap a line = call that number back.
 */
@Composable
fun RecentsList(onCall: (String) -> Unit, onSave: (Contact) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCallLog(context)) }
    var reload by remember { mutableIntStateOf(0) }
    // Number being saved from a held line (null = no chooser open).
    var choosing by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // Fresh list each time the screen comes back (e.g. after a call).
    LifecycleResumeEffect(Unit) {
        granted = hasCallLog(context)
        reload++
        onPauseOrDispose { }
    }

    if (!granted) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Allow \"Call logs\" to show your recent calls.", color = ZsColors.OnSurface)
            Button(
                onClick = { ask.launch(Manifest.permission.READ_CALL_LOG) },
                modifier = Modifier.padding(top = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Text("Allow") }
        }
        return
    }

    val calls by produceState<List<RecentCall>?>(null, reload, ContactsStore.version, SpamList.version) {
        value = withContext(Dispatchers.IO) { runCatching { loadRecents(context) }.getOrDefault(emptyList()) }
    }
    val list = calls
    choosing?.let { num -> SaveChooser(num, onPick = { c -> choosing = null; onSave(c) }, onDismiss = { choosing = null }) }
    when {
        list == null -> Box(modifier.fillMaxSize())
        list.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No calls yet", color = ZsColors.Secondary)
        }
        else -> LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list) { call ->
                RecentRow(
                    call,
                    // Hold: saved = open its contact; not saved = choose new or add to existing.
                    onLongClick = {
                        if (call.number.isNotBlank()) {
                            val saved = call.contact
                            if (saved != null) onSave(saved) else choosing = localNumber(call.number)
                        }
                    },
                ) { if (call.number.isNotBlank()) onCall(call.number) }
            }
        }
    }
}

@Composable
/** Tapping the row does nothing (no accidental calls); the phone icon calls; hold = options. */
private fun RecentRow(call: RecentCall, onLongClick: () -> Unit, onCall: () -> Unit) {
    val missed = call.type == CallLog.Calls.MISSED_TYPE || call.type == CallLog.Calls.REJECTED_TYPE || call.type == CallLog.Calls.BLOCKED_TYPE
    val (icon, label) = when (call.type) {
        CallLog.Calls.OUTGOING_TYPE -> ZsIcons.CallMade to "Outgoing"
        CallLog.Calls.MISSED_TYPE -> ZsIcons.CallMissed to "Missed"
        CallLog.Calls.REJECTED_TYPE -> ZsIcons.CallMissed to "Declined"
        CallLog.Calls.BLOCKED_TYPE -> ZsIcons.CallMissed to "Blocked"
        else -> ZsIcons.CallReceived to "Incoming"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Arrow color: red = missed / declined, green = calls that connected or were made.
        Icon(icon, contentDescription = label, tint = if (missed) MissedRed else CalledGreen, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            // Saved in ZeroSync = name first; else the phone's name; else the number.
            val title = (call.contact?.name ?: call.name ?: call.number.ifBlank { "Unknown" }) + if (call.count > 1) " (${call.count})" else ""
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                // Spam tag, right of the name.
                if (call.spam) {
                    // Short red "S" badge; screen readers say "Spam".
                    Text(
                        "S",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .semantics { contentDescription = "Spam" }
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFD32F2F))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            // "Missed · 5 min" (+ the number when a name is shown above).
            val numberPart = if (call.contact != null || call.name != null) " · " + localNumber(call.number) else ""
            Text(
                label + " · " + shortTime(call.time) + numberPart,
                color = ZsColors.Secondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // Network logo (NTC / Ncell), nothing for unknown numbers.
        // Right side: one gray pill = network logo | divider | call. (Save a contact: hold the line.)
        CallPill(call.number, onCall, Modifier.padding(start = 8.dp))
    }
}

/**
 * Save an unsaved number: "New contact", or pick an existing contact to add it to
 * (the number is added as Friends; the editor opens so you can change the label and Save).
 */
@Composable
private fun SaveChooser(number: String, onPick: (Contact) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val contacts by produceState(emptyList<Contact>(), ContactsStore.version) {
        value = withContext(Dispatchers.IO) { ContactsStore.all(context) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZsColors.Surface,
        title = { Text("Save $number", color = Color.White) },
        text = {
            Column {
                // Spam on/off for this number.
                val isSpam = remember(SpamList.version) { SpamList.isSpam(context, number) }
                Text(
                    if (isSpam) "✓  Remove from spam" else "⦸  Mark as spam",
                    color = if (isSpam) Color.White else Color(0xFFD32F2F),
                    modifier = Modifier.fillMaxWidth()
                        .clickable { if (isSpam) SpamList.remove(context, number) else SpamList.add(context, number); onDismiss() }
                        .padding(vertical = 12.dp),
                )
                Text(
                    "+  New contact",
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onPick(Contact(numbers = listOf(ContactNumber(number)))) }
                        .padding(vertical = 12.dp),
                )
                if (contacts.isNotEmpty()) {
                    Text("Add to existing", color = ZsColors.Secondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(contacts, key = { it.id }) { c ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onPick(c.copy(numbers = c.numbers + ContactNumber(number))) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(c, 36.dp)
                                Text(c.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ZsColors.Secondary) } },
    )
}

/**
 * Gray rounded pill: network logo | thin divider | green phone. Tapping anywhere on it calls.
 * [roomy] = a bit more space inside (Contacts list).
 */
@Composable
internal fun CallPill(number: String, onCall: () -> Unit, modifier: Modifier = Modifier, roomy: Boolean = false) {
    val gap = if (roomy) 12.dp else 8.dp
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(ZsColors.Surface)
            // The WHOLE pill (logo, divider, phone) is one call button.
            .clickable(enabled = number.isNotBlank(), onClick = onCall)
            .padding(horizontal = if (roomy) 10.dp else 6.dp, vertical = if (roomy) 2.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (carrierOf(number) != null) {
            CarrierLogo(number, Modifier.padding(horizontal = gap).size(22.dp))
            Box(Modifier.size(width = 1.dp, height = 24.dp).background(ZsColors.Secondary.copy(alpha = 0.5f)))
            Box(Modifier.size(width = if (roomy) 4.dp else 0.dp, height = 1.dp))
        }
        Icon(
            ZsIcons.Phone, contentDescription = "Call", tint = CalledGreen,
            modifier = Modifier.size(40.dp).padding(9.dp),
        )
    }
}

/** "now", "5 min", "2 hr", "Yesterday", else a short date like "Oct 21". */
private fun shortTime(time: Long): String {
    val diff = System.currentTimeMillis() - time
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000L} min"
        diff < 86_400_000L -> "${diff / 3_600_000L} hr"
        diff < 2 * 86_400_000L -> "Yesterday"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(time))
    }
}

internal fun hasCallLog(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

internal fun loadRecents(context: Context): List<RecentCall> {
    val out = ArrayList<RecentCall>()
    context.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
        null, null,
        "${CallLog.Calls.DATE} DESC",
    )?.use { c ->
        var read = 0
        while (c.moveToNext() && read < MAX_CALLS) {
            read++
            val number = c.getString(0).orEmpty()
            val name = c.getString(1)?.takeIf { it.isNotBlank() }
            val type = c.getInt(2)
            val time = c.getLong(3)
            val last = out.lastOrNull()
            // Same person, same kind of call, right after each other = one line with a count.
            if (last != null && localNumber(last.number) == localNumber(number) && last.type == type) {
                out[out.lastIndex] = last.copy(count = last.count + 1)
            } else {
                out.add(RecentCall(number, name, type, time, 1, ContactsStore.find(context, number), SpamList.isSpam(context, number)))
            }
        }
    }
    return out
}
