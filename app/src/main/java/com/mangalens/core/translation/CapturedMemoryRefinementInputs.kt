package com.mangalens.core.translation

import com.mangalens.core.translation.memory.CapturedSeriesMemoryPacket

internal data class CapturedMemoryRefinementInputs(val chapterContext: String, val glossary: Map<String, String>, val packetSha256: String?,
    val targetProjectionOmitted: Boolean = false)

/** Optional memory fits the existing real prompt budget; it never alters the pinned style/model. */
internal object CapturedMemoryRefinementPolicy {
    // The shipped pre-capture namespace is a historical lookup identity, never a generation default.
    private const val HISTORICAL_INPUT_PROFILE_LOOKUP = "orez-localization-v2"

    fun styleIdentity(base: String, configuration: ChapterTranslationConfig): String = base +
        (configuration.refinementRequest?.let {
            ":refinement:" + TranslationRefinementPolicy.hash(TranslationRefinementRequestCodec.identity(it)).take(20)
        } ?: "") + (if (configuration.localRefinement)
            ":localization:${configuration.refinementRequest?.inputProfileRevision ?: HISTORICAL_INPUT_PROFILE_LOOKUP}" else "") + if (configuration.reconstructionVersion >= 2) ":glyph-reconstruction-v${configuration.reconstructionVersion}" else ""

    fun inputs(packet: CapturedSeriesMemoryPacket?, pageIndex: Int, source: String, draft: String, target: String,
        request: TranslationRefinementRequest, chapterContext: String): CapturedMemoryRefinementInputs {
        if (packet == null) return CapturedMemoryRefinementInputs(chapterContext, emptyMap(), null)
        // hi-latn refines Hindi before rendering Roman output. A Roman packet contains no proven Hindi intermediate.
        if (!packet.targetLanguage.equals(target, ignoreCase = true)) {
            var context = chapterContext.take(4500)
            while (context.isNotEmpty() && !TranslationRefinementPolicy.validCapturedInputs(source, draft, target, request, context, emptyMap(), packet.sha256))
                context = context.dropLast(minOf(128, context.length))
            return CapturedMemoryRefinementInputs(context, emptyMap(), packet.sha256, targetProjectionOmitted = true)
        }
        val lexical = packet.relevant(pageIndex, source)
        val identity = packet.sha256
        val glossary = linkedMapOf<String, String>()
        for ((name, preferred) in lexical.glossary.entries.take(16)) {
            val candidate = glossary + (name to preferred)
            if (TranslationRefinementPolicy.validCapturedInputs(source, draft, target, request, "", candidate, identity)) glossary[name] = preferred
        }
        val memoryContext = buildList {
            packet.styleContext().takeIf { it.isNotBlank() }?.let { add("Explicit series preference (context): $it") }
            lexical.priorDialogue.forEach { hit -> add(hit.text) }
        }.joinToString("\n")
        var context = (listOf(chapterContext, memoryContext).filter { it.isNotBlank() }).joinToString("\n").take(4500)
        while (context.isNotEmpty() && !TranslationRefinementPolicy.validCapturedInputs(source, draft, target, request, context, glossary, identity))
            context = context.dropLast(minOf(128, context.length))
        return CapturedMemoryRefinementInputs(context, glossary.toMap(), identity)
    }

    fun cacheStyle(base: String, packet: CapturedSeriesMemoryPacket?, pageIndex: Int = 0, source: String = "", chapterContext: String = ""): String {
        if (packet == null) return base
        require(pageIndex in 0 until 2000 && source.length <= 4096 && chapterContext.length <= 4500)
        val contextIdentity = TranslationRefinementPolicy.hash(listOf(packet.sha256, pageIndex.toString(), source, chapterContext)
            .joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" })
        return "$base:series-memory:${packet.sha256}:context:$contextIdentity"
    }
}
