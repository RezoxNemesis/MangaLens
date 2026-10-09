package com.mangalens.ui.library

import android.content.Context
import com.mangalens.core.reader.LibraryChapterMetadata
import com.mangalens.core.reader.ReadingStatus
import org.json.JSONObject
import java.io.IOException

object LibraryOptionsCodec {
    const val MAX_BYTES = 4_096
    fun encode(options: LibraryOptions): String {
        require(options.collection == null || options.collection.length <= LibraryChapterMetadata.MAX_COLLECTION_LENGTH)
        require(options.sourceKey == null || validSourceKey(options.sourceKey))
        return JSONObject().put("version", 1).put("status", options.status?.name).put("bookmarksOnly", options.bookmarksOnly)
            .put("source", options.sourceKey).put("collection", options.collection).put("offline", options.offline.name)
            .put("sort", options.sort.name).toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }
    fun decode(raw: String?): LibraryOptions = runCatching {
        require(raw != null && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        val status = optional(json, "status")?.let { ReadingStatus.valueOf(it) }
        val source = optional(json, "source")?.also { require(validSourceKey(it)) }
        val collection = optional(json, "collection")?.also { require(it.length <= LibraryChapterMetadata.MAX_COLLECTION_LENGTH) }
        LibraryOptions(status, json.getBoolean("bookmarksOnly"), source, collection,
            LibraryOfflineFilter.valueOf(json.getString("offline")), LibrarySort.valueOf(json.getString("sort")))
    }.getOrDefault(LibraryOptions())
    private fun optional(json: JSONObject, key: String): String? = if (json.isNull(key) || !json.has(key)) null else json.getString(key).takeIf { it.isNotBlank() }
    private fun validSourceKey(value: String) = value == "local" || value == "other" ||
        (value.startsWith("host:") && value.length in 6..260 && value.none { it.isWhitespace() || it == '/' || it == '?' || it == '#' })
}

internal interface LibrarySettingsStorage { fun read(): String?; fun write(value: String): Boolean }

/** Disk work is called on IO by the screen. Accepted choices alone are persisted. */
class LibrarySettingsStore internal constructor(private val storage: LibrarySettingsStorage) {
    constructor(context: Context) : this(object : LibrarySettingsStorage {
        private val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        override fun read(): String? = runCatching { prefs.getString(OPTIONS_KEY, null) }.getOrNull()
        override fun write(value: String) = prefs.edit().putString(OPTIONS_KEY, value).commit()
    })
    fun load(): LibraryOptions = synchronized(fence) { LibraryOptionsCodec.decode(storage.read()) }
    fun save(options: LibraryOptions) = synchronized(fence) {
        val encoded = LibraryOptionsCodec.encode(options)
        if (!storage.write(encoded)) throw IOException("Library filters could not be saved. Please retry.")
    }
    companion object {
        const val PREFERENCES_NAME = "mangalens_library"
        const val OPTIONS_KEY = "explorer_v1"
        private val fence = Any()
    }
}
