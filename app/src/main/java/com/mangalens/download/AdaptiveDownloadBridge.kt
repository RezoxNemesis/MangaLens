package com.mangalens.download

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AdaptiveDownloadBridge(private val context: Context) {
    private val dao = DownloadDatabase.get(context).downloads()

    suspend fun enqueue(url: String, title: String, mime: String?): String = withContext(Dispatchers.IO) {
        val id = java.util.UUID.randomUUID().toString()
        dao.upsert(DownloadEntity(id, url, title, mime ?: "application/octet-stream", state = DownloadState.DOWNLOADING))
        MangaLensDownloadService.addAdaptive(context, id, Uri.parse(url), mime)
        id
    }

    suspend fun remove(id: String) {
        MangaLensDownloadService.remove(context, id)
        dao.delete(id)
    }
}
