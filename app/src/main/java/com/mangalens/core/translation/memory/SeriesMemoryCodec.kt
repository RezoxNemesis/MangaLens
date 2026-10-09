package com.mangalens.core.translation.memory

import org.json.JSONArray
import org.json.JSONObject

/** Bounded schemas never confuse the cache, personal edits, source proof or generated surface. */
internal object SeriesMemoryCodec {
    fun chapter(value: MemoryChapterJournal): ByteArray = JSONObject().put("version", 3).put("chapterId", value.chapterId).put("associationRevision", value.associationRevision)
        .put("removed", value.removed).put("association", value.association?.let { JSONObject().put("seriesId", it.seriesId).put("ordinal", it.ordinal) })
        .put("bubbles", JSONArray().apply { value.bubbles.forEach { bubble -> put(JSONObject().put("receipt", receipt(bubble.receipt)).put("editRevision", bubble.editRevision)
            .put("correction", bubble.correction?.let { correction -> JSONObject().put("original", receipt(correction.original))
                .put("revisions", JSONArray().apply { correction.revisions.forEach { revision -> put(JSONObject().put("number", revision.revision)
                    .put("editedAt", revision.editedAt).put("ocr", revision.edit.correctedOcr).put("translation", revision.edit.translated)
                    .put("hindiDraft", revision.edit.hindiDraft)) } }) }) ) } }).toString().toByteArray(Charsets.UTF_8)

