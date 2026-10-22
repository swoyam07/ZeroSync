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
fun ContactsList(onOpen: (Contact) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val contacts by produceState(emptyList<Contact>(), ContactsStore.version) {
        value = withContext(Dispatchers.IO) { ContactsStore.all(context) }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Contacts", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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
                    c.numbers.firstOrNull()?.let { CarrierLogo(it.number, Modifier.size(20.dp)) }
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

/**
 * Create / edit a contact: photo, name, one or more numbers (each Personal / Home / Work),
 * description. Save needs a name and at least one number. Back = cancel.
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ZsIcons.Back, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(24.dp).clickable(onClick = onDone))
            Text(if (start.id.isEmpty()) "New contact" else "Edit contact", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
        }

        // Photo: tap to choose, "Remove" under it when there is one.
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(110.dp).clip(CircleShape).background(ZsColors.Surface).border(1.dp, ZsColors.Secondary, CircleShape)
                    .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) {
                val shown = newPhoto
                when {
                    shown != null -> Image(shown.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    start.hasPhoto && !removePhoto -> Avatar(start, 110.dp)
                    else -> Icon(ZsIcons.Image, contentDescription = "Add photo", tint = ZsColors.Secondary, modifier = Modifier.size(36.dp))
                }
            }
            if (newPhoto != null || (start.hasPhoto && !removePhoto)) {
                Text("Remove photo", color = ZsColors.Secondary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp).clickable { newPhoto = null; removePhoto = true })
            }
        }

        Field("Name", name, { name = it })

        numbers.forEachIndexed { i, n ->
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(
                    "Number",
                    n.number,
                    { v -> numbers = numbers.toMutableList().also { it[i] = n.copy(number = v) } },
                    Modifier.weight(1f),
                    KeyboardType.Phone,
                )
                LabelPicker(n.label) { l -> numbers = numbers.toMutableList().also { it[i] = n.copy(label = l) } }
                if (numbers.size > 1) {
                    Text("✕", color = ZsColors.Secondary, fontSize = 18.sp,
                        modifier = Modifier.padding(start = 8.dp).clickable { numbers = numbers.filterIndexed { j, _ -> j != i } })
                }
            }
        }
        Text("+ Add number", color = Color.White, modifier = Modifier.padding(top = 10.dp).clickable { numbers = numbers + ContactNumber("") })

        Field("Description", description, { description = it }, Modifier.padding(top = 16.dp), singleLine = false)

        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (start.id.isNotEmpty()) {
                Button(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DeleteRed, contentColor = Color.White),
                ) { Icon(ZsIcons.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp)) }
            }
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ContactsStore.save(context, start.copy(name = name, numbers = numbers, description = description), newPhoto, removePhoto)
                        }
                        onDone()
                    }
                },
                enabled = canSave,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Text("Save") }
        }
        if (!canSave) {
            Text("Name and at least one number are needed.", color = ZsColors.Secondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
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

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = ZsColors.Secondary,
            focusedLabelColor = Color.White,
            unfocusedLabelColor = ZsColors.Secondary,
            cursorColor = Color.White,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Dropdown: Personal (default) / Home / Work. */
@Composable
private fun LabelPicker(selected: NumberLabel, onPick: (NumberLabel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 8.dp)) {
        Text(
            "${selected.name} ▾",
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, ZsColors.Secondary, RoundedCornerShape(12.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 14.dp),
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
