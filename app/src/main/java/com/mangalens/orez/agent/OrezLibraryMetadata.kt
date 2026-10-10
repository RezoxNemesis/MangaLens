package com.mangalens.orez.agent

import com.mangalens.core.reader.NativeLibraryOperationReceipt
import com.mangalens.core.reader.NativeLibraryOperationReceiptCodec
import com.mangalens.core.reader.ReadingStatus
import com.mangalens.core.reader.libraryTextKey
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

/** No URL/path/pixel/OCR/native-translation content is returned as a Library metadata result. */
internal data class OrezLibraryMetadataEntry(val chapterId: String, val title: String, val series: String,
    val notes: String, val collections: List<String>, val pageCount: Int, val position: Int,
    val bookmarked: Boolean, val readingStatus: String, val addedAt: Long, val lastReadAt: Long,
    val sourceTopologySha256: String, val operation: NativeLibraryOperationReceipt?) {
    fun summary(): JSONObject = JSONObject().put("chapterId",chapterId).put("title",bounded(title,256))
        .put("series",bounded(series,128)).put("pageCount",pageCount).put("position",position)
        .put("bookmarked",bookmarked).put("readingStatus",readingStatus).put("addedAt",addedAt).put("lastReadAt",lastReadAt)
    private fun bounded(value: String, limit: Int) = value.take(limit).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
}
internal object OrezLibraryMetadata {
    fun decode(chapterId: String, bytes: ByteArray): OrezLibraryMetadataEntry {
        require(chapterId.matches(Regex("[a-f0-9]{32}")) && bytes.size in 1..2_000_000)
        val body = JSONObject(bytes.toString(Charsets.UTF_8)); require(body.get("id") == chapterId)
        require(body.optInt("version",1) in 1..2); val pages = body.getJSONArray("pages"); require(pages.length() in 1..100_000)
        fun string(key: String, max: Int, optional: Boolean = true): String {
            val value = if (optional && !body.has(key)) "" else body.get(key) as String
            require(value.length <= max && '\u0000' !in value); return value
        }
        val title = string("title",2048,false); val series = string("seriesTitle",256); val notes = string("notes",8192)
        val list = body.optJSONArray("collections") ?: JSONArray(); require(list.length() <= 32)
        val collections = (0 until list.length()).map { (list.get(it) as String).also { text -> require(text.length <= 80 && text.none(Char::isISOControl)) } }
        fun long(key: String): Long {
            if (!body.has(key)) return 0L
            val value = body.get(key); require(value is Int || value is Long); return (value as Number).toLong().also { require(it >= 0) }
        }
        val position = long("position").also { require(it <= Int.MAX_VALUE) }.toInt().coerceAtMost(pages.length()-1)
        val status = if (body.has("readingStatus")) body.get("readingStatus") as String else ReadingStatus.READING.name
        require(status in ReadingStatus.entries.map { it.name })
        val bookmarked = if (body.has("bookmarked")) body.get("bookmarked") as Boolean else false
        val source = string("sourceUrl",8192)
        val fields = mutableListOf("orez-library-source-topology-v1",chapterId,source,pages.length().toString())
        val indices = HashSet<Int>()
        for (n in 0 until pages.length()) {
            val row = pages.getJSONObject(n); val index = row.get("index"); require(index is Int || index is Long)
            val ordinal = (index as Number).toLong(); require(ordinal in 0..Int.MAX_VALUE.toLong() && indices.add(ordinal.toInt()))
            val pageSource = row.get("source") as String; require(pageSource.length <= 8192 && '\u0000' !in pageSource)
            val file = if (!row.has("file") || row.isNull("file")) "" else row.get("file") as String
            require(file.isEmpty() || file == "null" || file.length <= 255 && file.none { it == '/' || it == '\\' || it.isISOControl() })
            fields += ordinal.toString(); fields += pageSource; fields += file
            // Original document URI/hash/order metadata is already local data; no provider is opened.
            fields += row.optJSONObject("documentSource")?.let { NativeLibraryOperationReceipt.stateDigest(it) }.orEmpty()
        }
        val fingerprint = NativeLibraryOperationReceipt.valueDigest(*fields.toTypedArray())
        val operation = if (!body.has("nativeLibraryOperation")) null else body.getJSONObject("nativeLibraryOperation").let(NativeLibraryOperationReceiptCodec::decode)
            ?.also { require(it.stateSha256 == NativeLibraryOperationReceipt.stateDigest(body)) { "Native Library receipt does not match current persisted state." } }
        return OrezLibraryMetadataEntry(chapterId,title,series,notes,Collections.unmodifiableList(collections),pages.length(),position,
            bookmarked,status,long("addedAt"),long("lastReadAt"),fingerprint,operation)
    }
    fun matches(entry: OrezLibraryMetadataEntry, query: String): Boolean {
        val words = libraryTextKey(query).trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val text = libraryTextKey((listOf(entry.title,entry.series,entry.notes) + entry.collections).joinToString("\n"))
        return words.all(text::contains)
    }
}
