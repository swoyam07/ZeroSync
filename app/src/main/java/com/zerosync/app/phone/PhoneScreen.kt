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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.zIndex
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
    // Your active SIMs (two = per-SIM call buttons on Keypad and Contacts).
    var simsTick by remember { mutableStateOf(0) }
    val sims by androidx.compose.runtime.produceState(emptyList<SimCard>(), simsTick) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { activeSims(context) }
    }
    // Contact being created/edited (null = not editing). Fills the whole Phone screen.
    var editing by remember { mutableStateOf<Contact?>(null) }
    var spamOpen by remember { mutableStateOf(false) }
    var favOpen by remember { mutableStateOf(false) }
    // Network whose quick codes are shown (null = closed).
    var codesFor by remember { mutableStateOf<Carrier?>(null) }
    // Number waiting for "SIM 1 or SIM 2?" (null = no pop-up).
    var askSimFor by remember { mutableStateOf<String?>(null) }
    // Call through [sim]; no SIM chosen + two SIMs (e.g. a landline) = small pop-up asks first.
    val dial: (String, SimCard?) -> Unit = { n, sim ->
        if (sim == null && sims.size >= 2) askSimFor = n else placeCall(context, n, sim)
    }
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
    var isSmsApp by remember { mutableStateOf(isDefaultSms(context)) }
    val smsRoleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isSmsApp = isDefaultSms(context)
    }
    // Unread texts for the badge on the message icon (reloads when messages change / on resume).
    var resumeTick by remember { mutableStateOf(0) }
    val unread by androidx.compose.runtime.produceState(0, com.zerosync.app.sms.SmsStore.version, resumeTick, isSmsApp) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.zerosync.app.sms.SmsStore.unreadCount(context) }
    }
    LifecycleResumeEffect(Unit) {
        notificationsOn = notificationsAllowed(context)
        overlayOn = CallOverlay.canShow(context)
        isSmsApp = isDefaultSms(context)
        resumeTick++
        simsTick++
        isDefault = isDefaultDialer(context)
        onPauseOrDispose { }
    }

    // Quick code: dial it from THAT network's SIM (NTC code → NTC SIM, Ncell code → Ncell SIM).
    codesFor?.let { c ->
        ServiceCodesDialog(c, onDial = { code ->
            codesFor = null
            if (!hasCallPermission(context)) callPermission.launch(Manifest.permission.CALL_PHONE)
            else dial(code, sims.firstOrNull { it.carrier == c })
        }, onDismiss = { codesFor = null })
    }
    askSimFor?.let { n -> SimChoiceDialog(n, sims, onPick = { sim -> askSimFor = null; placeCall(context, n, sim) }, onDismiss = { askSimFor = null }) }
    if (favOpen) {
        Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().padding(bottom = bottomPadding)) {
            FavoritesScreen(onDone = { favOpen = false })
        }
        return
    }
    if (spamOpen) {
        Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().padding(bottom = bottomPadding)) {
            SpamListScreen(onDone = { spamOpen = false })
        }
        return
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
        // Top: Messages icon with the unread count (on Contacts it sits in that tab's header row).
        // Left: SIM used for calls ("SIM 1 · Ncell"); hidden when the phone has no default SIM.
        val simText by androidx.compose.runtime.produceState<String?>(null, resumeTick) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val sims = activeSims(context)
                when {
                    sims.isEmpty() -> null
                    // Two SIMs with no default: show nothing (the Call 1 / Call 2 capsule covers it).
                    else -> callingSim(context)?.label
                }
            }
        }
        if (phoneTab != PhoneTab.Contacts) Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            simText?.let { t ->
                Text(
                    t,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(ZsColors.Surface).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } ?: Box(Modifier)
            // NTC / Ncell quick codes (balance, packs, credit), Keypad tab only.
            if (phoneTab == PhoneTab.Keypad) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetworkButton(Carrier.Ntc, { codesFor = Carrier.Ntc })
                    NetworkButton(Carrier.Ncell, { codesFor = Carrier.Ncell })
                }
            }
            Box(contentAlignment = Alignment.TopEnd) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(ZsColors.Surface)
                        .clickable { context.startActivity(android.content.Intent(context, com.zerosync.app.sms.MessagesActivity::class.java)) },
                    contentAlignment = Alignment.Center,
                ) { Icon(ZsIcons.Message, contentDescription = "Messages", tint = Color.White, modifier = Modifier.size(24.dp)) }
                if (unread > 0) com.zerosync.app.sms.UnreadBadge(unread)
            }
        }
        // Messages need ZeroSync as the default SMS app.
        if (!isSmsApp) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(ZsColors.Surface).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "To send and receive texts in ZeroSync, set it as the default SMS app.",
                    color = ZsColors.OnSurface,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = {
                        val rm = context.getSystemService(RoleManager::class.java)
                        smsRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                    },
                    modifier = Modifier.padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("Set as default SMS app") }
            }
        }
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
            // Saved contacts matching the typed digits (3+), shown above the keys. Nothing
            // changes unless you TAP one: then its number replaces what you typed.
            DialSuggestions(number, onPick = { number = it })
            Dialpad(onKey = { number += it }, onLongZero = { number += '+' })
            }
            PhoneTab.Recents -> RecentsList(
                onCall = { n ->
                    if (hasCallPermission(context)) dial(n, bestSimFor(context, n, sims))
                    else { number = n; phoneTab = PhoneTab.Keypad }
                },
                onSave = { c -> editing = c },
                modifier = Modifier.weight(1f),
            )
            PhoneTab.Contacts -> ContactsList(
                onOpen = { editing = it },
                onSpam = { spamOpen = true },
                onFavorites = { favOpen = true },
                sims = sims,
                onCallWith = { n, sim ->
                    if (hasCallPermission(context)) dial(n, sim)
                    else { number = n; phoneTab = PhoneTab.Keypad }
                },
                unreadMessages = unread,
                onMessages = { context.startActivity(android.content.Intent(context, com.zerosync.app.sms.MessagesActivity::class.java)) },
                onCall = { n ->
                    if (hasCallPermission(context)) dial(n, null)
                    else { number = n; phoneTab = PhoneTab.Keypad }
                },
                modifier = Modifier.weight(1f),
            )
        }
        PhoneTabs(
            phoneTab,
            onSelect = { phoneTab = it },
            onCall = { sim ->
                if (number.isBlank()) return@PhoneTabs
                if (hasCallPermission(context)) dial(number, sim)
                else callPermission.launch(Manifest.permission.CALL_PHONE)
            },
            sims = sims,
            typed = number,
            // Favorite: same-network SIM (NTC→NTC, Ncell→Ncell) like every other call.
            onFavorite = { slot ->
                val n = Favorites.get(context, slot)
                if (n == null) { favOpen = true }
                else if (hasCallPermission(context)) dial(n, bestSimFor(context, n, sims))
                else callPermission.launch(Manifest.permission.CALL_PHONE)
            },
        )
    }
}

