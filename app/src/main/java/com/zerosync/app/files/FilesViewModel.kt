package com.zerosync.app.files

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DocKind(val label: String, val exts: List<String>) {
    /** Every file on the phone, any type, A–Z. */
    All("All files", emptyList()),
    Pdf("PDF", listOf("pdf")),
    /** PDFs added as books (reading position remembered). */
    Books("Books", emptyList()),
    Word("Word", listOf("docx", "doc")),
    Ppt("PPT", listOf("pptx", "ppt")),
    Excel("Excel", listOf("xlsx", "xls")),
    Text("Text", listOf("txt", "csv")),
    Images("Images", listOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")),
    Videos("Videos", listOf("mp4", "mkv", "webm", "3gp", "mov", "avi")),
}

data class DocFile(val uri: Uri, val name: String, val size: Long, val modified: Long, val kind: DocKind) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    /** Old binary Office formats (.doc/.ppt/.xls) can't be read without a big library. */
    val canPreview: Boolean get() = kind != DocKind.All && ext !in setOf("doc", "ppt", "xls")
}

/** What the viewer shows for the opened file. */
sealed interface DocContent {
    data object Loading : DocContent
    data class Failed(val message: String) : DocContent
    /** [startPage] 0-based; [book] = opened from Books (double tap saves the page). */
    data class Pdf(val uri: Uri, val startPage: Int = 0, val book: Boolean = false) : DocContent
    data class Word(val blocks: List<DocBlock>) : DocContent
    data class Slides(val presentation: Presentation) : DocContent
    data class Excel(val sheets: List<Sheet>) : DocContent
    data class Text(val lines: List<String>) : DocContent
    data class Picture(val uri: Uri) : DocContent
    data class Video(val uri: Uri) : DocContent
}

/** Biggest text file we show (bigger ones are cut). */
private const val MAX_TEXT_CHARS = 2_000_000

class FilesViewModel(app: Application) : AndroidViewModel(app) {
    private val resolver = app.contentResolver

    var kind by mutableStateOf(DocKind.Pdf)
    var docs by mutableStateOf(emptyList<DocFile>())
        private set
    var loading by mutableStateOf(false)
        private set

    var opened by mutableStateOf<DocFile?>(null)
        private set
    var content by mutableStateOf<DocContent>(DocContent.Loading)
        private set
    private var openJob: Job? = null

