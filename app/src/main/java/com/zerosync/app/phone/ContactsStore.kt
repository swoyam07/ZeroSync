package com.zerosync.app.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** [title] = what the user sees. Stored names stay "Personal"/"Home" so saved contacts still load. */
enum class NumberLabel(val title: String) { Personal("Friends"), Home("Family"), Work("Work") }

data class ContactNumber(val number: String, val label: NumberLabel = NumberLabel.Personal)

/** A private contact. [id] is empty for a new, unsaved one. */
data class Contact(
    val id: String = "",
    val name: String = "",
    val numbers: List<ContactNumber> = listOf(ContactNumber("")),
    val description: String = "",
    val hasPhoto: Boolean = false,
)

/**
 * AES-256-GCM with a key kept in the phone's secure hardware (Android Keystore).
 * The key never leaves the chip and only ZeroSync can use it, so copied files are just noise.
 */
private object Vault {
    private const val ALIAS = "zerosync_contacts"
    private const val IV_SIZE = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain) // random IV stored in front
    }

    fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
        return c.doFinal(data, IV_SIZE, data.size - IV_SIZE)
    }
}

/**
 * ZeroSync's own contacts. Private app storage (other apps can't read it), never in the
 * phone's shared contacts, no backup, and encrypted on top. Photos are encrypted too.
 * Call from a background thread (decrypting/writing files).
 */
object ContactsStore {
    /** Bumped on every change so screens reload. */
    var version by mutableIntStateOf(0)
        private set

    @Volatile private var cache: List<Contact>? = null

    private fun dir(context: Context) = File(context.filesDir, "contacts").apply { mkdirs() }
    private fun dataFile(context: Context) = File(dir(context), "contacts.bin")
    private fun photoFile(context: Context, id: String) = File(dir(context), "$id.photo")

    fun all(context: Context): List<Contact> {
        cache?.let { return it }
        val file = dataFile(context)
        val list = if (!file.exists()) emptyList() else runCatching {
            fromJson(String(Vault.decrypt(file.readBytes())))
        }.getOrDefault(emptyList())
        cache = list
        return list
    }

    /** Saved contact that has [number] (any form: +977…, spaces, dashes), or null. */
    fun find(context: Context, number: String): Contact? {
        val key = localNumber(number)
        if (key.isEmpty()) return null
        return all(context).firstOrNull { c -> c.numbers.any { localNumber(it.number) == key } }
    }

    /** Save [contact] (new or edited). [photo]: new picture; [removePhoto]: delete the old one. */
    fun save(context: Context, contact: Contact, photo: Bitmap?, removePhoto: Boolean): Contact {
        val id = contact.id.ifEmpty { UUID.randomUUID().toString() }
        val pf = photoFile(context, id)
        val hasPhoto = when {
            photo != null -> {
                val out = ByteArrayOutputStream()
                photo.compress(Bitmap.CompressFormat.JPEG, 88, out)
                writeAtomic(pf, Vault.encrypt(out.toByteArray()))
                true
            }
            removePhoto -> { pf.delete(); false }
            else -> contact.hasPhoto
        }
        val saved = contact.copy(
            id = id,
            name = contact.name.trim(),
            numbers = contact.numbers.filter { it.number.isNotBlank() }.map { it.copy(number = it.number.trim()) },
            description = contact.description.trim(),
            hasPhoto = hasPhoto,
        )
        write(context, all(context).filter { it.id != id } + saved)
        return saved
    }

    fun delete(context: Context, id: String) {
        photoFile(context, id).delete()
        write(context, all(context).filter { it.id != id })
    }

    fun photo(context: Context, id: String): Bitmap? {
        val f = photoFile(context, id)
        if (!f.exists()) return null
        return runCatching { Vault.decrypt(f.readBytes()) }.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    private fun write(context: Context, list: List<Contact>) {
        val sorted = list.sortedBy { it.name.lowercase() }
        writeAtomic(dataFile(context), Vault.encrypt(toJson(sorted).toByteArray()))
        cache = sorted
        version++
    }

    /** Write to a temp file then swap, so a crash never leaves a half-written file. */
    private fun writeAtomic(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
    }

    private fun toJson(list: List<Contact>): String = JSONArray().apply {
        list.forEach { c ->
            put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("description", c.description)
                    .put("photo", c.hasPhoto)
                    .put("numbers", JSONArray().apply {
                        c.numbers.forEach { n -> put(JSONObject().put("n", n.number).put("l", n.label.name)) }
                    }),
            )
        }
    }.toString()

    private fun fromJson(text: String): List<Contact> {
        val arr = JSONArray(text)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val nums = o.getJSONArray("numbers")
            Contact(
                id = o.getString("id"),
                name = o.getString("name"),
                description = o.optString("description"),
                hasPhoto = o.optBoolean("photo"),
                numbers = List(nums.length()) { j ->
                    val n = nums.getJSONObject(j)
                    ContactNumber(n.getString("n"), runCatching { NumberLabel.valueOf(n.getString("l")) }.getOrDefault(NumberLabel.Personal))
                },
            )
        }
    }
}
