package com.zerosync.app.phone

import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.telecom.TelecomManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zerosync.app.ui.theme.ZsColors

/**
 * One SIM: slot shown to the user (1 or 2), network name (e.g. "Ncell", "Namaste" = NTC),
 * and Android's calling account for it ([handle]) used to force a call through this SIM.
 */
data class SimCard(val subId: Int, val slot: Int, val name: String, val handle: PhoneAccountHandle? = null) {
    val label: String get() = "SIM $slot · $name"
}

/**
 * Active SIMs, from Android's list of calling accounts (HyperOS blocks the usual SIM lookups
 * for apps, but this list works). Slot comes from SubscriptionManager when allowed, else order.
 */
fun activeSims(context: Context): List<SimCard> {
    val telecom = context.getSystemService(TelecomManager::class.java)
    val handles = runCatching { telecom.callCapablePhoneAccounts }.getOrNull().orEmpty()
    val subs = runCatching { context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList }.getOrNull().orEmpty()
    return handles.mapIndexedNotNull { i, h ->
        val account = runCatching { telecom.getPhoneAccount(h) }.getOrNull() ?: return@mapIndexedNotNull null
        val subId = h.id.toIntOrNull() ?: -1
        val slot = subs.firstOrNull { it.subscriptionId == subId }?.simSlotIndex?.plus(1) ?: (i + 1)
        SimCard(subId, slot, account.label?.toString()?.ifBlank { null } ?: "SIM", h)
    }.sortedBy { it.slot }
}

/**
 * SIM used for calls from the dial pad: the only active SIM, else the default calling SIM.
 * null = two SIMs and the phone asks every time.
 */
fun callingSim(context: Context): SimCard? {
    val sims = activeSims(context)
    if (sims.size == 1) return sims.first()
    val def = runCatching { context.getSystemService(TelecomManager::class.java).getDefaultOutgoingPhoneAccount("tel") }.getOrNull()
    return sims.firstOrNull { it.handle == def }
}

/** SIM a call is going through (from the call's account), or null if unknown. */
fun simForCall(context: Context, handle: PhoneAccountHandle?): SimCard? {
    handle ?: return null
    return activeSims(context).firstOrNull { it.handle == handle }
}

/** Small gray chip like "SIM 1 · Ncell". */
@Composable
fun SimChip(sim: SimCard, modifier: Modifier = Modifier) {
    Text(
        sim.label,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(ZsColors.Surface)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
