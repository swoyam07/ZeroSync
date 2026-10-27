package com.zerosync.app.sms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerosync.app.phone.CarrierLogo
import com.zerosync.app.phone.ContactsStore
import com.zerosync.app.phone.localNumber
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZeroSyncTheme
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SendGreen = Color(0xFF34A853)

/**
 * Messages: conversation list → one conversation (bubbles + send box) → "+" new message.
 * Opened from the Phone screen's message icon, a message notification (EXTRA_ADDRESS), or
 * "send SMS to" links (exported only because Android requires it for the default SMS app).
 */
class MessagesActivity : ComponentActivity() {
    companion object { const val EXTRA_ADDRESS = "address" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // From a notification, or sms:/smsto: links (number after the colon).
        val start = intent?.getStringExtra(EXTRA_ADDRESS) ?: intent?.data?.schemeSpecificPart?.substringBefore('?')
        setContent { ZeroSyncTheme { MessagesApp(start, onClose = ::finish) } }
    }
}

@Composable
private fun MessagesApp(startAddress: String?, onClose: () -> Unit) {
    // null = conversation list, "" = new message (type a number), else that conversation.
    var open by remember { mutableStateOf(startAddress) }
    Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().imePadding()) {
        when (val a = open) {
            null -> ConversationList(onOpen = { open = it }, onNew = { open = "" }, onBack = onClose)
            else -> ConversationScreen(a, onBack = { if (startAddress != null && open == startAddress) onClose() else open = null })
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, leading: (@Composable () -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ZsIcons.Back, contentDescription = "Back", tint = Color.White,
            modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack).padding(10.dp))
        leading?.invoke() // e.g. the contact's photo
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp))
        action?.invoke()
    }
}

private fun nameOf(context: android.content.Context, address: String) =
    ContactsStore.find(context, address)?.name ?: localNumber(address).ifEmpty { address }

@Composable
private fun ConversationList(onOpen: (String) -> Unit, onNew: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Pair<Conversation, String>?>(null) }
    deleting?.let { (conv, name) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = ZsColors.Surface,
            title = { Text("Delete chat with $name?", color = Color.White) },
            text = { Text("All messages with $name are removed. The saved contact and number stay.", color = ZsColors.OnSurface) },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) { SmsStore.deleteConversation(context, conv.address) }
                        SmsNotifications.clear(context, conv.address)
                        deleting = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                ) { Text("Delete") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("Cancel", color = ZsColors.Secondary) } },
        )
    }
    // Each chat with its shown name and the saved contact (null = not saved).
    val list by produceState<List<ChatRow>?>(null, SmsStore.version, ContactsStore.version) {
        value = withContext(Dispatchers.IO) {
            SmsStore.conversations(context).map { c ->
                val contact = ContactsStore.find(context, c.address)
                ChatRow(c, contact?.name ?: localNumber(c.address).ifEmpty { c.address }, contact)
            }
        }
    }
    var query by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Messages", onBack)
        // Search: name, number or message text (case doesn't matter).
        Field(query, { query = it }, "Search", Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp))
        val q = query.trim()
        val items = list?.let { all ->
            if (q.isEmpty()) all
            else all.filter { r ->
                r.name.contains(q, ignoreCase = true) || r.conv.address.contains(q) || r.conv.last.body.contains(q, ignoreCase = true)
            }
        }
        when {
            items == null -> Unit
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (q.isEmpty()) "No messages" else "No chats match \"$q\"", color = ZsColors.Secondary)
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) { // room so the + never covers the last chat
                items(items, key = { it.conv.threadId }) { r ->
                    val conv = r.conv
                    Row(
                        // Tap = open; hold = delete this whole chat (contact stays saved).
                        Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(conv.address) }, onLongClick = { deleting = conv to r.name }).padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(r.contact, r.name, 44.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (conv.unread > 0) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                Text(shortTime(conv.last.date), color = if (conv.unread > 0) Color.White else ZsColors.Secondary,
                                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                            }
                            Text(conv.last.body, color = ZsColors.Secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        CarrierLogo(conv.address, Modifier.padding(start = 8.dp).size(20.dp))
                        if (conv.unread > 0) UnreadBadge(conv.unread, Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
    // New message: round "+" at the bottom right, easy to reach with the thumb.
    Box(
        Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)
            .size(56.dp).clip(CircleShape).background(ZsColors.Tertiary).clickable(onClick = onNew),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = Color.Black, style = MaterialTheme.typography.headlineSmall)
    }
    }
}

private class ChatRow(val conv: Conversation, val name: String, val contact: com.zerosync.app.phone.Contact?)

/** Saved contact = its photo (or initial, via the Contacts avatar); not saved = gray circle with a "#". */
@Composable
private fun ChatAvatar(contact: com.zerosync.app.phone.Contact?, name: String, size: androidx.compose.ui.unit.Dp) {
    if (contact != null) com.zerosync.app.phone.Avatar(contact, size)
    else Box(Modifier.size(size).clip(CircleShape).background(ZsColors.Surface), contentAlignment = Alignment.Center) {
        Text("#", color = ZsColors.Secondary, style = MaterialTheme.typography.titleMedium)
    }
}

