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
fun ContactsList(onOpen: (Contact) -> Unit, onSpam: () -> Unit, onCall: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val contacts by produceState(emptyList<Contact>(), ContactsStore.version) {
        value = withContext(Dispatchers.IO) { ContactsStore.all(context) }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Contacts", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            // Spam list, left of the + (create) button.
            Text(
                "Spam",
                color = Color.White,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, ZsColors.Secondary, RoundedCornerShape(50))
                    .clickable(onClick = onSpam)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
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
        LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
            items(contacts, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(c) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c, 44.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(c.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        c.numbers.firstOrNull()?.let {
                            Text("${it.label.name} • ${it.number}", color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    // Same call pill as Recents; calls the first number.
                    c.numbers.firstOrNull()?.let { n -> CallPill(n.number, { onCall(n.number) }, Modifier.padding(start = 8.dp)) }
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
    val canSave = name.isNotBlank() && numbers.any { it.number.isNotBlank() }
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
                RoundIcon(ZsIcons.Delete, "Delete", DeleteRed, Color.White, { confirmDelete = true }, Modifier.padding(end = 8.dp))
            }
            RoundIcon(ZsIcons.Check, "Save", if (canSave) SaveGreen else ZsColors.Surface, if (canSave) Color.White else ZsColors.Secondary, { if (canSave) save() })
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
                Text("Add a name and a number to save.", color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
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

/** Dropdown pill: Personal (default) / Home / Work. */
@Composable
private fun LabelPicker(selected: NumberLabel, onPick: (NumberLabel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 8.dp)) {
        Text(
            "${selected.name} ▾",
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
                DropdownMenuItem(text = { Text(l.name, color = Color.White) }, onClick = { onPick(l); open = false })
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
