package com.zerosync.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.edit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved capture: a JPEG (passport / ID) or a PDF. Lives in app-private storage. */
data class SavedDoc(val file: File, val isPdf: Boolean, val isPublic: Boolean) {
    /** Which mode took it, from the file name prefix: PASSPORT_, ID_ or PDF_. */
    val kind: String get() = file.name.substringBefore('_')

    /** Name shown to the user (and used for the public copy): without the PASSPORT_/ID_/PDF_ prefix. */
    val displayName: String get() = file.name.substringAfter('_')
}

/**
 * Camera files. Private by default: saved in the app's own folder, which other apps can't read.
 * "Make public" copies the file to shared storage (Pictures/ZeroSync or Documents/ZeroSync)
 * so WhatsApp etc. can pick it; "make private" deletes that shared copy again.
 * Runs on a background thread (callers use Dispatchers.IO).
 */
class CameraRepository(private val context: Context) {
    private val dir = File(context.filesDir, "camera").apply { mkdirs() }
    private val pagesDir = File(context.cacheDir, "pdf_pages").apply { mkdirs() }
    // file name -> shared-storage Uri of its public copy (only for files that are public now).
    private val publicCopies = context.getSharedPreferences("camera_public", Context.MODE_PRIVATE)

    fun list(): List<SavedDoc> =
        dir.listFiles().orEmpty()
            .filter { it.extension == "jpg" || it.extension == "pdf" }
            .sortedByDescending { it.lastModified() }
            .map { SavedDoc(it, it.extension == "pdf", publicCopies.contains(it.name)) }

    /** Keep only characters that are safe in file names. */
    private fun safeName(name: String) = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)

    /** base.ext, or base (2).ext, base (3).ext ... if that name is taken. */
    private fun uniqueFile(base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var n = 2
        while (f.exists()) f = File(dir, "$base (${n++}).$ext")
        return f
    }

    private fun newName(prefix: String, ext: String): String =
        "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$ext"

    /** Save a cropped photo as a private JPEG. */
    fun savePhoto(bitmap: Bitmap, prefix: String): File {
        val file = File(dir, newName(prefix, "jpg"))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return file
    }

    /** Keep one PDF page as a temp JPEG until the PDF is built (keeps RAM low). */
    fun addPage(bitmap: Bitmap): File {
        val file = File(pagesDir, "page_${System.nanoTime()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    fun clearPages() {
        pagesDir.listFiles()?.forEach { it.delete() }
    }

    /** Build one A4 PDF from the page files (one photo per page, filling it), then drop the temps. */
    /** [title]: the name the user chose (without .pdf); empty = date-time name. */
    fun buildPdf(pages: List<File>, title: String = ""): File {
        val pdf = PdfDocument()
        // A4 in PDF points (1/72 inch).
        val w = 595
        val h = 842
        pages.forEachIndexed { i, pageFile ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            BitmapFactory.decodeFile(pageFile.path)?.let { bmp ->
                page.canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), null)
                bmp.recycle()
            }
            pdf.finishPage(page)
        }
        val file = if (title.isBlank()) File(dir, newName("PDF", "pdf")) else uniqueFile("PDF_" + safeName(title), "pdf")
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        clearPages()
        return file
    }

    /** Copy to shared storage so other apps can see it. */
    fun makePublic(doc: SavedDoc) {
        if (doc.isPublic) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, doc.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (doc.isPdf) "application/pdf" else "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (doc.isPdf) Environment.DIRECTORY_DOCUMENTS else Environment.DIRECTORY_PICTURES) + "/ZeroSync",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (doc.isPdf) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return
        resolver.openOutputStream(uri)?.use { out -> doc.file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        publicCopies.edit { putString(doc.file.name, uri.toString()) }
    }

    /** Delete the shared copy: other apps can't see it anymore. The private file stays. */
    fun makePrivate(doc: SavedDoc) {
        val uri = publicCopies.getString(doc.file.name, null) ?: return
        // We created this entry ourselves, so no extra permission is needed to delete it.
        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
        publicCopies.edit { remove(doc.file.name) }
    }

    /** Delete for good: shared copy (if any) and the private file. */
    fun delete(doc: SavedDoc) {
        makePrivate(doc)
        doc.file.delete()
    }
}
