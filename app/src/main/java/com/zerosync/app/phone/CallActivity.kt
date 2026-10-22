package com.zerosync.app.phone

import android.app.NotificationManager
import android.os.Bundle
import android.os.SystemClock
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import com.zerosync.app.ui.ZsIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerosync.app.ui.theme.ZeroSyncTheme
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Red for end/decline: same approved danger-color exception as Delete. */
private val EndRed = Color(0xFFD32F2F)

/**
 * Full-screen call screen. Shows over the lock screen and turns the screen on (manifest).
 * Ringing: swipe the handle up = answer, down = decline. Then the in-call screen.
 * Closes itself when the call ends.
 */
class CallActivity : ComponentActivity() {
    companion object {
        /** Set by the pop-up's Answer button: answer as soon as the screen opens. */
        const val EXTRA_ANSWER = "answer"
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        answerIfAsked(intent)
    }

    private fun answerIfAsked(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ANSWER, false) == true && CallManager.state == Call.STATE_RINGING) {
            CallManager.answer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        answerIfAsked(intent)
        setContent {
            ZeroSyncTheme {
                val call = CallManager.call
                val state = CallManager.state
                LaunchedEffect(call, state) {
                    if (call == null || state == Call.STATE_DISCONNECTED) finish()
                    // Answered or declined: the incoming-call banner is no longer needed.
                    if (state != Call.STATE_RINGING) getSystemService(NotificationManager::class.java).cancelAll()
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    when (state) {
                        Call.STATE_RINGING -> IncomingCall()
                        Call.STATE_SELECT_PHONE_ACCOUNT -> SimPicker()
                        else -> InCall()
                    }
                }
            }
        }
    }
}

@Composable
private fun CallerHeader(status: String) {
    Column(Modifier.statusBarsPadding().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(status, color = ZsColors.Secondary, style = MaterialTheme.typography.labelLarge)
        // In call: saved name big with the number under it; else just the number.
        val context = androidx.compose.ui.platform.LocalContext.current
        val number = CallManager.number
        val name by androidx.compose.runtime.produceState<String?>(null, number) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ContactsStore.find(context, number)?.name }
        }
        Text(
            name ?: number,
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 12.dp),
        )
        // Number (when a name is shown) + network logo (NTC / Ncell), nothing if unknown.
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CarrierLogo(number, Modifier.size(22.dp))
            if (name != null) Text(number, color = ZsColors.Secondary, modifier = Modifier.padding(start = 8.dp))
        }
        // Which SIM this call uses, e.g. "SIM 1 · Ncell".
        val sim by androidx.compose.runtime.produceState<SimCard?>(null, CallManager.call, CallManager.state) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                simForCall(context, CallManager.call?.details?.accountHandle)
            }
        }
        sim?.let { SimChip(it, Modifier.padding(top = 10.dp)) }
    }
}

// ---------- Ringing: layout like the stock Android call screen ----------
// Number big at the top, "Mobile • number" under it, a big wavy-edged photo spot (empty for
// now), a Message chip, then a bar: Decline · [phone handle] · Answer.
// Drag the handle right = answer, left = decline.