    fun readChapter(bytes: ByteArray): MemoryChapterJournal {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val version = json.nonNegativeInt("version"); require(version in 1..3)
        val id = json.getString("chapterId"); require(memoryValidId(id))
        val association = json.optJSONObject("association")?.let { row -> MemoryChapterAssociation(id, row.getString("seriesId"),
            if (row.isNull("ordinal")) null else row.getInt("ordinal")).also { it.validate() } }
        val rows = json.getJSONArray("bubbles"); require(rows.length() <= 2_048)
        val bubbles = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val captured = readReceipt(row.getJSONObject("receipt")); require(captured.source.chapterId == id)
            val correction = row.optJSONObject("correction")?.let { saved ->
                val original = readReceipt(saved.getJSONObject("original")); require(original.bubbleId == captured.bubbleId)
                val history = saved.getJSONArray("revisions"); require(history.length() in 1..32)
                MemoryCorrection(original, (0 until history.length()).map { n ->
                    val revision = history.getJSONObject(n)
                    val edit = MemoryCorrectionEdit(revision.optionalText("ocr"), revision.optionalText("translation"), revision.optionalText("hindiDraft"))
                    edit.validate()
                    val number = revision.nonNegativeInt("number"); val editedAt = revision.getLong("editedAt")
                    require(number > 0 && editedAt >= 0)
                    MemoryCorrectionRevision(number, edit, editedAt)
                }).also { correction ->
                    require(version != 1 || correction.revisions.first().revision == 1)
                    require(correction.revisions.zipWithNext().all { (before, after) -> after.revision.toLong() == before.revision.toLong() + 1 })
                }
            }
            val editRevision = if (version == 1) correction?.revision ?: 0 else row.nonNegativeInt("editRevision")
            require(correction == null || correction.revision == editRevision)
            MemoryIndexedBubble(captured, correction, editRevision)
        }
        require(bubbles.map { it.receipt.bubbleId }.distinct().size == bubbles.size)
        val removed = json.getBoolean("removed"); require(!removed || (bubbles.isEmpty() && association == null))
        val associationRevision = if (version < 3) 0L else json.getLong("associationRevision").also { require(it >= 0) }
        return MemoryChapterJournal(id, association, bubbles, removed, associationRevision)
    }

    fun profile(value: SeriesMemoryProfile): ByteArray = JSONObject().put("version", 1).put("id", value.id).put("title", value.title)
        .put("removed", value.removed).put("style", value.style?.let { JSONObject().put("id", it.styleId).put("target", it.targetLanguage).put("custom", it.customInstructions) })
        .put("glossary", JSONArray().apply { value.glossary.forEach { term -> put(JSONObject().put("id", term.id).put("source", term.source)
            .put("preferred", term.preferred).put("target", term.targetLanguage).put("kind", term.kind.name).put("aliases", JSONArray(term.aliases))
            .put("updatedAt", term.updatedAt).put("originSha", term.originSourceSha256).put("origin", term.origin?.let { JSONObject().put("chapter", it.chapterId).put("page", it.pageIndex) })) } })
        .toString().toByteArray(Charsets.UTF_8)

    fun readProfile(bytes: ByteArray): SeriesMemoryProfile {
        val json = JSONObject(bytes.toString(Charsets.UTF_8)); require(json.getInt("version") == 1)
        val rows = json.getJSONArray("glossary"); require(rows.length() <= 512)
        val terms = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i); val aliases = row.getJSONArray("aliases"); require(aliases.length() <= 16)
            SeriesGlossaryTerm(row.getString("id"), row.getString("source"), row.getString("preferred"), row.getString("target"),
                MemoryTermKind.valueOf(row.getString("kind")), (0 until aliases.length()).map(aliases::getString),
                row.optJSONObject("origin")?.let { MemoryLocation(it.getString("chapter"), it.getInt("page")) }, row.getLong("updatedAt"), row.optionalText("originSha"))
        }
        val style = json.optJSONObject("style")?.let { SeriesStylePreference(it.getString("id"), it.getString("target"), it.getString("custom")) }
        return SeriesMemoryProfile(json.getString("id"), json.getString("title"), terms, style, json.getBoolean("removed")).also {
            it.validate(); require(!it.removed || (it.glossary.isEmpty() && it.style == null))
        }
    }

    private fun receipt(value: MemoryPublicationReceipt): JSONObject = value.source.let { source ->
        JSONObject().put("chapter", source.chapterId).put("page", source.pageIndex).put("sourcePath", source.sourcePath).put("sourceSha", source.sourceSha256)
            .put("width", source.imageWidth).put("height", source.imageHeight).put("left", source.bounds.left).put("top", source.bounds.top)
            .put("right", source.bounds.right).put("bottom", source.bounds.bottom).put("task", value.taskId).put("generation", value.generation)
            .put("target", value.targetLanguage).put("config", value.configurationIdentity).put("ocr", value.originalOcr).put("translation", value.originalTranslation)
            .put("outputPath", value.outputPath).put("outputSha", value.outputSha256).put("seriesId", value.seriesId)
            .put("nativeAuthorityVersion", value.nativeAuthorityVersion).put("ownerRequestId", value.ownerRequestId)
            .put("presentationEpoch", value.presentationEpoch).put("associationRevision", value.associationRevision)
    }

    private fun readReceipt(row: JSONObject) = MemoryPublicationReceipt(
        MemorySourceProof(row.getString("chapter"), row.getInt("page"), row.getString("sourcePath"), row.getString("sourceSha"), row.getInt("width"), row.getInt("height"),
            MemoryRegionBounds(row.getInt("left"), row.getInt("top"), row.getInt("right"), row.getInt("bottom"))),
        row.getString("task"), row.getString("generation"), row.getString("target"), row.getString("config"), row.getString("ocr"),
        row.optionalText("translation"), row.optionalText("outputPath"), row.optionalText("outputSha"), row.optionalText("seriesId"),
        if (row.has("nativeAuthorityVersion")) row.nonNegativeInt("nativeAuthorityVersion") else 0,
        row.optionalText("ownerRequestId"), row.optionalLong("presentationEpoch"), row.optionalLong("associationRevision")).also { it.validate() }

    private fun JSONObject.optionalLong(key: String): Long? = if (!has(key) || isNull(key)) null else {
        val value = get(key); require(value is Int || value is Long); (value as Number).toLong()
    }

    private fun JSONObject.optionalText(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.nonNegativeInt(key: String): Int {
        val value = get(key)
        require(value is Int || value is Long) { "The memory edit version must be an integer." }
        val number = (value as Number).toLong()
        require(number in 0..Int.MAX_VALUE.toLong())
        return number.toInt()
    }
}
