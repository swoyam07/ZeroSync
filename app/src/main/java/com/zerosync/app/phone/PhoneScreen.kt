package com.zerosync.app.phone

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Icon
import com.zerosync.app.ui.ZsIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.zerosync.app.ui.theme.ZeroSyncTheme
import com.zerosync.app.ui.theme.ZsColors

/** Room at the bottom so the dial pad clears the nav strip. */
private val NavClearance = 88.dp

/**
 * Dial screen Android opens for "dial a number" (e.g. tapping a phone number in another app).
 * Android requires this from any default Phone app. Exported only for that (allowed by the rules).
 */
class DialerActivity : ComponentActivity() {
    companion object {
        /** Opened from a missed-call notification: clear that number's count. */
        const val EXTRA_FROM_MISSED = "from_missed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_FROM_MISSED)?.let { MissedCallLog.clear(this, it) }
        val start = intent?.data?.schemeSpecificPart.orEmpty()
        setContent { ZeroSyncTheme { PhoneScreen(startNumber = start, bottomPadding = 24.dp) } }
    }
}

/** Phone tab: setup (become default Phone app) + dial pad. */
@Composable
fun PhoneScreen(startNumber: String = "", bottomPadding: androidx.compose.ui.unit.Dp = NavClearance) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(isDefaultDialer(context)) }
    var number by remember { mutableStateOf(startNumber) }
    var phoneTab by remember { mutableStateOf(PhoneTab.Keypad) }
    // Contact being created/edited (null = not editing). Fills the whole Phone screen.
    var editing by remember { mutableStateOf<Contact?>(null) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = isDefaultDialer(context)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    // Call button: ask for the call permission first if it's missing, then call.
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) placeCall(context, number)
    }
    var notificationsOn by remember { mutableStateOf(notificationsAllowed(context)) }
    var overlayOn by remember { mutableStateOf(CallOverlay.canShow(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = notificationsAllowed(context)
        overlayOn = CallOverlay.canShow(context)
        isDefault = isDefaultDialer(context)
        onPauseOrDispose { }
    }

    editing?.let { c ->
        Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().padding(bottom = bottomPadding)) {
            ContactEditor(c, onDone = { editing = null })
        }
        return
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().padding(bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!isDefault) {
            Column(
                Modifier.padding(24.dp).clip(RoundedCornerShape(16.dp)).background(ZsColors.Surface).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "To show ZeroSync's call screen, set it as the default Phone app. " +
                        "You can switch back any time in Settings → Apps → Default apps → Phone app.",
                    color = ZsColors.OnSurface,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = {
                        // Ask once for notifications (needed for the incoming-call screen).
                        permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CALL_PHONE))
                        val rm = context.getSystemService(RoleManager::class.java)
                        roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                    },
                    modifier = Modifier.padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("Set as default Phone app") }
            }
        }
        // ZeroSync's own incoming-call card while the phone is unlocked needs this.
        if (isDefault && !overlayOn) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(ZsColors.Surface).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Allow \"Display over other apps\" to see ZeroSync's own call pop-up while using the phone.",
                    color = ZsColors.OnSurface,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                    modifier = Modifier.padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("Allow") }
            }
        }
        // Without notifications: no missed-call alerts and no incoming-call pop-up.
        if (isDefault && !notificationsOn) {
            Column(
                Modifier.padding(24.dp).clip(RoundedCornerShape(16.dp)).background(ZsColors.Surface).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Notifications are off, so missed calls and the incoming-call pop-up can't show.",
                    color = ZsColors.OnSurface,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                    modifier = Modifier.padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("Turn on notifications") }
            }
        }
        when (phoneTab) {
            PhoneTab.Keypad -> {
            Spacer(Modifier.weight(1f))
            // Number with a delete (backspace) icon on its right. Hold the icon = clear all.
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(44.dp))
                Text(
                    number.ifEmpty { " " },
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            enabled = number.isNotEmpty(),
                            onClick = { number = number.dropLast(1) },
                            onLongClick = { number = "" },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (number.isNotEmpty()) {
                        Icon(ZsIcons.Backspace, contentDescription = "Delete", tint = ZsColors.Secondary, modifier = Modifier.size(26.dp))
                    }
                }
            }
            Dialpad(onKey = { number += it }, onLongZero = { number += '+' })
            }
            PhoneTab.Recents -> RecentsList(
                onCall = { n ->
                    if (hasCallPermission(context)) placeCall(context, n)
                    else { number = n; phoneTab = PhoneTab.Keypad }
                },
                onSave = { c -> editing = c },
                modifier = Modifier.weight(1f),
            )
            PhoneTab.Contacts -> ContactsList(onOpen = { editing = it }, modifier = Modifier.weight(1f))
        }
        PhoneTabs(
            phoneTab,
            onSelect = { phoneTab = it },
            onCall = {
                if (number.isBlank()) return@PhoneTabs
                if (hasCallPermission(context)) placeCall(context, number)
                else callPermission.launch(Manifest.permission.CALL_PHONE)
            },
        )
    }
}