/** Circle with soft waves around the edge (like the reference photo frame). */
private val WavyCircle = androidx.compose.foundation.shape.GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = minOf(cx, cy)
    val steps = 180
    for (i in 0..steps) {
        val a = 2 * Math.PI * i / steps
        val rr = r * (0.94f + 0.06f * kotlin.math.cos(10 * a).toFloat()) // 10 soft bumps
        val x = cx + rr * kotlin.math.cos(a).toFloat()
        val y = cy + rr * kotlin.math.sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
private fun IncomingCall() {
    val number = CallManager.number
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Saved contact: name big, "Work • number" under it. Unknown: number big.
        val context = androidx.compose.ui.platform.LocalContext.current
        val contact by androidx.compose.runtime.produceState<Contact?>(null, number) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ContactsStore.find(context, number) }
        }
        val saved = contact
        val label = saved?.numbers?.firstOrNull { localNumber(it.number) == localNumber(number) }?.label?.title ?: "Mobile"
        Text(saved?.name ?: number, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Normal, maxLines = 1, modifier = Modifier.padding(top = 48.dp))
        // "<label> • number" with the network logo (NTC / Ncell) when we can tell.
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CarrierLogo(number, Modifier.padding(end = 8.dp).size(22.dp))
            Text("$label • $number", color = ZsColors.OnSurface.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge)
        }

        // Photo spot: empty for now (caller photos come with contacts).
        Box(
            Modifier
                .padding(top = 32.dp)
                .fillMaxWidth(0.82f)
                .aspectRatio(1f)
                .clip(WavyCircle)
                .background(ZsColors.Surface)
                // Network color around the photo: NTC blue, Ncell purple; none if unknown.
                .then(
                    carrierOf(number)?.let { Modifier.border(5.dp, Color(it.color), WavyCircle) } ?: Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // No photo: default person picture. Saved photo is drawn on top of it.
            Icon(ZsIcons.Contact, contentDescription = null, tint = ZsColors.Secondary, modifier = Modifier.fillMaxSize(0.4f))
            // Contact photo if saved.
            if (saved?.hasPhoto == true) {
                val photo by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, saved.id) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ContactsStore.photo(context, saved.id) }
                }
                photo?.let {
                    androidx.compose.foundation.Image(
                        it.asImageBitmap(), contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Saved contact's description, under the photo.
        saved?.description?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                note,
                color = ZsColors.OnSurface.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Unknown number: Spam = add to the spam list and decline (future calls won't ring).
        if (saved == null && number != "Unknown") {
            Text(
                "Spam",
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(EndRed)
                    .clickable { SpamList.add(context, number); CallManager.decline() }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        // Message: decline with a quick text (sent by the phone as a normal SMS).
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(ZsColors.Surface)
                .clickable { CallManager.declineWithMessage("Can't talk now. I'll call you back.") }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ZsIcons.Message, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text("Message", color = Color.White, modifier = Modifier.padding(start = 8.dp))
        }
        }
        AnswerBar(Modifier.padding(top = 20.dp, bottom = 72.dp))
    }
}

/** Bar: Decline (left) · white phone handle (middle) · Answer (right). Drag handle to choose. */
@Composable
private fun AnswerBar(modifier: Modifier = Modifier) {
    // Handle position. Past the edge it STAYS there (no jump back to the middle) and the
    // call is answered / declined; the in-call screen then replaces it directly.
    // Let go before the edge = it glides back to the middle.
    val drag = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(50))
            .background(ZsColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Text("Decline", color = Color.White, modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp))
        Text("Answer", color = Color.White, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp))
        Box(
            Modifier
                .offset { IntOffset(drag.value.roundToInt(), 0) }
                .size(width = 96.dp, height = 68.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .pointerInput(Unit) {
                    val limit = 80.dp.toPx()
                    val edge = limit * 1.3f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (done) return@detectHorizontalDragGestures
                            scope.launch {
                                when {
                                    drag.value >= limit -> {
                                        done = true
                                        drag.animateTo(edge, androidx.compose.animation.core.tween(90))
                                        CallManager.answer()
                                    }
                                    drag.value <= -limit -> {
                                        done = true
                                        drag.animateTo(-edge, androidx.compose.animation.core.tween(90))
                                        CallManager.decline()
                                    }
                                    else -> drag.animateTo(0f, androidx.compose.animation.core.tween(180))
                                }
                            }
                        },
                        onDragCancel = { if (!done) scope.launch { drag.animateTo(0f, androidx.compose.animation.core.tween(180)) } },
                    ) { change, dx ->
                        if (done) return@detectHorizontalDragGestures
                        change.consume()
                        scope.launch { drag.snapTo((drag.value + dx).coerceIn(-edge, edge)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(ZsIcons.Phone, contentDescription = "Drag right to answer, left to decline", tint = Color.Black, modifier = Modifier.size(28.dp))
        }
    }
}

// ---------- Two SIMs: Android asks which one to call from ----------

@Composable
private fun SimPicker() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val telecom = remember { context.getSystemService(android.telecom.TelecomManager::class.java) }
    val accounts = remember(CallManager.call) {
        CallManager.call?.details?.intentExtras
            ?.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS, android.telecom.PhoneAccountHandle::class.java)
            .orEmpty()
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        CallerHeader("Call with which SIM?")
        Spacer(Modifier.weight(1f))
        accounts.forEachIndexed { i, handle ->
            // SIM name from the phone (e.g. carrier name); fall back to "SIM 1/2".
            val label = runCatching { telecom.getPhoneAccount(handle)?.label?.toString() }.getOrNull() ?: "SIM ${i + 1}"
            ToggleKey("SIM ${i + 1} · $label", false) { CallManager.chooseSim(handle) }
            Spacer(Modifier.size(14.dp))
        }
        Box(
            Modifier.padding(top = 14.dp, bottom = 40.dp).size(72.dp).clip(CircleShape).background(EndRed)
                .clickable { CallManager.hangUp() },
            contentAlignment = Alignment.Center,
        ) { Text("Cancel", color = Color.White) }
    }
}

