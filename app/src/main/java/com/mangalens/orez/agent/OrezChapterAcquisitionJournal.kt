package com.mangalens.orez.agent

import com.mangalens.core.acquisition.ChapterImageCandidate
import com.mangalens.core.acquisition.ChapterImagePromotion
import com.mangalens.core.reader.ChapterPageAcquisitionPolicy
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.ChapterPromotionHint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private native proof of the stable request, its exact ordered image catalog and acquired originals. */
internal data class OrezChapterAcquisitionRecord(
    val requestId: String,
    val scopeFingerprint: String,
    val title: String,
    val documentSha256: String,
    val candidates: List<ChapterImageCandidate>,
    val pages: List<ChapterPage> = emptyList(),
    val completed: Boolean = false
) {
    val allPagesAcquired: Boolean get() = pages.size == candidates.size &&
        pages.map { it.index } == (1..candidates.size).toList() &&
        pages.all { it.localPath != null && it.contentRevision != null && it.error == null }
    val namespace: String get() = OrezNextChapterPolicy.sha(requestId).take(16)
    fun validate(images: File): OrezChapterAcquisitionRecord {
        require(requestId.matches(Regex("orez-[A-Za-z0-9-]{1,140}")) && title.length <= 250 &&
            listOf(scopeFingerprint, documentSha256).all { it.matches(Regex("[a-f0-9]{64}")) }) { "Native acquisition ownership is incomplete." }
        require(candidates.size in 1..OrezNextChapterPolicy.MAX_PAGES && candidates.map { it.url }.distinct().size == candidates.size)
        candidates.forEach { OrezNextChapterPolicy.publicUrl(it.url) }
        require(pages.size <= candidates.size && pages.map { it.index }.distinct().size == pages.size &&
            pages.map { it.index } == pages.map { it.index }.sorted() && (!completed || allPagesAcquired))
        pages.forEach { page ->
            require(page.index in 1..candidates.size)
            val candidate = candidates[page.index - 1]
            require(page.sourceUrl == candidate.url && page.documentSource == null) { "Native page exceeds its captured catalog." }
            if (page.error != null) {
                require(page.error == ChapterPageAcquisitionPolicy.FAILURE && page.localPath == null &&
                    page.contentRevision == null && page.promotionHint == null) { "A failed native page cannot carry original-byte proof." }
            } else {
                val source = File(requireNotNull(page.localPath)).canonicalFile
                val revision = requireNotNull(page.contentRevision) { "Native page revision is missing." }
                val expectedName = "owned_${namespace}_${page.index}_${OrezNextChapterPolicy.sha(candidate.url).take(16)}.img"
                require(source.parentFile == images.canonicalFile && source.name == expectedName &&
                    revision.substringBefore(':').matches(Regex("[a-f0-9]{64}")) &&
                    page.promotionHint?.reason == candidate.promotion &&
                    (page.promotionHint == null || page.promotionHint.sourceSha256 == revision.substringBefore(':'))) { "Native page proof exceeds its captured catalog or file owner." }
            }
        }
        return this
    }
}

internal class OrezChapterAcquisitionJournal(files: File, private val requestId: String, private val owner: OrezAcquisitionPrivateOwner) {
    private val images = File(files, "chapters")
    private val directory = File(files, "orez_chapter_acquisitions").apply { check(isDirectory || mkdirs()) }
    private val file = File(directory, OrezNextChapterPolicy.sha(requestId).take(32) + ".json")
    private val io = OrezOwnedChapterJournalIo(owner)
    fun read(): OrezChapterAcquisitionRecord? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val bytes = io.read(file).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_BYTES) { "Native acquisition journal exceeds its safe limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decodeRecord(bytes, requestId, images)
    }
    fun write(record: OrezChapterAcquisitionRecord) {
        io.write(file, encodeRecord(record, requestId, images))
    }
    companion object {
        private const val MAX_BYTES = 1_000_000
        /** Bounded serialization only; replay never grants fresh original-byte or Library authority. */
        internal fun decodeRecord(bytes: ByteArray, requestId: String, images: File): OrezChapterAcquisitionRecord {
            require(bytes.size <= MAX_BYTES)
            val root = JSONObject(bytes.toString(Charsets.UTF_8)); val schema = root.getInt("schema"); require(schema in 1..2)
            val candidates = root.getJSONArray("candidates"); val pages = root.getJSONArray("pages")
            require(candidates.length() in 1..OrezNextChapterPolicy.MAX_PAGES && pages.length() <= candidates.length())
            val record = OrezChapterAcquisitionRecord(root.getString("requestId"), root.getString("scopeFingerprint"), root.getString("title"),
                root.getString("documentSha256"), (0 until candidates.length()).map { index -> candidates.getJSONObject(index).let {
                    ChapterImageCandidate(it.getString("url"), it.optString("promotion").takeIf { value -> value.isNotEmpty() && value != "null" }?.let(ChapterImagePromotion::valueOf))
                } }, (0 until pages.length()).map { offset -> pages.getJSONObject(offset).let { page ->
                    // Schema 1 is the original successful prefix; schema 2 carries actual sparse ordinals.
                    val ordinal = if (schema == 1) offset + 1 else page.getInt("index")
                    require(ordinal in 1..candidates.length())
                    val candidate = candidates.getJSONObject(ordinal - 1)
                    val source = candidate.getString("url")
                    if (schema == 2 && !page.isNull("error")) {
                        require(page.getString("error") == ChapterPageAcquisitionPolicy.FAILURE && page.isNull("path") && page.isNull("revision"))
                        ChapterPageAcquisitionPolicy.failure(ordinal, source)
                    } else {
                        val promotion = candidate.optString("promotion").takeIf { it.isNotEmpty() && it != "null" }?.let(ChapterImagePromotion::valueOf)
                        val revision = page.getString("revision")
                        ChapterPage(ordinal, source, page.getString("path"), contentRevision = revision,
                            promotionHint = promotion?.let { ChapterPromotionHint(it, revision.substringBefore(':')) })
                    }
                } }, root.getBoolean("completed"))
            require(record.requestId == requestId) { "Native acquisition journal belongs to another request." }
            return record.validate(images)
        }
        internal fun encodeRecord(record: OrezChapterAcquisitionRecord, requestId: String, images: File): ByteArray {
            require(record.requestId == requestId); record.validate(images)
            val root = JSONObject().put("schema", 2).put("requestId", requestId).put("scopeFingerprint", record.scopeFingerprint)
                .put("title", record.title).put("documentSha256", record.documentSha256).put("completed", record.completed)
                .put("candidates", JSONArray(record.candidates.map { JSONObject().put("url", it.url).put("promotion", it.promotion?.name ?: JSONObject.NULL) }))
                .put("pages", JSONArray(record.pages.map { JSONObject().put("index", it.index).put("path", it.localPath ?: JSONObject.NULL)
                    .put("revision", it.contentRevision ?: JSONObject.NULL).put("error", it.error ?: JSONObject.NULL) }))
            val bytes = root.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= MAX_BYTES)
            return bytes
        }
    }
}
