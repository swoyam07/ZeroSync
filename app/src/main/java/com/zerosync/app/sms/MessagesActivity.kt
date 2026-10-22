package com.zerosync.app.sms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
private fun TopBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ZsIcons.Back, contentDescription = "Back", tint = Color.White,
            modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack).padding(10.dp))
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
    val list by produceState<List<Pair<Conversation, String>>?>(null, SmsStore.version) {
        value = withContext(Dispatchers.IO) { SmsStore.conversations(context).map { it to nameOf(context, it.address) } }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Messages", onBack) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable(onClick = onNew), contentAlignment = Alignment.Center) {
                Text("+", color = Color.Black, style = MaterialTheme.typography.titleLarge)
            }
        }
        val items = list
        when {
            items == null -> Unit
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No messages", color = ZsColors.Secondary) }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(items, key = { it.first.threadId }) { (conv, name) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(conv.address) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (conv.unread > 0) FontWeight.Bold else FontWeight.Normal)
                            Text(conv.last.body, color = ZsColors.Secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        CarrierLogo(conv.address, Modifier.padding(start = 8.dp).size(20.dp))
                        if (conv.unread > 0) UnreadBadge(conv.unread, Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
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
            TopBar(name, onBack) { CarrierLogo(address, Modifier.padding(end = 8.dp).size(22.dp)) }
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
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        // SAFETY: plain text only. Links are NOT clickable (no auto-linking), nothing in a
                        // message is ever opened or run. Hold to select + copy, then paste it yourself.
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(m.body, color = if (m.incoming) Color.White else Color.Black)
                        }
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
            ) { Icon(ZsIcons.Message, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(22.dp)) }
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
