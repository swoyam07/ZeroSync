package com.zerosync.app.phone

import android.content.Context
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors

/** Spam numbers (any amount), kept in ZeroSync's private storage as local numbers (no +977). */
object SpamList {
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("spam_numbers", Context.MODE_PRIVATE)

    fun all(context: Context): List<String> = prefs(context).getStringSet("numbers", emptySet()).orEmpty().sorted()

    fun isSpam(context: Context, number: String): Boolean {
        val key = localNumber(number)
        return key.isNotEmpty() && key in prefs(context).getStringSet("numbers", emptySet()).orEmpty()
    }

    fun add(context: Context, number: String) {
        val key = localNumber(number)
        if (key.isEmpty()) return
        prefs(context).edit { putStringSet("numbers", all(context).toSet() + key) }
        version++
    }

    fun remove(context: Context, number: String) {
        prefs(context).edit { putStringSet("numbers", all(context).toSet() - localNumber(number)) }
        version++
    }
}

/**
 * Android asks the default Phone app about each incoming call BEFORE it rings.
 * Spam number = reject silently: no ringtone, no call screen, no pop-up, no notification.
 * The call still goes into the call history, so it shows in Recents (tagged Spam).
 * Exported only because the system must bind it (BIND_SCREENING_SERVICE).
 */
class SpamScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val spam = details.callDirection == Call.Details.DIRECTION_INCOMING && SpamList.isSpam(this, number)
        val response = CallResponse.Builder().apply {
            if (spam) {
                setDisallowCall(true)
                setRejectCall(true)
                setSkipNotification(true)
                setSkipCallLog(false) // keep it in Recents
            }
        }.build()
        respondToCall(details, response)
    }
}

@Composable
private fun Header(text: String) {
    Text(text, color = ZsColors.Secondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

/** Spam list screen: type a number, or pick from recent calls; Remove takes one out. Back = close. */
@Composable
fun SpamListScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val numbers = remember(SpamList.version) { SpamList.all(context) }
    BackHandler(onBack = onDone)

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ZsIcons.Back, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(24.dp).clickable(onClick = onDone))
            Text("Spam numbers", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
        }
        Text(
            "Calls from these numbers are rejected silently and only show in Recents as Spam.",
            color = ZsColors.Secondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White, unfocusedBorderColor = ZsColors.Secondary,
                    focusedLabelColor = Color.White, unfocusedLabelColor = ZsColors.Secondary, cursorColor = Color.White,
                ),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { SpamList.add(context, input); input = "" },
                enabled = localNumber(input).isNotEmpty(),
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Text("Add") }
        }
        // Recent callers not yet in the list (from the call history), newest first.
        val recents by produceState(emptyList<RecentCall>(), SpamList.version) {
            value = withContext(Dispatchers.IO) {
                if (!hasCallLog(context)) emptyList()
                else runCatching { loadRecents(context) }.getOrDefault(emptyList())
                    .filter { it.number.isNotBlank() && !SpamList.isSpam(context, it.number) }
                    .distinctBy { localNumber(it.number) }
                    .take(30)
            }
        }

        LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Header("In spam (${numbers.size})") }
            if (numbers.isEmpty()) item { Text("No spam numbers", color = ZsColors.Secondary, modifier = Modifier.padding(vertical = 8.dp)) }
            items(numbers, key = { "s$it" }) { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CarrierLogo(n, Modifier.padding(end = 10.dp).size(20.dp))
                    Text(n, color = Color.White, modifier = Modifier.weight(1f))
                    // Take the number out of spam.
                    Text("Remove", color = ZsColors.Secondary, modifier = Modifier.clickable { SpamList.remove(context, n) }.padding(8.dp))
                }
            }
            if (recents.isNotEmpty()) item { Header("Recent calls") }
            items(recents, key = { "r" + localNumber(it.number) }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CarrierLogo(r.number, Modifier.padding(end = 10.dp).size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.contact?.name ?: r.name ?: localNumber(r.number), color = Color.White)
                        if (r.contact != null || r.name != null) Text(localNumber(r.number), color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("+ Spam", color = Color(0xFFD32F2F), modifier = Modifier.clickable { SpamList.add(context, r.number) }.padding(8.dp))
                }
            }
        }
    }
}