/** Green for the call button only (user-approved color exception, like stock dialers). */
private val CallGreen = Color(0xFF34A853)

/** Order = left to right on screen: Contacts · Keypad (middle) · Recents. */
enum class PhoneTab(val label: String) { Contacts("Contacts"), Keypad("Keypad"), Recents("Recents") }

/** Height of every bottom tab slot (= Call button size). */
private val TabSlotHeight = 64.dp

/** Bottom tabs: Contacts · Keypad/Call · Recents. Icons only, active = white, others gray. */
@Composable
private fun PhoneTabs(selected: PhoneTab, onSelect: (PhoneTab) -> Unit, onCall: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PhoneTab.entries.forEach { tab ->
            val active = tab == selected
            // On the Keypad tab the middle button becomes the green Call button (3 buttons, not 4).
            if (tab == PhoneTab.Keypad && active) {
                Box(Modifier.weight(1f).height(TabSlotHeight), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(TabSlotHeight).clip(CircleShape).background(CallGreen).clickable(onClick = onCall),
                        contentAlignment = Alignment.Center,
                    ) { Icon(ZsIcons.Phone, contentDescription = "Call", tint = Color.White, modifier = Modifier.size(30.dp)) }
                }
                return@forEach
            }
            val color = if (active) Color.White else ZsColors.Secondary
            // Same fixed height as the Call button, so icons never jump when switching tabs.
            Column(
                Modifier.weight(1f).height(TabSlotHeight).clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val icon = when (tab) {
                    PhoneTab.Keypad -> ZsIcons.Dialpad
                    PhoneTab.Recents -> ZsIcons.Clock
                    PhoneTab.Contacts -> ZsIcons.Contact
                }
                // Icon only; the label is read out by screen readers.
                Icon(icon, contentDescription = tab.label, tint = color, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/**
 * 3 x 4 number keys: big digit only, no borders.
 * Used for dialing and for the in-call keypad. Hold 0 = "+" when [onLongZero] is given.
 */
@Composable
fun Dialpad(onKey: (Char) -> Unit, onLongZero: (() -> Unit)? = null) {
    val rows = listOf("123", "456", "789", "*0#")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { c ->
                    Column(
                        Modifier
                            .size(width = 96.dp, height = 64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = { onKey(c) },
                                onLongClick = if (c == '0' && onLongZero != null) onLongZero else null,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(c.toString(), color = Color.White, fontSize = 28.sp)
                    }
                }
            }
        }
    }
}

private fun notificationsAllowed(context: Context) =
    context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()

private fun isDefaultDialer(context: Context): Boolean =
    context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)

/** Start a normal SIM call through Android (the call screen then opens by itself). */
private fun hasCallPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

private fun placeCall(context: Context, number: String) {
    if (!hasCallPermission(context)) return
    runCatching {
        context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), null)
    }
}