// ---------- Connected: timer, mute, speaker, keypad, hold, end ----------

@Composable
private fun InCall() {
    var keypad by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    val state = CallManager.state

    // Call timer, ticking once a second only while this screen is open.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val status = when (state) {
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW -> "Calling…"
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_ACTIVE -> CallManager.connectedAt.takeIf { it > 0 }?.let { formatDuration(now - it) } ?: ""
        else -> ""
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        CallerHeader(status)
        Spacer(Modifier.weight(1f))
        if (keypad) {
            Text(digits, color = Color.White, fontSize = 26.sp, modifier = Modifier.padding(bottom = 12.dp))
            Dialpad(onKey = { c -> digits += c; CallManager.pressKey(c) })
        } else {
            // One row of round buttons.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ToggleKey("Mute", CallManager.muted, ZsIcons.MicOff) { CallManager.toggleMute() }
                ToggleKey("Speaker", CallManager.speaker, ZsIcons.Volume) { CallManager.toggleSpeaker() }
                ToggleKey("Keypad", false, ZsIcons.Dialpad) { keypad = true }
                ToggleKey("Hold", state == Call.STATE_HOLDING, ZsIcons.Pause) { CallManager.toggleHold() }
            }
        }
        Row(Modifier.padding(top = 28.dp, bottom = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (keypad) {
                Text("Hide", color = ZsColors.Secondary, modifier = Modifier.padding(end = 28.dp).clickable { keypad = false })
            }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(EndRed).clickable { CallManager.hangUp() },
                contentAlignment = Alignment.Center,
            ) { Icon(ZsIcons.CallEnd, contentDescription = "End call", tint = Color.White, modifier = Modifier.size(34.dp)) }
        }
    }
}

/** Rounded-square button: white when on, outlined when off. With [icon]: icon only (label = description). */
@Composable
private fun ToggleKey(label: String, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    // Icon buttons are circles; text buttons (SIM picker) are rounded rectangles.
    val shape = if (icon != null) CircleShape else RoundedCornerShape(18.dp)
    Box(
        Modifier
            .size(width = if (icon != null) 64.dp else if (label.length > 10) 260.dp else 120.dp, height = 64.dp)
            .clip(shape)
            .background(if (on) Color.White else Color.Black)
            .border(1.dp, ZsColors.Secondary, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(28.dp))
        } else {
            Text(label, color = if (on) Color.Black else Color.White)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
