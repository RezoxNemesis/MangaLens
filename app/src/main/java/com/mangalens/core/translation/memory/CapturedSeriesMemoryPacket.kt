package com.mangalens.core.translation.memory

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections

/** One bounded immutable request snapshot; it never reads ambient series journals during inference. */
class CapturedSeriesMemoryPacket private constructor(
    val chapterId: String,
    val sourceIdentity: String,
    val targetLanguage: String,
    val configurationIdentity: String,
    val firstPage: Int,
    val profileSha256: String,
    val truncated: Boolean,
    private val profile: SeriesMemoryProfile,
    private val chapters: List<MemoryChapterSnapshot>
) {
    val seriesId: String get() = profile.id
    val association: MemoryChapterAssociation get() = chapters.single { it.chapterId == chapterId }.association!!
    val associationRevision: Long get() = chapters.single { it.chapterId == chapterId }.associationRevision
    val sha256: String get() = hash(canonical(payload()))

    internal fun relevant(pageIndex: Int, source: String): RelevantSeriesMemory {
        val bubbles = chapters.flatMap { it.bubbles }
        // Every full identity was independently checked against the same captured base model/style.
        // Keep original receipts intact; a previous packet hash is not replaced in their authority.
        val identities = (bubbles.map { it.receipt.configurationIdentity } + configurationIdentity).distinct()
        val results = identities.map { identity -> SeriesMemoryQuery.relevant(
            MemoryRetrievalRequest(chapterId, pageIndex, source, targetLanguage, identity), chapters.mapNotNull { it.association }, profile, bubbles) }
        var remaining = 4096
        val prior = results.flatMap { it.priorDialogue }.distinctBy { it.bubbleId }
            .sortedWith(compareByDescending<MemorySearchHit> { hit -> chapters.single { it.chapterId == hit.source.chapterId }.association!!.ordinal ?: -1 }
                .thenByDescending { it.source.pageIndex }.thenBy { it.bubbleId }).take(8).mapNotNull { hit ->
                val text = hit.text.take(remaining).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
                remaining -= text.length
                text.takeIf { it.isNotBlank() }?.let { hit.copy(text = it) }
            }
        return RelevantSeriesMemory(seriesId, results.first().glossary, prior)
    }

    internal fun styleContext(): String = profile.style?.takeIf { it.targetLanguage.equals(targetLanguage, true) }
        ?.customInstructions.orEmpty().take(2048)

    fun toJson(): JSONObject = payload().put("sha256", sha256)
    private fun payload(): JSONObject = JSONObject().put("version", 1).put("chapterId", chapterId)
        .put("sourceIdentity", sourceIdentity).put("targetLanguage", targetLanguage).put("configurationIdentity", configurationIdentity).put("firstPage", firstPage)
        .put("profileSha256", profileSha256).put("truncated", truncated)
        .put("profile", JSONObject(SeriesMemoryCodec.profile(profile).toString(Charsets.UTF_8)))
        .put("chapters", JSONArray().apply { chapters.forEach { chapter -> put(JSONObject(SeriesMemoryCodec.chapter(
            MemoryChapterJournal(chapter.chapterId, chapter.association, chapter.bubbles, chapter.removed, chapter.associationRevision)
        ).toString(Charsets.UTF_8))) } })

    override fun equals(other: Any?): Boolean = other is CapturedSeriesMemoryPacket && sha256 == other.sha256
    override fun hashCode(): Int = sha256.hashCode()

    companion object {
        const val MAX_BYTES = 65_536
        internal fun capture(chapterId: String, sourceIdentity: String, target: String, configurationIdentity: String, firstPage: Int,
            fullProfile: SeriesMemoryProfile, snapshots: List<MemoryChapterSnapshot>, incomplete: Boolean): CapturedSeriesMemoryPacket {
            val current = requireNotNull(snapshots.singleOrNull { it.chapterId == chapterId })
            val link = requireNotNull(current.association)
            require(!current.removed && link.seriesId == fullProfile.id && !fullProfile.removed)
            fun before(location: MemoryLocation): Boolean = if (location.chapterId == chapterId) location.pageIndex < firstPage else {
                val earlier = snapshots.singleOrNull { it.chapterId == location.chapterId }?.association
                link.ordinal != null && earlier?.ordinal != null && earlier.ordinal < link.ordinal
            }
            val verified = snapshots.filter { !it.removed && it.association?.seriesId == fullProfile.id }
            val candidates = verified.flatMap { chapter -> chapter.bubbles.filter { bubble ->
                bubble.receipt.nativeAuthorityVersion == 1 && bubble.receipt.seriesId == fullProfile.id &&
                    bubble.receipt.associationRevision == chapter.associationRevision &&
                    bubble.receipt.targetLanguage.equals(target, true) &&
                    before(MemoryLocation(chapter.chapterId, bubble.receipt.source.pageIndex))
            } }.sortedWith(compareByDescending<MemoryIndexedBubble> { bubble ->
                verified.single { it.chapterId == bubble.receipt.source.chapterId }.association!!.ordinal ?: Int.MIN_VALUE
            }.thenByDescending { it.receipt.source.pageIndex }.thenBy { it.receipt.bubbleId })
            var bubbles = candidates.take(8).map { bubble -> bubble.copy(correction = bubble.correction?.let { correction ->
                correction.copy(revisions = frozen(correction.revisions.takeLast(1)))
            }) }
            val terms = fullProfile.glossary.filter { term -> term.targetLanguage.equals(target, true) &&
                (term.origin == null || before(term.origin) && verified.any { snapshot -> snapshot.bubbles.any { bubble ->
                    bubble.receipt.nativeAuthorityVersion == 1 && bubble.receipt.source.chapterId == term.origin.chapterId &&
                        bubble.receipt.source.pageIndex == term.origin.pageIndex && bubble.receipt.source.sourceSha256 == term.originSourceSha256
                } }) }.sortedWith(compareByDescending<SeriesGlossaryTerm> { it.source.length }.thenBy { it.id })
            var glossary = terms.take(16).map { it.copy(aliases = frozen(it.aliases)) }
            var clipped = incomplete || candidates.size > bubbles.size || terms.size > glossary.size
            val profileHash = hash(canonical(JSONObject(SeriesMemoryCodec.profile(fullProfile).toString(Charsets.UTF_8))))
            while (true) {
                val used = (bubbles.map { it.receipt.source.chapterId } + chapterId + glossary.mapNotNull { it.origin?.chapterId }).toSet()
                val chapterCopies = verified.filter { it.chapterId in used }.sortedBy { it.chapterId }.map { snapshot -> snapshot.copy(
                    bubbles = frozen(bubbles.filter { it.receipt.source.chapterId == snapshot.chapterId })) }
                val value = CapturedSeriesMemoryPacket(chapterId, sourceIdentity, target, configurationIdentity, firstPage, profileHash, clipped,
                    fullProfile.copy(glossary = frozen(glossary)), frozen(chapterCopies))
                if (value.toJson().toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES && chapterCopies.size <= 9) {
                    validate(value); return value
                }
                clipped = true
                if (bubbles.isNotEmpty()) bubbles = bubbles.dropLast(1)
                else if (glossary.isNotEmpty()) glossary = glossary.dropLast(1)
                else error("The selected series memory identity exceeds its bounded request limit.")
            }
        }

        fun fromJson(json: JSONObject): CapturedSeriesMemoryPacket {
            require(json.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            require(json.getInt("version") == 1)
            val profile = SeriesMemoryCodec.readProfile(json.getJSONObject("profile").toString().toByteArray(Charsets.UTF_8))
            val rows = json.getJSONArray("chapters"); require(rows.length() in 1..9)
            val chapters = (0 until rows.length()).map { index ->
                val saved = SeriesMemoryCodec.readChapter(rows.getJSONObject(index).toString().toByteArray(Charsets.UTF_8))
                MemoryChapterSnapshot(saved.chapterId, saved.association, frozen(saved.bubbles), saved.removed, saved.associationRevision)
            }
            val value = CapturedSeriesMemoryPacket(json.getString("chapterId"), json.getString("sourceIdentity"), json.getString("targetLanguage"),
                json.getString("configurationIdentity"), json.getInt("firstPage"), json.getString("profileSha256"), json.getBoolean("truncated"),
                profile.copy(glossary = frozen(profile.glossary.map { it.copy(aliases = frozen(it.aliases)) })), frozen(chapters))
            validate(value); require(json.getString("sha256") == value.sha256) { "Captured series memory packet was altered." }; return value
        }

        private fun validate(value: CapturedSeriesMemoryPacket) {
            require(memoryValidId(value.chapterId) && memoryValidHash(value.sourceIdentity) && memoryValidHash(value.profileSha256) && memoryValidHash(value.configurationIdentity))
            require(value.targetLanguage.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})?")) && value.firstPage in 0 until 2000)
            require(!value.profile.removed && value.profile.glossary.size <= 16 && value.chapters.size in 1..9)
            require(value.chapters.map { it.chapterId }.distinct().size == value.chapters.size)
            val link = value.association
            require(link.seriesId == value.profile.id && value.associationRevision >= 0)
            require(value.chapters.all { !it.removed && it.association?.seriesId == link.seriesId && it.associationRevision >= 0 })
            require(value.chapters.sumOf { it.bubbles.size } <= 8)
            value.chapters.forEach { chapter -> chapter.bubbles.forEach { bubble ->
                val receipt = bubble.receipt; receipt.validate()
                require(receipt.nativeAuthorityVersion == 1 && receipt.seriesId == link.seriesId && receipt.associationRevision == chapter.associationRevision)
                require(receipt.source.chapterId == chapter.chapterId && receipt.targetLanguage == value.targetLanguage)
                require(bubble.correction == null || bubble.correction.revisions.size == 1)
                require(if (chapter.chapterId == value.chapterId) receipt.source.pageIndex < value.firstPage else
                    link.ordinal != null && chapter.association!!.ordinal != null && chapter.association.ordinal!! < link.ordinal)
            } }
            require(value.toJson().toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        }

        private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
        private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key -> JSONObject.quote(key) + ":" + canonical(value.get(key)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }
}
