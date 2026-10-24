package com.zerosync.app.phone

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.edit
import com.zerosync.app.music.MusicRepository
import com.zerosync.app.ui.theme.ZsColors

/**
 * Ringtone per contact category (Friends / Family / Work). Unset = phone's default ringtone.
 * Unknown numbers always use the phone's default ringtone. Kept in private storage.
 */
object RingtoneSettings {
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("ringtones", Context.MODE_PRIVATE)

    /** (uri, name) for a category; uri null = phone default. */
    fun get(context: Context, label: NumberLabel): Pair<String?, String> {
        val p = prefs(context)
        return p.getString("${label.name}_uri", null) to (p.getString("${label.name}_name", null) ?: "Default ringtone")
    }

    fun set(context: Context, label: NumberLabel, uri: String?, name: String) {
        prefs(context).edit {
            if (uri == null) { remove("${label.name}_uri"); remove("${label.name}_name") }
            else { putString("${label.name}_uri", uri); putString("${label.name}_name", name) }
        }
        version++
    }

    /** Ringtone for this caller: their category's song if set, else the phone's default. */
    fun uriFor(context: Context, number: String): Uri? {
        val contact = ContactsStore.find(context, number)
        val label = contact?.numbers?.firstOrNull { localNumber(it.number) == localNumber(number) }?.label
        val custom = label?.let { get(context, it).first }
        return custom?.let(Uri::parse) ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
    }
}

/**
 * Plays the ringtone for incoming calls (ZeroSync rings itself so each category can have its
 * own song). Follows the phone: silent / vibrate mode or Do Not Disturb = no sound (the call
 * still vibrates). Volume = the phone's ring volume.
 */
object CallRinger {
    private var player: MediaPlayer? = null

    fun start(context: Context, number: String) {
        stop()
        val audio = context.getSystemService(AudioManager::class.java)
        val dnd = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || dnd) return
        val uri = runCatching { RingtoneSettings.uriFor(context, number) }.getOrNull() ?: return
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        // Song missing / unreadable: fall back to the phone's default ringtone.
        if (player == null) runCatching {
            val def = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) ?: return
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                setDataSource(context, def); isLooping = true; prepare(); start()
            }
        }
    }

    fun stop() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }
}

/** Dialog: Friends / Family / Work with their ringtone; tap one to change it. */
@Composable
fun RingtonesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf<NumberLabel?>(null) }
    val current = remember(RingtoneSettings.version) { NumberLabel.entries.associateWith { RingtoneSettings.get(context, it).second } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZsColors.Surface,
        title = { Text("Ringtones", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Unknown numbers use the phone's default ringtone.", color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall)
                NumberLabel.entries.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black)
                            .clickable { picking = l }.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(l.title, color = Color.White, modifier = Modifier.weight(1f))
                        Text(current[l] ?: "Default ringtone", color = ZsColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1.4f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = ZsColors.Secondary) } },
    )
    picking?.let { l ->
        SongPicker(
            title = "${l.title} ringtone",
            onPick = { uri, name -> RingtoneSettings.set(context, l, uri, name); picking = null },
            onDismiss = { picking = null },
        )
    }
}

/** "Default ringtone" or any song from your music folders. */
@Composable
private fun SongPicker(title: String, onPick: (String?, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val songs by produceState(emptyList<Pair<String, String>>()) {
        value = runCatching { MusicRepository(context.contentResolver).loadSongs().map { it.uri.toString() to it.title } }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZsColors.Surface,
        title = { Text(title, color = Color.White) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text("Default ringtone", color = Color.White, modifier = Modifier.fillMaxWidth().clickable { onPick(null, "Default ringtone") }.padding(vertical = 12.dp))
                }
                items(songs) { (uri, name) ->
                    Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(uri, name) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ZsColors.Secondary) } },
    )
}
