package com.mangalens.ui.video

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI

internal data class RecentVideoHistoryState(val entries: List<RecentVideoEntry> = emptyList(),
    val loading: Boolean = true, val error: String? = null)

internal interface RecentVideoJournalIo {
    fun read(file: File): ByteArray?
    fun write(file: File, bytes: ByteArray)
}

private object AtomicRecentVideoJournalIo : RecentVideoJournalIo {
    override fun read(file: File): ByteArray? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return AtomicFile(file).openRead().use { input ->
            val bytes = input.readBytesBounded(RecentVideoCodec.MAX_BYTES)
            bytes
        }
    }
    override fun write(file: File, bytes: ByteArray) {
        check(file.parentFile?.let { it.mkdirs() || it.isDirectory } == true)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = read(buffer)
            if (size < 0) break
            check(output.size() + size <= max) { "Recent video history exceeds its safe limit." }
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }
}

internal object RecentVideoCodec {
    const val MAX_BYTES = 512 * 1024
    fun encode(entries: List<RecentVideoEntry>): ByteArray {
        require(entries.size <= RecentVideoHistoryPolicy.MAX_ENTRIES)
        require(entries.map { it.key }.distinct().size == entries.size)
        val array = JSONArray()
        entries.forEach { it.validate(); array.put(JSONObject().put("kind", it.source.kind.name)
            .put("uri", it.source.uri).put("key", it.key).put("title", it.title)
            .put("position", it.positionMs).put("duration", it.durationMs).put("seekable", it.seekable)
            .put("playedAt", it.playedAt).put("favorite", it.favorite)) }
        return JSONObject().put("version", 1).put("entries", array).toString().toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): List<RecentVideoEntry> {
        require(bytes.size <= MAX_BYTES)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version") == 1)
        val array = root.getJSONArray("entries")
        require(array.length() <= RecentVideoHistoryPolicy.MAX_ENTRIES)
        val entries = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val source = RecentVideoSource(RecentVideoKind.valueOf(item.getString("kind")), item.getString("uri")).validate()
            require(item.getString("key") == source.key)
            RecentVideoEntry(source, item.getString("title"), item.getLong("position"), item.getLong("duration"),
                item.getBoolean("seekable"), item.getLong("playedAt"), item.getBoolean("favorite")).validate()
        }
        require(entries.map { it.key }.distinct().size == entries.size)
        return entries.sortedWith(compareByDescending<RecentVideoEntry> { it.playedAt }.thenBy { it.key })
    }
}

/** One bounded application-owned writer survives player/Activity closure without retaining their UI. */
internal class RecentVideoStore internal constructor(
    directory: File, private val io: RecentVideoJournalIo = AtomicRecentVideoJournalIo,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val onCommitted: () -> Unit = {}
) {
    private val file = File(directory, "history.json")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val writes = Channel<Mutation>(64)
    private val mutable = MutableStateFlow(RecentVideoHistoryState())
    val state: StateFlow<RecentVideoHistoryState> = mutable
    private sealed interface Mutation {
        data class Record(val entry: RecentVideoEntry) : Mutation
        data class Favorite(val key: String, val favorite: Boolean) : Mutation
        data class Remove(val key: String) : Mutation
    }

    init {
        scope.launch {
            var committed = emptyList<RecentVideoEntry>()
            var readable = false
            try {
                committed = io.read(file)?.let(RecentVideoCodec::decode).orEmpty()
                readable = true
                mutable.value = RecentVideoHistoryState(committed, false)
                try { onCommitted() } catch (_: Exception) {}
            } catch (_: Exception) {
                // Keep a corrupt/unreadable journal untouched; a new visit cannot erase earlier records.
                mutable.value = RecentVideoHistoryState(loading = false,
                    error = "Recent video history could not be read. Your saved videos are retained.")
            }
            for (mutation in writes) {
                if (!readable) continue
                val next = when (mutation) {
                    is Mutation.Record -> RecentVideoHistoryPolicy.record(committed, mutation.entry)
                    is Mutation.Favorite -> RecentVideoHistoryPolicy.favorite(committed, mutation.key, mutation.favorite)
                    is Mutation.Remove -> committed.filterNot { it.key == mutation.key }
                }
                try {
                    io.write(file, RecentVideoCodec.encode(next))
                    committed = next
                    mutable.value = RecentVideoHistoryState(committed, false)
                    try { onCommitted() } catch (_: Exception) {}
                } catch (_: Exception) {
                    mutable.value = RecentVideoHistoryState(committed, false,
                        "Recent video history could not be saved. Your saved videos are retained.")
                }
            }
        }
    }

    fun record(source: RecentVideoSource, position: Long, duration: Long, seekable: Boolean) {
        val actualDuration = duration.coerceAtLeast(0)
        val actualSeekable = seekable && actualDuration > 0
        val actualPosition = if (actualSeekable) position.coerceIn(0, actualDuration) else 0
        val label = when (source.kind) {
            RecentVideoKind.LOCAL -> runCatching { URI(source.uri).path.substringAfterLast('/').take(160) }.getOrNull()
            RecentVideoKind.ONLINE -> runCatching { URI(source.uri).host }.getOrNull()
        }.orEmpty().filterNot(Char::isISOControl).ifBlank { "Video" }.take(160)
        submit(Mutation.Record(RecentVideoEntry(source, label, actualPosition, actualDuration, actualSeekable, now().coerceAtLeast(0))))
    }
    fun favorite(key: String, value: Boolean) { submit(Mutation.Favorite(key, value)) }
    fun remove(key: String) { submit(Mutation.Remove(key)) }
    private fun submit(value: Mutation) {
        if (writes.trySend(value).isFailure) mutable.value = mutable.value.copy(
            error = "Recent video history is still saving. Try again shortly.")
    }
    companion object {
        @Volatile private var instance: RecentVideoStore? = null
        /** Caller supplies real bounded stream ownership; no duplicate history writer is created. */
        fun readWidgetEntries(context: Context, read: (File) -> ByteArray?): List<RecentVideoEntry> =
            read(File(context.applicationContext.filesDir, "video_history/history.json"))?.let(RecentVideoCodec::decode).orEmpty()
        fun shared(context: Context): RecentVideoStore = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: RecentVideoStore(File(app.filesDir, "video_history"),
                onCommitted = { com.mangalens.widget.MangaLensWidgetUpdates.request(app) }).also { instance = it }
        }
    }
}