    /** Find every document on the phone (needs "All files access"). */
    fun load() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            docs = withContext(Dispatchers.IO) { query() }
            allFiles = withContext(Dispatchers.IO) { runCatching { queryAll() }.getOrDefault(emptyList()) }
            loading = false
        }
    }

    /** "All files": every file on the phone (no folders), sorted A–Z by name. */
    var allFiles by mutableStateOf(emptyList<DocFile>())
        private set

    private fun queryAll(): List<DocFile> {
        val byExt = DocKind.entries.flatMap { k -> k.exts.map { it to k } }.toMap()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val out = ArrayList<DocFile>()
        resolver.query(
            collection, projection,
            "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL", // folders have no type
            null,
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 20000) {
                val name = c.getString(1) ?: continue
                // Known types open in their viewer; anything else is listed only.
                val kind = byExt[name.substringAfterLast('.', "").lowercase()] ?: DocKind.All
                val base = when (kind) {
                    DocKind.Images -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    DocKind.Videos -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> collection
                }
                out.add(DocFile(ContentUris.withAppendedId(base, c.getLong(0)), name, c.getLong(2), c.getLong(3) * 1000, kind))
            }
        }
        return out
    }

    private fun query(): List<DocFile> {
        val byExt = DocKind.entries.flatMap { k -> k.exts.map { it to k } }.toMap()
        val nameCol = MediaStore.Files.FileColumns.DISPLAY_NAME
        val selection = byExt.keys.joinToString(" OR ") { "$nameCol LIKE ?" }
        val args = byExt.keys.map { "%.$it" }.toTypedArray()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            nameCol,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val out = ArrayList<DocFile>()
        resolver.query(collection, projection, selection, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nCol = c.getColumnIndexOrThrow(nameCol)
            val sCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                val name = c.getString(nCol) ?: continue
                val kind = byExt[name.substringAfterLast('.', "").lowercase()] ?: continue
                // Images/videos use their own media address so thumbnails work.
                val base = when (kind) {
                    DocKind.Images -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    DocKind.Videos -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> collection
                }
                out.add(
                    DocFile(
                        ContentUris.withAppendedId(base, c.getLong(idCol)),
                        name,
                        c.getLong(sCol),
                        c.getLong(dCol) * 1000,
                        kind,
                    ),
                )
            }
        }
        return out
    }

    /** Open a file: read it in the background, then show it. */
    fun open(doc: DocFile) {
        opened = doc
        content = DocContent.Loading
        openJob?.cancel()
        openJob = viewModelScope.launch {
            content = withContext(Dispatchers.IO) {
                runCatching { read(doc) }.getOrElse { DocContent.Failed(it.message ?: "Can't open this file") }
            }
        }
    }

    private fun read(doc: DocFile): DocContent {
        if (doc.kind == DocKind.All) return DocContent.Failed("No preview for .${doc.ext} files. ZeroSync only lists them.")
        if (!doc.canPreview) {
            return DocContent.Failed("Old .${doc.ext} format can't be previewed. Save it as .${doc.ext}x to view it.")
        }
        if (doc.kind == DocKind.Pdf) return DocContent.Pdf(doc.uri) // PDF tab: always page 1
        if (doc.kind == DocKind.Books) return DocContent.Pdf(doc.uri, Books.page(getApplication(), doc.uri).coerceAtLeast(0), book = true)
        if (doc.kind == DocKind.Images) return DocContent.Picture(doc.uri)
        if (doc.kind == DocKind.Videos) return DocContent.Video(doc.uri)
        val input = resolver.openInputStream(doc.uri) ?: error("Can't open this file")
        return input.use {
            when (doc.kind) {
                DocKind.Word -> DocContent.Word(parseDocx(it))
                DocKind.Ppt -> DocContent.Slides(parsePptx(it))
                DocKind.Excel -> DocContent.Excel(parseXlsx(it))
                else -> DocContent.Text(readCapped(it).lines())
            }
        }
    }

    /** Read at most [MAX_TEXT_CHARS] characters, so a huge file can't fill the memory. */
    private fun readCapped(input: java.io.InputStream): String {
        val reader = input.bufferedReader()
        val buf = CharArray(MAX_TEXT_CHARS)
        var n = 0
        while (n < buf.size) {
            val read = reader.read(buf, n, buf.size - n)
            if (read < 0) break
            n += read
        }
        return String(buf, 0, n)
    }

    /** Delete a file from the phone (allowed by "All files access"). */
    fun delete(doc: DocFile) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { deleteFile(doc) }
            if (ok) {
                // Remove it from every list (same file can be in "All files" and its type tab).
                val id = doc.uri.lastPathSegment
                docs = docs.filter { it.uri.lastPathSegment != id }
                allFiles = allFiles.filter { it.uri.lastPathSegment != id }
            }
        }
    }

    /**
     * Delete through Android's media list; if that refuses (common for non-media files like
     * .zip/.apk), delete the real file by its path ("All files access" allows this) and then
     * remove its entry from the list.
     */
    private fun deleteFile(doc: DocFile): Boolean {
        if (runCatching { resolver.delete(doc.uri, null, null) > 0 }.getOrDefault(false)) return true
        val path = runCatching {
            resolver.query(doc.uri, arrayOf(MediaStore.Files.FileColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: return false
        val file = java.io.File(path)
        if (!file.exists() || !file.delete()) return false
        runCatching { resolver.delete(doc.uri, null, null) } // drop the stale entry
        return true
    }

    fun close() {
        openJob?.cancel()
        opened = null
        content = DocContent.Loading
    }
}
