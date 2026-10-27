package com.zerosync.app.phone

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DeleteRed = Color(0xFFD32F2F)

/** Contacts tab: private list, "+" to add. Tap = edit. */
@Composable
fun ContactsList(
    onOpen: (Contact) -> Unit,
    onSpam: () -> Unit,
    onFavorites: () -> Unit = {},
    onCall: (String) -> Unit,
    modifier: Modifier = Modifier,
    sims: List<SimCard> = emptyList(),
    onCallWith: (String, SimCard) -> Unit = { n, _ -> onCall(n) },
    unreadMessages: Int = 0,
    onMessages: () -> Unit = {},
) {
    val context = LocalContext.current
    val contacts by produceState(emptyList<Contact>(), ContactsStore.version) {
        value = withContext(Dispatchers.IO) { ContactsStore.all(context) }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Contacts", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            // One row: ♪ Ringtones · Messages (with unread count) · Spam · + (create contact).
            Box(
                Modifier.padding(end = 10.dp).size(44.dp).clip(CircleShape).background(ZsColors.Surface).clickable(onClick = onFavorites), // opens ringtones (param name kept)
                contentAlignment = Alignment.Center,
            ) { Icon(ZsIcons.Music, contentDescription = "Ringtones", tint = Color.White, modifier = Modifier.size(22.dp)) } // same icon style as Messages
            Box(Modifier.padding(end = 10.dp), contentAlignment = Alignment.TopEnd) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(ZsColors.Surface).clickable(onClick = onMessages),
                    contentAlignment = Alignment.Center,
                ) { Icon(ZsIcons.Message, contentDescription = "Messages", tint = Color.White, modifier = Modifier.size(22.dp)) }
                if (unreadMessages > 0) com.zerosync.app.sms.UnreadBadge(unreadMessages)
            }
            // Spam list: red ⓘ-style badge (red circle, white "!").
            Box(Modifier.padding(end = 10.dp).size(44.dp).clip(CircleShape).background(ZsColors.Surface).clickable(onClick = onSpam), contentAlignment = Alignment.Center) {
                SpamBadge(24.dp)
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable { onOpen(Contact()) },
                contentAlignment = Alignment.Center,
            ) { Text("+", color = Color.Black, fontSize = 26.sp) }
        }
        if (contacts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No contacts yet. Only ZeroSync can see them.", color = ZsColors.Secondary)
            }
            return
        }
        // Split by first letter (A–Z, anything else = "#"). The list is already sorted by name.
        val groups = remember(contacts) { contacts.groupBy { letterOf(it.name) }.toSortedMap(compareBy { if (it == '#') '[' else it }) }
        // Where each letter's header sits in the list (header + its contacts per group).
        val headerIndex = remember(groups) {
            var i = 0
            groups.mapValues { (_, list) -> i.also { i += 1 + list.size } }
        }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var dragLetter by remember { mutableStateOf<Char?>(null) } // letter under the finger on the A–Z bar
        // How far the list area's middle sits below the screen's middle (px), so the A–Z bar
        // can be centred on the whole screen, not just on the list.
        val windowHeight = LocalWindowInfo.current.containerSize.height
        var belowMiddle by remember { mutableStateOf(0f) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { box ->
            belowMiddle = box.positionInWindow().y + box.size.height / 2f - windowHeight / 2f
        }) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
          groups.forEach { (letter, group) ->
            item(key = "header-$letter") {
                Text(
                    letter.toString(),
                    color = ZsColors.Secondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(group, key = { it.id }) { c ->
                Row(
                    // Extra end space so the A–Z bar never covers the call buttons.
                    Modifier.fillMaxWidth().clickable { onOpen(c) }.padding(start = 20.dp, end = 32.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c, 44.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(c.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        c.numbers.firstOrNull()?.let {
                            Text(it.label.title /* category only, no number */, color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    // Same call pill as Recents; calls the first number.
                    val saved = c.numbers.filter { it.number.isNotBlank() }
                    if (saved.isNotEmpty()) {
                        // One capsule, one segment per number (1 to 3) in that number's
                        // network color (NTC blue, Ncell purple, black otherwise). Each calls that number from
                        // YOUR SIM on the same network (NTC→NTC, Ncell→Ncell), else your default SIM.
                        val context = LocalContext.current
                        NumberCallCapsule(saved.map { it.number }, Modifier.padding(start = 8.dp)) { n ->
                            val sim = bestSimFor(context, n, sims)
                            if (sim != null) onCallWith(n, sim) else onCall(n)
                        }
                    }
                }
            }
          }
        }
        AlphabetBar(
            present = groups.keys,
            active = dragLetter,
            onActive = { dragLetter = it },
            onPick = { letter ->
                // Jump to that letter, or the next letter that has contacts.
                val target = headerIndex.entries.firstOrNull { it.key >= letter }?.value ?: headerIndex.values.last()
                scope.launch { listState.scrollToItem(target) }
            },
            liftPx = belowMiddle,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        }
    }
}

private val Alphabet = ('A'..'Z').toList() + '#'

/** First letter for grouping: A–Z, anything else = '#'. */
private fun letterOf(name: String): Char = name.trim().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'

/**
 * Right-edge A–Z bar. Letters that have contacts are bright, the rest very dim.
 * Tap or drag up/down along it to jump; each new letter gives a small vibration.
 * While dragging: a dark pill behind the bar, the letter under the finger becomes a white dot,
 * and a round bubble with that letter (big) floats beside it.
 */
@Composable
private fun AlphabetBar(
    present: Set<Char>,
    active: Char?,
    onActive: (Char?) -> Unit,
    onPick: (Char) -> Unit,
    modifier: Modifier = Modifier,
    liftPx: Float = 0f, // move the bar's middle up by this much (to match the screen's middle)
) {
    val haptics = LocalHapticFeedback.current
    // Extra bottom room = twice the lift, which moves the bar's centre up by exactly the lift.
    val extraBottom = with(LocalDensity.current) { (liftPx * 2).coerceAtLeast(0f).toDp() }
    val currentOnPick by rememberUpdatedState(onPick)
    val currentOnActive by rememberUpdatedState(onActive)
    var last by remember { mutableStateOf<Char?>(null) } // letter under the finger
    val bubble = 64.dp
    // Fixed roomy spacing per letter (shrinks only on short screens); the whole bar is
    // centred top-to-bottom on the right edge.
    // Same room top and bottom (plus the lift): the bar is centred on the screen and "#" is never cut off.
    BoxWithConstraints(modifier.fillMaxHeight().padding(top = 32.dp, bottom = 32.dp + extraBottom, end = 4.dp)) {
        val slot = minOf(22.dp, maxHeight / Alphabet.size) // height per letter
        val barHeight = slot * Alphabet.size
        Row(Modifier.height(barHeight).align(Alignment.CenterEnd), verticalAlignment = Alignment.Top) {
            // Bubble column: the big letter, level with the finger.
            Box(Modifier.width(bubble + 12.dp).fillMaxHeight()) {
                val i = active?.let { Alphabet.indexOf(it) } ?: -1
                if (i >= 0) {
                    val top = (slot * i + slot / 2 - bubble / 2).coerceIn(0.dp, barHeight - bubble)
                    Box(
                        Modifier.padding(top = top).size(bubble).clip(CircleShape).background(ZsColors.Surface)
                            .border(1.dp, ZsColors.PrimaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(active.toString(), color = if (active in present) Color.White else ZsColors.Secondary, fontSize = 30.sp)
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active != null) ZsColors.Surface else Color.Transparent)
                    .pointerInput(Unit) {
                        fun pick(y: Float) {
                            val i = (y / size.height * Alphabet.size).toInt().coerceIn(0, Alphabet.lastIndex)
                            val letter = Alphabet[i]
                            if (letter != last) {
                                last = letter
                                currentOnActive(letter)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnPick(letter)
                            }
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            pick(down.position.y)
                            while (true) {
                                val change = awaitPointerEvent().changes.first()
                                if (!change.pressed) break
                                change.consume()
                                pick(change.position.y)
                            }
                            last = null
                            currentOnActive(null)
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Alphabet.forEach { letter ->
                    Box(Modifier.height(slot).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (letter == active) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                                // Trim the font's built-in top/bottom space so the letter sits dead centre.
                                Text(
                                    letter.toString(),
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 10.sp,
                                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                                    ),
                                )
                            }
                        } else {
                            Text(
                                letter.toString(),
                                color = if (letter in present) ZsColors.OnSurface else ZsColors.Secondary.copy(alpha = 0.35f),
                                fontSize = 10.sp,
                                fontWeight = if (letter in present) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Round photo, or the first letter of the name when there's no photo. */
@Composable
fun Avatar(contact: Contact, size: Dp) {
    val context = LocalContext.current
    val photo by produceState<Bitmap?>(null, contact.id, contact.hasPhoto, ContactsStore.version) {
        value = if (contact.hasPhoto) withContext(Dispatchers.IO) { ContactsStore.photo(context, contact.id) } else null
    }
    Box(Modifier.size(size).clip(CircleShape).background(ZsColors.Surface), contentAlignment = Alignment.Center) {
        val bmp = photo
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(contact.name.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.4f).sp)
        }
    }
}

private val SaveGreen = Color(0xFF34A853)

/**
 * Create / edit a contact, styled like the music player: big round photo with a ring,
 * centered name, rounded dark cards for numbers and description, round icon buttons.
 * Top bar: back · title · (red trash) · green ✓ save. Save needs a name and one number.
 */
@Composable
fun ContactEditor(start: Contact, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(start.name) }
    var numbers by remember { mutableStateOf(start.numbers.ifEmpty { listOf(ContactNumber("")) }) }
    var description by remember { mutableStateOf(start.description) }
    var newPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onDone)

    // System photo picker: no storage permission, you choose one picture.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            newPhoto = withContext(Dispatchers.IO) { runCatching { loadSmall(context, uri) }.getOrNull() }
            removePhoto = false
        }
    }
    // Every number must be unique: not twice in this contact, not in another contact.
    // Compared without +977 / spaces, so "+9779841000000" and "9841000000" count as the same.
    val others by androidx.compose.runtime.produceState(emptyList<Contact>(), ContactsStore.version) {
        value = withContext(Dispatchers.IO) { ContactsStore.all(context).filter { it.id != start.id } }
    }
    val problems = numbers.mapIndexed { i, n ->
        val key = localNumber(n.number)
        when {
            key.isEmpty() -> null
            numbers.take(i).any { localNumber(it.number) == key } -> "Already added above"
            else -> others.firstOrNull { o -> o.numbers.any { localNumber(it.number) == key } }?.let { "Already saved for ${it.name}" }
        }
    }
    val canSave = name.isNotBlank() && numbers.any { it.number.isNotBlank() } && problems.all { it == null }
    // Anything edited compared to when the screen opened?
    val changed = name != start.name || description != start.description || newPhoto != null ||
        removePhoto || numbers.filter { it.number.isNotBlank() } != start.numbers.filter { it.number.isNotBlank() }
    val save: () -> Unit = {
        scope.launch {
            withContext(Dispatchers.IO) {
                ContactsStore.save(context, start.copy(name = name, numbers = numbers, description = description), newPhoto, removePhoto)
            }
            onDone()
        }
    }
    // Photo ring takes the first number's network color (NTC blue / Ncell purple), else gray.
    val ring = numbers.firstNotNullOfOrNull { carrierOf(it.number) }?.let { Color(it.color) } ?: ZsColors.Secondary

    Column(Modifier.fillMaxSize()) {
        // ---- Top bar ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(ZsIcons.Back, "Back", Color.Transparent, Color.White, onDone)
            Text(
                if (start.id.isEmpty()) "New contact" else "Edit contact",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            if (start.id.isNotEmpty()) {
                // Delete: gray circle, white icon (no red).
                RoundIcon(ZsIcons.Delete, "Delete", ZsColors.Surface, Color.White, { confirmDelete = true }, Modifier.padding(end = 8.dp))
            }
            // Save: only when something changed (white circle, black ✓; no green).
            if (changed) RoundIcon(ZsIcons.Check, "Save", if (canSave) Color.White else ZsColors.Surface, if (canSave) Color.Black else ZsColors.Secondary, { if (canSave) save() })
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- Photo with ring (like the music cover) ----
            Box(Modifier.padding(top = 12.dp).size(156.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(156.dp)
                        .border(3.dp, ring, CircleShape)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(ZsColors.Surface)
                        .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) {
                    val shown = newPhoto
                    when {
                        shown != null -> Image(shown.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        start.hasPhoto && !removePhoto -> Avatar(start, 140.dp)
                        name.isNotBlank() -> Text(name.take(1).uppercase(), color = Color.White, fontSize = 52.sp)
                        else -> Icon(ZsIcons.Contact, contentDescription = "Add photo", tint = ZsColors.Secondary, modifier = Modifier.size(56.dp))
                    }
                }
                // Small badge: add photo / remove photo.
                val hasPhoto = newPhoto != null || (start.hasPhoto && !removePhoto)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, Color.Black, CircleShape)
                        .clickable {
                            if (hasPhoto) { newPhoto = null; removePhoto = true }
                            else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (hasPhoto) ZsIcons.Close else ZsIcons.Add, contentDescription = if (hasPhoto) "Remove photo" else "Add photo", tint = Color.Black, modifier = Modifier.size(20.dp))
                }
            }

            // ---- Name: big and centered ----
            PlainField(
                value = name,
                onChange = { name = it },
                placeholder = "Name",
                textStyle = MaterialTheme.typography.headlineSmall.copy(color = Color.White, textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = 16.dp),
            )

            // ---- Numbers card ----
            SectionCard("Numbers") {
                numbers.forEachIndexed { i, n ->
                    problems.getOrNull(i)?.let { msg ->
                        Text(msg, color = DeleteRed, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 28.dp, top = 4.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            CarrierLogo(n.number, Modifier.size(22.dp))
                        }
                        PlainField(
                            value = n.number,
                            onChange = { v -> numbers = numbers.toMutableList().also { it[i] = n.copy(number = v) } },
                            placeholder = "Phone number",
                            keyboard = KeyboardType.Phone,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                            modifier = Modifier.weight(1f),
                        )
                        LabelPicker(n.label) { l -> numbers = numbers.toMutableList().also { it[i] = n.copy(label = l) } }
                        if (numbers.size > 1) {
                            Icon(
                                ZsIcons.Close, contentDescription = "Remove number", tint = ZsColors.Secondary,
                                modifier = Modifier.padding(start = 6.dp).size(28.dp).clip(CircleShape)
                                    .clickable { numbers = numbers.filterIndexed { j, _ -> j != i } }.padding(5.dp),
                            )
                        }
                    }
                }
                Row(
                    Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).border(1.dp, ZsColors.Secondary, RoundedCornerShape(50))
                        .clickable { numbers = numbers + ContactNumber("") }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ZsIcons.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("Add number", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                }
            }

            // ---- Description card ----
            SectionCard("About") {
                PlainField(
                    value = description,
                    onChange = { description = it },
                    placeholder = "Notes about this person",
                    singleLine = false,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                )
            }

            if (!canSave) {
                Text(if (problems.any { it != null }) "Fix the repeated number to save." else "Add a name and a number to save.", color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
            }
            Box(Modifier.size(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = ZsColors.Surface,
            title = { Text("Delete ${start.name}?", color = Color.White) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { ContactsStore.delete(context, start.id) }
                            confirmDelete = false
                            onDone()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeleteRed, contentColor = Color.White),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = ZsColors.Secondary) } },
        )
    }
}

/** Round icon button (44 dp). */
@Composable
private fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(44.dp).clip(CircleShape).background(background).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Rounded dark card with a small gray title, like the music screen's panels. */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(top = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ZsColors.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(title, color = ZsColors.Secondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

/** Borderless text field (no box), gray placeholder, white cursor. */
@Composable
private fun PlainField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = textStyle,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        decorationBox = { inner ->
            Box(contentAlignment = if (textStyle.textAlign == TextAlign.Center) Alignment.Center else Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = ZsColors.Secondary))
                inner()
            }
        },
    )
}

/** Dropdown pill: Friends (default) / Family / Work. */
@Composable
private fun LabelPicker(selected: NumberLabel, onPick: (NumberLabel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 8.dp)) {
        Text(
            "${selected.title} ▾",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = ZsColors.Surface) {
            NumberLabel.entries.forEach { l ->
                DropdownMenuItem(text = { Text(l.title, color = Color.White) }, onClick = { onPick(l); open = false })
            }
        }
    }
}

/** Picture from the picker, shrunk to max 512 px (enough for a contact photo, small to store). */
private fun loadSmall(context: android.content.Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 512) {
            val f = 512f / longest
            decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}
