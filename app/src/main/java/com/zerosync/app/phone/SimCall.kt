package com.zerosync.app.phone

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors

private val SameNetworkGreen = Color(0xFF34A853)

/** Network of one of YOUR SIMs, from its name ("Ncell", "NTC", "Nepal Telecom"...). */
val SimCard.carrier: Carrier?
    get() {
        val n = name.lowercase()
        return when {
            "ncell" in n -> Carrier.Ncell
            "ntc" in n || "nepal telecom" in n || "namaste" in n -> Carrier.Ntc
            else -> null
        }
    }

/**
 * Which of the person's numbers to dial from [sim]: one on the SAME network if they have it
 * (NTC → NTC, Ncell → Ncell is usually cheaper), else their first number.
 */
fun numberFor(sim: SimCard, numbers: List<String>): String? {
    val list = numbers.filter { it.isNotBlank() }
    return list.firstOrNull { sim.carrier != null && carrierOf(it) == sim.carrier } ?: list.firstOrNull()
}

/**
 * SIM for calling [number]: your SIM on the SAME network (NTC→NTC, Ncell→Ncell).
 * Number on another network (landline, other company) with TWO SIMs = null, so the
 * "Call with which SIM?" screen asks you. One SIM = that SIM.
 */
fun bestSimFor(context: Context, number: String, sims: List<SimCard>): SimCard? =
    sims.firstOrNull { sameNetwork(it, number) } ?: sims.singleOrNull()

/** True when calling from [sim] stays on the same network as [number] (the cheaper choice). */
fun sameNetwork(sim: SimCard, number: String): Boolean = sim.carrier != null && carrierOf(number) == sim.carrier

/** Place a normal SIM call; with [sim] it goes through that SIM, else the phone decides. */
fun placeCallWith(context: Context, number: String, sim: SimCard?) {
    val extras = Bundle()
    // Force this SIM, so Android doesn't ask "which SIM?".
    sim?.handle?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
    runCatching {
        context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), extras)
    }
}

/**
 * Two SIMs: one call button per SIM ("1" / "2" with that SIM's network logo).
 * Each button dials the person's number on the same network when there is one.
 * Same network as the number it will dial = green (recommended, usually cheaper);
 * the other SIM stays gray but still works.
 */
@Composable
fun SimCallButtons(numbers: List<String>, sims: List<SimCard>, onCall: (String, SimCard) -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(ZsColors.Surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sims.forEach { sim ->
            val target = numberFor(sim, numbers)
            val recommended = target != null && sameNetwork(sim, target)
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (recommended) SameNetworkGreen else Color.Black)
                    .border(1.dp, if (recommended) SameNetworkGreen else ZsColors.Secondary.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable(enabled = target != null) { target?.let { onCall(it, sim) } }
                    .padding(horizontal = 10.dp)
                    .size(width = size, height = size * 0.8f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(ZsIcons.Phone, contentDescription = "Call with ${sim.label}", tint = if (recommended) Color.White else SameNetworkGreen, modifier = Modifier.size(16.dp))
                Text("${sim.slot}", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** Tiny badge with a SIM's network logo, e.g. on the keypad call buttons. */
@Composable
fun SimLogo(sim: SimCard, modifier: Modifier = Modifier) {
    val c = sim.carrier ?: return
    Box(modifier.size(16.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        CarrierLogo(if (c == Carrier.Ntc) "984" else "980", Modifier.size(12.dp))
    }
}

/**
 * One capsule split per number (max 3), same height as the single call pill (40 dp): each
 * segment is the number's network color (NTC dark blue, Ncell purple, black for other numbers like landlines) with a white
 * phone icon. Tap = call that number (matching SIM); hold = choose SIM 1 / SIM 2.
 */
@Composable
fun NumberCallCapsule(
    numbers: List<String>,
    modifier: Modifier = Modifier,
    /** Hold a segment = choose SIM 1 / SIM 2 for that number. */
    onLongCall: ((String) -> Unit)? = null,
    onCall: (String) -> Unit,
) {
    val shape = RoundedCornerShape(50)
    // Always the same size (one or several numbers); segments share the width equally.
    Row(
        modifier.width(CapsuleWidth).height(40.dp).clip(shape).border(1.dp, Color.White.copy(alpha = 0.2f), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        numbers.take(3).forEachIndexed { i, n ->
            if (i > 0) Box(Modifier.width(1.5.dp).height(40.dp).background(Color.Black)) // divider
            val color = carrierOf(n)?.let { Color(it.color) } ?: Color.Black // other networks / landlines: black
            Box(
                Modifier.weight(1f).height(40.dp).background(color)
                    .combinedClickable(onClick = { onCall(n) }, onLongClick = onLongCall?.let { { it(n) } }),
                contentAlignment = Alignment.Center, // phone icon centered in its segment
            ) {
                Icon(ZsIcons.Phone, contentDescription = "Call $n", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Width of the contacts call capsule (same for 1, 2 or 3 numbers). */
private val CapsuleWidth = 96.dp

/**
 * Small pop-up for numbers that are neither NTC nor Ncell (with two SIMs): one capsule split
 * in the middle — [SIM icon 1 │ SIM icon 2]. Tap a half = call from that SIM.
 */
@Composable
fun SimChoiceDialog(number: String, sims: List<SimCard>, onPick: (SimCard) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZsColors.Surface,
        title = { Text("Call $number from", color = Color.White, style = MaterialTheme.typography.titleMedium) },
        text = {
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.height(56.dp).clip(shape).border(1.dp, Color.White.copy(alpha = 0.3f), shape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sims.take(2).forEachIndexed { i, sim ->
                    if (i > 0) Box(Modifier.width(1.5.dp).height(56.dp).background(ZsColors.Secondary)) // middle barrier
                    Row(
                        Modifier.weight(1f).height(56.dp).background(Color.Black).clickable { onPick(sim) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(ZsIcons.SimCard, contentDescription = "Call from ${sim.label}", tint = Color.White, modifier = Modifier.size(22.dp))
                        Text("${sim.slot}", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = ZsColors.Secondary) } },
    )
}
