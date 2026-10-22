package com.zerosync.app.phone

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZeroSyncTheme
import com.zerosync.app.ui.theme.ZsColors

private val AnswerGreen = Color(0xFF34A853)
private val DeclineRed = Color(0xFFD32F2F)

/**
 * ZeroSync's own small incoming-call card, drawn over other apps while the phone is unlocked
 * (HyperOS ignores most of an app's style in its own call pop-up). Needs "Display over other
 * apps"; without it the normal system pop-up is used. Shown only while a call rings.
 */
object CallOverlay {
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    fun show(context: Context, number: String, contact: Contact?) {
        if (view != null || !canShow(context)) return
        val wm = context.getSystemService(WindowManager::class.java)
        val o = OverlayOwner().also { owner = it }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                ZeroSyncTheme {
                    IncomingCard(
                        number = number,
                        contact = contact,
                        onOpen = { openFullScreen(context) },
                        onAnswer = { CallManager.answer(); openFullScreen(context) },
                        onDecline = { CallManager.decline() },
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Doesn't take the keyboard focus, so the app underneath keeps working.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = (36 * context.resources.displayMetrics.density).toInt() // just under the status bar
        }
        runCatching { wm.addView(v, params) }.onSuccess {
            view = v
            o.start()
        }.onFailure { owner = null }
    }

    fun hide(context: Context) {
        val v = view ?: return
        owner?.stop()
        runCatching { context.getSystemService(WindowManager::class.java).removeView(v) }
        view = null
        owner = null
    }

    private fun openFullScreen(context: Context) {
        hide(context)
        runCatching {
            context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Card: photo (network-colored ring) · name + number + logo · Decline · Answer. */
@Composable
private fun IncomingCard(number: String, contact: Contact?, onOpen: () -> Unit, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val ring = carrierOf(number)?.let { Color(it.color) } ?: ZsColors.Secondary
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF141414))
            .border(1.dp, ZsColors.Secondary.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).border(3.dp, ring, CircleShape).padding(4.dp), contentAlignment = Alignment.Center) {
            if (contact != null) {
                Avatar(contact, 44.dp)
            } else {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(ZsColors.Surface), contentAlignment = Alignment.Center) {
                    Icon(ZsIcons.Contact, contentDescription = null, tint = ZsColors.Secondary, modifier = Modifier.size(24.dp))
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(contact?.name ?: number, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarrierLogo(number, Modifier.padding(end = 6.dp).size(16.dp))
                Text(if (contact != null) number else "Incoming call", color = ZsColors.Secondary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        RoundAction(DeclineRed, "Decline", onDecline)
        RoundAction(AnswerGreen, "Answer", onAnswer, Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun RoundAction(color: Color, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(ZsIcons.Phone, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp)) }
}

/** Minimal lifecycle so Compose can run in a window that isn't an Activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this).apply { performRestore(null as Bundle?) }
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    fun start() { registry.currentState = Lifecycle.State.RESUMED }
    fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
}
