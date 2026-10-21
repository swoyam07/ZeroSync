package com.zerosync.app.camera

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Camera modes. [aspect] = frame width / height of the real document.
 * Passport 35x45 mm, ID card 85.6x54 mm (bank-card size), PDF = A4 portrait 210x297 mm.
 */
enum class CameraMode(val label: String, val aspect: Float) {
    Passport("Passport", 35f / 45f),
    Id("ID", 85.6f / 54f),
    Pdf("PDF", 210f / 297f),
    Saved("Saved", 1f), // list of captures, not a camera
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CameraRepository(app)

    var mode by mutableStateOf(CameraMode.Passport)

    var saved by mutableStateOf(emptyList<SavedDoc>())
        private set

    var busy by mutableStateOf(false)
        private set

    /** PDF in progress: pages taken so far (temp files). */
    var pdfPages by mutableStateOf(emptyList<File>())
        private set

    /** Show "Add another page?" after each PDF photo. */
    var askAnotherPage by mutableStateOf(false)
        private set

    /** Short message after saving, e.g. "Saved (private)". Cleared by the screen. */
    var message by mutableStateOf<String?>(null)

    fun refresh() {
        viewModelScope.launch { saved = withContext(Dispatchers.IO) { repo.list() } }
    }

    /** A cropped photo arrived from the camera. */
    fun onCaptured(bitmap: Bitmap) {
        busy = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                when (mode) {
                    CameraMode.Pdf -> pdfPages = pdfPages + repo.addPage(scaleDown(bitmap, 1240))
                    else -> repo.savePhoto(bitmap, mode.label.uppercase())
                }
            }
            bitmap.recycle()
            busy = false
            if (mode == CameraMode.Pdf) askAnotherPage = true else {
                message = "Saved (private)"
                refresh()
            }
        }
    }

    /** "Yes": close the question and take the next page. */
    fun addAnotherPage() {
        askAnotherPage = false
    }

    /** "No" / "Submit": turn all pages into one PDF. */
    fun finishPdf() {
        askAnotherPage = false
        val pages = pdfPages
        if (pages.isEmpty()) return
        busy = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.buildPdf(pages) }
            pdfPages = emptyList()
            busy = false
            message = "PDF saved (${pages.size} page${if (pages.size > 1) "s" else ""}, private)"
            refresh()
        }
    }

    /** Throw away the unfinished PDF. */
    fun cancelPdf() {
        askAnotherPage = false
        pdfPages = emptyList()
        viewModelScope.launch(Dispatchers.IO) { repo.clearPages() }
    }

    fun setPublic(doc: SavedDoc, public: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { if (public) repo.makePublic(doc) else repo.makePrivate(doc) }
            refresh()
        }
    }

    fun delete(doc: SavedDoc) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(doc) }
            refresh()
        }
    }

    /** Shrink so the longest side is at most [max] px (PDF pages don't need full camera size). */
    private fun scaleDown(bitmap: Bitmap, max: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= max) return bitmap
        val f = max.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt(), (bitmap.height * f).toInt(), true)
    }
}
