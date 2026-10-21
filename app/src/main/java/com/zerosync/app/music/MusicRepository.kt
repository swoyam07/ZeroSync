package com.zerosync.app.music

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    /** Phone folder the song is in (e.g. English, Nepali, Hindi). */
    val folderId: Long,
    val folderName: String,
)

/** A phone folder that contains songs. */
data class MusicFolder(val id: Long, val name: String, val songs: List<Song>)

/** Reads songs from Android's MediaStore index. Read-only; nothing leaves the phone. */
class MusicRepository(private val resolver: ContentResolver) {

    suspend fun loadSongs(): List<Song> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.BUCKET_ID,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
        )
        val songs = ArrayList<Song>()
        resolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", // skip ringtones, voice notes...
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val folderIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_ID)
            val folderNameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val artist = c.getString(artistCol)
                songs += Song(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(titleCol) ?: "Unknown",
                    artist = if (artist == null || artist == MediaStore.UNKNOWN_STRING) "Unknown artist" else artist,
                    folderId = c.getLong(folderIdCol),
                    folderName = c.getString(folderNameCol) ?: "Unknown",
                )
            }
        }
        songs
    }

    /** Group songs by phone folder, folders sorted by name. */
    fun folders(songs: List<Song>): List<MusicFolder> =
        songs.groupBy { it.folderId }
            .map { (id, list) -> MusicFolder(id, list.first().folderName, list) }
            .sortedBy { it.name.lowercase() }
}
