package de.konan.hiresplayer.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val mimeType: String?,
) {
    val formatLabel: String
        get() = when (mimeType) {
            "audio/flac", "audio/x-flac" -> "FLAC"
            "audio/x-wav", "audio/wav" -> "WAV"
            "audio/mpeg" -> "MP3"
            "audio/mp4", "audio/x-m4a", "audio/aac" -> "M4A"
            "audio/ogg" -> "OGG"
            "audio/opus" -> "OPUS"
            else -> mimeType?.substringAfter('/')?.uppercase().orEmpty()
        }

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(ContentUris.withAppendedId(ALBUM_ART, albumId))
                .build()
        )
        .build()

    private companion object {
        val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}

object MusicLibrary {
    suspend fun load(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
        )
        val tracks = mutableListOf<Track>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE, ${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, ${MediaStore.Audio.Media.TRACK}",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                tracks += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(titleCol) ?: "Unbekannt",
                    artist = c.getString(artistCol)?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: "Unbekannter Interpret",
                    album = c.getString(albumCol) ?: "",
                    albumId = c.getLong(albumIdCol),
                    durationMs = c.getLong(durationCol),
                    mimeType = c.getString(mimeCol),
                )
            }
        }
        tracks
    }
}