/** Green for the call button only (user-approved color exception, like stock dialers). */
private val CallGreen = Color(0xFF34A853)

/** Order = left to right on screen: Contacts · Keypad (middle) · Recents. */
enum class PhoneTab(val label: String) { Contacts("Contacts"), Keypad("Keypad"), Recents("Recents") }

/**
 * Up to 5 saved contacts whose number contains the typed digits (any form, +977 ignored),
 * as a sideways row of chips: name + matching number. Tap = put that number in the dialer.
 */
@Composable
private fun DialSuggestions(typed: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val digits = localNumber(typed)
    val contacts by androidx.compose.runtime.produceState(emptyList<Contact>(), ContactsStore.version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ContactsStore.all(context) }
    }
    val matches = remember(digits, contacts) {
        if (digits.length < 3) emptyList()
        else contacts.flatMap { c -> c.numbers.filter { localNumber(it.number).contains(digits) }.map { c to it.number } }
            .filter { localNumber(it.second) != digits } // already fully typed: nothing to suggest
            .take(5)
    }
    // Fixed height so the keypad doesn't jump when suggestions appear / disappear.
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().height(44.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(matches.size) { i ->
            val (c, n) = matches[i]
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(ZsColors.Surface).clickable { onPick(localNumber(n)) }
                    .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(c, 28.dp)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(c.name, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Text(localNumber(n), color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

/** Number key size + gap; the bottom tabs use the same columns so everything lines up. */
private val KeyWidth = 96.dp
private val KeyGap = 12.dp

/** Height of every bottom tab slot (= Call button size). */
private val TabSlotHeight = 64.dp

/** Bottom tabs: Contacts · Keypad/Call · Recents. Icons only, active = white, others gray. */
@Composable
private fun PhoneTabs(
    selected: PhoneTab,
    onSelect: (PhoneTab) -> Unit,
    onCall: (SimCard?) -> Unit,
    sims: List<SimCard> = emptyList(),
    typed: String = "",
    onFavorite: (FavSlot) -> Unit = {},
) {
    // Nudged a bit lower (offset), closer to the nav strip.
    // Same width and columns as the number keys, so Contacts sits under 1-4-7-*, Call under
    // 2-5-8-0 and Recents under 3-6-9-#.
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).offset(y = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(KeyGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneTab.entries.forEach { tab ->
            val active = tab == selected
            // On the Keypad tab the middle button becomes the green Call button (3 buttons, not 4).
            if (tab == PhoneTab.Keypad && active) {
                // zIndex: the favorites menu is drawn on top of the neighbor tab icons.
                Box(Modifier.width(KeyWidth).height(TabSlotHeight).zIndex(1f), contentAlignment = Alignment.Center) {
                    // One green call button. Once the typed number's network is known (first 3
                    // digits), it turns that network's color (NTC blue / Ncell purple) and calls
                    // from YOUR SIM on the same network (NTC→NTC, Ncell→Ncell), else your default.
                    val context = LocalContext.current
                    val net = carrierOf(typed)
                    // Tap = call typed number; drag ↑ → ↓ ← = call that favorite (no hold needed).
                    FavCallButton(
                        color = net?.let { Color(it.color) } ?: CallGreen,
                        size = TabSlotHeight,
                        onTap = { onCall(bestSimFor(context, typed, sims)) }, // unknown network + 2 SIMs = asks which SIM
                        onFavorite = onFavorite,
                    )
                }
                return@forEach
            }
            val color = if (active) Color.White else ZsColors.Secondary
            // Same fixed height as the Call button, so icons never jump when switching tabs.
            Column(
                Modifier.width(KeyWidth).height(TabSlotHeight).clip(RoundedCornerShape(16.dp)).clickable { onSelect(tab) },
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
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                row.forEach { c ->
                    Column(
                        Modifier
                            .size(width = KeyWidth, height = 64.dp)
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

private fun isDefaultSms(context: Context): Boolean =
    context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)

private fun isDefaultDialer(context: Context): Boolean =
    context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)

/** Start a normal SIM call through Android (the call screen then opens by itself). */
private fun hasCallPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

private fun placeCall(context: Context, number: String, sim: SimCard? = null) {
    if (!hasCallPermission(context)) return
    placeCallWith(context, number, sim)
}
