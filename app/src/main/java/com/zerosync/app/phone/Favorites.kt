package com.zerosync.app.phone

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The 4 favorite spots, matching the drag directions on the keypad call button. */
enum class FavSlot(val label: String, val arrow: String, val angle: Float) {
    Up("Up", "↑", 270f), Right("Right", "→", 0f), Down("Down", "↓", 90f), Left("Left", "←", 180f),
}

/** Favorite numbers (one per direction), kept in ZeroSync's private storage. */
object Favorites {
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun get(context: Context, slot: FavSlot): String? = prefs(context).getString(slot.name, null)

    fun set(context: Context, slot: FavSlot, number: String) {
        prefs(context).edit { putString(slot.name, localNumber(number).ifEmpty { number.trim() }) }
        version++
    }

    fun clear(context: Context, slot: FavSlot) {
        prefs(context).edit { remove(slot.name) }
        version++
    }
}

/** Favorites screen (★ in Contacts): 4 rows ↑ → ↓ ←. Tap a row to pick a contact number or type one. */
@Composable
fun FavoritesScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<FavSlot?>(null) }
    BackHandler(onBack = onDone)
    val names by produceState(emptyMap<FavSlot, Pair<String, String>?>(), Favorites.version, ContactsStore.version) {
        value = withContext(Dispatchers.IO) {
            FavSlot.entries.associateWith { s ->
                Favorites.get(context, s)?.let { n -> (ContactsStore.find(context, n)?.name ?: n) to n }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ZsIcons.Back, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(24.dp).clickable(onClick = onDone))
            Text("Favorites", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
        }
        Text(
            "On the keypad, drag the call button in a direction to call that favorite.",
            color = ZsColors.Secondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        FavSlot.entries.forEach { slot ->
            val fav = names[slot]
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(ZsColors.Surface)
                    .clickable { editing = slot }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
                    Text(slot.arrow, color = Color.White, fontSize = 20.sp)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(fav?.first ?: "Empty", color = if (fav != null) Color.White else ZsColors.Secondary)
                    if (fav != null && fav.first != fav.second) Text(fav.second, color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall)
                }
                fav?.let { CarrierLogo(it.second, Modifier.padding(end = 10.dp).size(20.dp)) }
                if (fav != null) {
                    Icon(ZsIcons.Close, contentDescription = "Clear", tint = ZsColors.Secondary,
                        modifier = Modifier.size(32.dp).clip(CircleShape).clickable { Favorites.clear(context, slot) }.padding(6.dp))
                }
            }
        }
    }

    editing?.let { slot -> FavPicker(slot, onDone = { editing = null }) }
}

/** Choose a saved contact's number, or type any number, for one direction. */
@Composable
private fun FavPicker(slot: FavSlot, onDone: () -> Unit) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    val options by produceState(emptyList<Pair<String, String>>(), ContactsStore.version) {
        value = withContext(Dispatchers.IO) {
            ContactsStore.all(context).flatMap { c -> c.numbers.filter { it.number.isNotBlank() }.map { c.name to it.number } }
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = ZsColors.Surface,
        title = { Text("Favorite ${slot.arrow} ${slot.label}", color = Color.White) },
        text = {
            Column {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type a number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White, unfocusedBorderColor = ZsColors.Secondary,
                        focusedLabelColor = Color.White, unfocusedLabelColor = ZsColors.Secondary, cursorColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (options.isNotEmpty()) {
                    Text("Or pick a contact", color = ZsColors.Secondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(options) { (name, number) ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .clickable { Favorites.set(context, slot, number); onDone() }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, color = Color.White)
                                    Text(number, color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall)
                                }
                                CarrierLogo(number, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { Favorites.set(context, slot, typed); onDone() },
                enabled = localNumber(typed).isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = ZsColors.Secondary) } },
    )
}