/** List time: today = "14:05", this week = "Mon", older = "Mar 4", other years = "3/4/24". */
private fun shortTime(millis: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val sameYear = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
    val days = (now.timeInMillis - millis) / 86_400_000L
    val pattern = when {
        sameYear && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) -> "HH:mm"
        days < 7 -> "EEE"
        sameYear -> "MMM d"
        else -> "d/M/yy"
    }
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(millis))
}

/** White round count, e.g. "3". */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(22.dp).clip(CircleShape).background(Color.White),
        contentAlignment = Alignment.Center,
    ) { Text(if (count > 99) "99+" else "$count", color = Color.Black, style = MaterialTheme.typography.labelSmall) }
}

/** One conversation. [start] "" = new message: type the number first. */
@Composable
private fun ConversationScreen(start: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(start) }
    var numberInput by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    // Message whose Copy / Delete box is open (null = none).
    var menuFor by remember { mutableStateOf<Sms?>(null) }
    var confirmDelete by remember { mutableStateOf<Sms?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    menuFor?.let { m ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = ZsColors.Surface,
            title = { Text("Message", color = Color.White) },
            text = {
                Column {
                    Text("Copy text", color = Color.White, modifier = Modifier.fillMaxWidth()
                        .clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.body)); menuFor = null }
                        .padding(vertical = 12.dp))
                    Text("Delete message", color = Color(0xFFD32F2F), modifier = Modifier.fillMaxWidth()
                        .clickable { confirmDelete = m; menuFor = null }
                        .padding(vertical = 12.dp))
                }
            },
            confirmButton = {},
            dismissButton = { androidx.compose.material3.TextButton(onClick = { menuFor = null }) { Text("Cancel", color = ZsColors.Secondary) } },
        )
    }
    confirmDelete?.let { m ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = ZsColors.Surface,
            title = { Text("Delete this message?", color = Color.White) },
            text = { Text("Only this message is removed. The conversation stays.", color = ZsColors.OnSurface) },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { scope.launch(Dispatchers.IO) { SmsStore.delete(context, m.id) }; confirmDelete = null },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                ) { Text("Delete") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = ZsColors.Secondary) } },
        )
    }

    // Everything with this person, sent and received, whatever form the number was saved in.
    val messages by produceState(emptyList<Sms>(), address, SmsStore.version) {
        value = if (address.isBlank()) emptyList() else withContext(Dispatchers.IO) { SmsStore.messages(context, address) }
    }
    // Opening = read: clear unread + this sender's notification.
    LaunchedEffect(address, messages.size) {
        if (address.isNotBlank() && messages.any { it.incoming && !it.read }) {
            withContext(Dispatchers.IO) { SmsStore.markRead(context, address) }
            SmsNotifications.clear(context, address)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (address.isBlank()) {
            TopBar("New message", onBack)
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("To", color = ZsColors.Secondary, modifier = Modifier.padding(end = 12.dp))
                Field(numberInput, { numberInput = it }, "Number", Modifier.weight(1f), KeyboardType.Phone)
            }
        } else {
            val name by produceState(localNumber(address).ifEmpty { address }, address) {
                value = withContext(Dispatchers.IO) { nameOf(context, address) }
            }
            val contact by produceState<com.zerosync.app.phone.Contact?>(null, address, ContactsStore.version) {
                value = withContext(Dispatchers.IO) { ContactsStore.find(context, address) }
            }
            TopBar(name, onBack, leading = { ChatAvatar(contact, name, 34.dp) }) { CarrierLogo(address, Modifier.padding(end = 8.dp).size(22.dp)) }
        }

        // Bubbles: received on the left (gray), sent on the right (white). Opens at the newest.
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
        ) {
            items(messages, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.incoming) Arrangement.Start else Arrangement.End) {
                    Column(
                        Modifier
                            .widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (m.incoming) ZsColors.Surface else Color.White)
                            // Hold a message = Copy / Delete (only that message, not the chat).
                            .combinedClickable(onClick = {}, onLongClick = { menuFor = m })
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        // SAFETY: plain text only. Links are NOT clickable (no auto-linking), nothing in a
                        // message is ever opened or run. Hold → Copy, then paste it yourself.
                        Text(m.body, color = if (m.incoming) Color.White else Color.Black)
                        Text(
                            java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(m.date)) +
                                if (m.failed) " · Not sent" else "",
                            color = if (m.failed) Color(0xFFD32F2F) else if (m.incoming) ZsColors.Secondary else Color.DarkGray,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // Send box + green send button.
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(text, { text = it }, "Message", Modifier.weight(1f), singleLine = false)
            val target = address.ifBlank { numberInput }
            val canSend = text.isNotBlank() && localNumber(target).isNotEmpty()
            Box(
                Modifier.padding(start = 8.dp).size(48.dp).clip(CircleShape).background(if (canSend) SendGreen else ZsColors.Surface)
                    .clickable(enabled = canSend) {
                        val body = text
                        text = ""
                        if (address.isBlank()) address = target
                        scope.launch(Dispatchers.IO) { SmsStore.send(context, target, body) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Typing = send arrow; empty box = message icon.
                Icon(if (text.isNotBlank()) ZsIcons.Send else ZsIcons.Message, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Rounded dark input box. */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 5,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.clip(RoundedCornerShape(22.dp)).background(ZsColors.Surface).padding(horizontal = 16.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, color = ZsColors.Secondary)
                inner()
            }
        },
    )
}
