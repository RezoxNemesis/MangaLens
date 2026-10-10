package com.mangalens.core.translation

import com.mangalens.core.translation.memory.MemoryChapterSnapshot
import com.mangalens.core.translation.memory.MemoryReadDeliveryLease
import com.mangalens.core.translation.memory.MemorySourceProof

/** Saved values stay separate from personal display text. This is not a new OCR/model result. */
internal data class ReaderBubbleView(
    val originalOcr: String,
    val originalTranslation: String,
    val personalOcr: String?,
    val personalTranslation: String?,
    val personalRevision: Int,
    val savedHindiDraft: String?,
    val personalHindiDraft: String?,
    val targetLanguage: String,
    val hasOriginalCrop: Boolean,
    val linkedSeriesId: String?,
    val linkedSeriesTitle: String?
)

/** Created only from actual saved native proof on IO; a numeric tap cannot create credentials. */
internal class ReaderBubbleInspection internal constructor(
    internal val presentation: ReaderMemoryPresentation,
    val pageIndex: Int,
    val letteringIndex: Int,
    internal val expectedNative: SavedMangaLettering,
    val source: MemorySourceProof?,
    internal val readerReceipt: ReaderTranslationReceipt,
    internal val proof: NativeMemoryPageProof,
    internal val memoryDelivery: MemoryReadDeliveryLease,
    internal val chapter: MemoryChapterSnapshot,
    internal val view: ReaderBubbleView,
    internal val profile: com.mangalens.core.translation.memory.SeriesMemoryProfile? = null
)

/** Runtime source stamps are never serialized into personal/native journals. */
internal class ReaderBubbleEditScope(val presentation: ReaderMemoryPresentation, val receipt: ReaderTranslationReceipt,
    val proof: NativeMemoryPageProof, val letteringIndex: Int, val expectedNative: SavedMangaLettering)
internal class ReaderBubbleEditorDelivery(val scope: ReaderBubbleEditScope, val editor: ReaderMemoryEditor,
    val memoryDelivery: MemoryReadDeliveryLease)
