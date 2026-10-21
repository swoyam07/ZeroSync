package com.zerosync.app.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val bucketId: Long,
    val bucketName: String,
    val dateAdded: Long,
)

/** A phone folder (MediaStore "bucket") that contains at least one video. */
data class VideoFolder(
    val bucketId: Long,
    val name: String,
    val videos: List<MediaItem>,
)

/**
 * Reads videos from Android's MediaStore index. No paths needed: MediaStore
 * already knows every media folder on the phone (Camera, Download, WhatsApp...).
 * Read-only; nothing is copied or sent anywhere.
 */
class MediaRepository(private val resolver: ContentResolver) {

    suspend fun loadVideos(): List<MediaItem> = withContext(Dispatchers.IO) {
        query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
            .sortedByDescending { it.dateAdded }
    }

    fun videoFolders(videos: List<MediaItem>): List<VideoFolder> =
        videos.groupBy { it.bucketId }
            .map { (id, items) -> VideoFolder(id, items.first().bucketName, items) }
            .sortedBy { it.name.lowercase() }

    private fun query(collection: Uri): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val result = ArrayList<MediaItem>()
        resolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    bucketId = c.getLong(bucketIdCol),
                    bucketName = c.getString(bucketNameCol) ?: "Unknown",
                    dateAdded = c.getLong(dateCol),
                )
            }
        }
        return result
    }
}
