package com.zerosync.app.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

// Small in-memory cache: 1/16 of the app's heap, capped at 32 MB. Built-in, no image library.
private object ThumbCache : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtMost(32 * 1024),
) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

// At most 4 thumbnails decode at once, so fast scrolling can't flood the CPU.
private val ThumbDispatcher = Dispatchers.IO.limitedParallelism(4)

/**
 * Loads a system-generated thumbnail for a photo, video, or song (its embedded cover).
 * Decoding is cancelled when the item scrolls off screen.
 */
@Composable
fun Thumbnail(
    uri: Uri,
    modifier: Modifier = Modifier,
    sizePx: Int = 256,
    contentScale: ContentScale = ContentScale.Crop,
    /** Shown until (or if never) the thumbnail loads. Transparent lets content behind show through. */
    placeholder: Color = Color(0xFF0E0B12),
) {
    val resolver = LocalContext.current.contentResolver
    val key = "$uri@$sizePx"

    // State is tied to the uri: when the same slot shows a different item (e.g. the music
    // carousel after a song change), it starts fresh instead of keeping the old picture.
    val state = remember(key) { mutableStateOf(ThumbCache.get(key)) }
    LaunchedEffect(key) {
        if (state.value != null) return@LaunchedEffect
        state.value = withContext(ThumbDispatcher) {
            val signal = CancellationSignal()
            val handle = coroutineContext.job.invokeOnCompletion { signal.cancel() }
            try {
                runCatching { resolver.loadThumbnail(uri, Size(sizePx, sizePx), signal) }
                    .getOrNull()
                    ?.also { ThumbCache.put(key, it) }
            } finally {
                handle.dispose()
            }
        }
    }
    val bitmap = state.value

    Box(modifier.background(placeholder)) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
