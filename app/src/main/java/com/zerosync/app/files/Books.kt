package com.zerosync.app.files

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Books: PDFs you chose to read as books, each with the page you saved (double tap).
 * Only this list + page numbers are stored (private); the PDF files are never changed.
 * Opening a PDF from the PDF tab always starts at page 1; from Books, at the saved page.
 */
object Books {
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("books", Context.MODE_PRIVATE)

    private fun read(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString("list", "[]")) }.getOrDefault(JSONArray())

    private fun write(context: Context, arr: JSONArray) {
        prefs(context).edit { putString("list", arr.toString()) }
        version++
    }

    fun all(context: Context): List<DocFile> {
        val arr = read(context)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            DocFile(Uri.parse(o.getString("uri")), o.getString("name"), o.optLong("size"), o.optLong("mod"), DocKind.Books)
        }
    }

    fun contains(context: Context, uri: Uri) = all(context).any { it.uri == uri }

    fun add(context: Context, doc: DocFile) {
        if (contains(context, doc.uri)) return
        val arr = read(context)
        arr.put(JSONObject().put("uri", doc.uri.toString()).put("name", doc.name).put("size", doc.size).put("mod", doc.modified).put("page", -1))
        write(context, arr)
    }

    fun remove(context: Context, uri: Uri) {
        val arr = read(context)
        val out = JSONArray()
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { if (it.getString("uri") != uri.toString()) out.put(it) }
        write(context, out)
    }

    /** Saved page (0-based) or -1 when none. */
    fun page(context: Context, uri: Uri): Int {
        val arr = read(context)
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { if (it.getString("uri") == uri.toString()) return it.optInt("page", -1) }
        return -1
    }

    fun savePage(context: Context, uri: Uri, page: Int) {
        val arr = read(context)
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { if (it.getString("uri") == uri.toString()) it.put("page", page) }
        write(context, arr)
    }
}
