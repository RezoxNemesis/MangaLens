package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*

internal data class PreparedNativeMemorySearchHit(val hit: MemorySearchHit, val receipt: ReaderTranslationReceipt, val proof: NativeMemoryPageProof)

/** Hashes outside the monitor; accepting bounded read-only hits uses the actual short native fence. */
internal class NativeMemorySearchAuthority(private val native: ChapterTranslationStore,
    private val prepareProof: (ReaderTranslationReceipt, Int) -> NativeMemoryPageProof? = native::prepareMemoryPublication) {
    fun prepare(snapshot: MemoryChapterSnapshot, hit: MemorySearchHit): PreparedNativeMemorySearchHit? {
        if (snapshot.removed) return null
        val bubble = snapshot.bubbles.singleOrNull { it.receipt.bubbleId == hit.bubbleId } ?: return null
        val receipt = bubble.receipt
        if (hit.source != receipt.source || hit.targetLanguage != receipt.targetLanguage || hit.configurationIdentity != receipt.configurationIdentity ||
            hit.revision != bubble.editRevision || hit.seriesId != snapshot.association?.seriesId ||
            snapshot.associationRevision != receipt.associationRevision || snapshot.association?.seriesId != receipt.seriesId) return null
        val expectedText = when (hit.kind) {
            MemorySearchKind.OCR -> receipt.originalOcr
            MemorySearchKind.TRANSLATION -> receipt.originalTranslation
            MemorySearchKind.CORRECTED_OCR -> bubble.correction?.edit?.correctedOcr
            MemorySearchKind.CORRECTED_TRANSLATION -> bubble.correction?.edit?.translated
        }
        if (hit.text != expectedText) return null
        val task = native.get(receipt.taskId)?.takeIf { !it.validationPending } ?: return null
        val readerReceipt = ReaderTranslationPresentation.receipt(task)
        val proof = prepareProof(readerReceipt, hit.source.pageIndex) ?: return null
        if (!nativeReceiptMatches(receipt, proof, native.memoryConfigurationIdentity(task.config))) return null
        val index = proof.page.lettering.indexOfFirst { it.source == receipt.originalOcr && it.translated == receipt.originalTranslation &&
            it.originalSourceBounds?.let { bounds -> MemoryRegionBounds(bounds.left, bounds.top, bounds.right, bounds.bottom) } == receipt.source.bounds }
        if (index < 0) return null
        if (hit.kind in setOf(MemorySearchKind.CORRECTED_OCR, MemorySearchKind.CORRECTED_TRANSLATION) &&
            PersonalMemoryOverlayPolicy.project(proof, receipt.configurationIdentity, snapshot)[index] == null) return null
        return PreparedNativeMemorySearchHit(hit, readerReceipt, proof)
    }

    fun <T> publish(prepared: List<PreparedNativeMemorySearchHit>, publish: (List<MemorySearchHit>) -> T): T = synchronized(native) {
        require(prepared.size <= 8)
        val accepted = prepared.mapNotNull { candidate ->
            runCatching {
                var hit: MemorySearchHit? = null
                native.commitMemoryPublication(candidate.receipt, candidate.proof) { hit = candidate.hit }
                hit
            }.getOrNull()
        }
        publish(accepted)
    }
}
