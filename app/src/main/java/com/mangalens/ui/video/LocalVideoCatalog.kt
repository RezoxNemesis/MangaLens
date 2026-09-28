package com.mangalens.ui.video

import android.content.Context
import android.provider.MediaStore

data class LocalVideoItem(val id: Long, val uri: android.net.Uri, val name: String, val mimeType: String, val sizeBytes: Long, val durationMs: Long)

class LocalVideoCatalog(private val context: Context) {
    fun scan(): List<LocalVideoItem> {
        val p = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION)
        val out = mutableListOf<LocalVideoItem>()
        context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, p, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
            val id=c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name=c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val mime=c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val size=c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val duration=c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while(c.moveToNext()) {
                val v=c.getLong(id)
                out += LocalVideoItem(v, android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v), c.getString(name).orEmpty(), c.getString(mime).orEmpty(), c.getLong(size), c.getLong(duration))
            }
        }
        return out
    }
}