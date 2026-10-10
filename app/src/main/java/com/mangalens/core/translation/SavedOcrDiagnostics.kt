package com.mangalens.core.translation

/** Actual accepted OCR observations and their translation outcomes, not an inferred semantic score. */
enum class SavedOcrOutcome { OBSERVED, TRANSLATED, REUSED, QUALITY_REJECTED, TRANSLATION_FAILED, GEOMETRY_CONFLICT, RECONSTRUCTION_DEFERRED, FITTING_DEFERRED, DEFERRED_LIMIT, DEFERRED_MODEL }
enum class SavedOcrGeometryKind { UNCLASSIFIED_TEXT, VERTICAL_GEOMETRY, SMALL_KANA_ADJACENT }
data class SavedOcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun valid(width: Int, height: Int) = left >= 0 && top >= 0 && right > left && bottom > top && right <= width && bottom <= height
}
data class SavedOcrFinding(val ordinal: Int, val bounds: SavedOcrBox, val recognizerScript: String,
    val mlKitDerivedConfidence: Float?, val lines: List<SavedOcrBox>, val outcome: SavedOcrOutcome,
    val nativeLetteringIndex: Int? = null, val proposedPanel: Int? = null,
    val geometryKind: SavedOcrGeometryKind = SavedOcrGeometryKind.UNCLASSIFIED_TEXT, val smallKanaNeighbourOrdinal: Int? = null, val writableBounds: SavedOcrBox? = null, val fittedSizeAtOne: Float? = null)

data class SavedPageOcrDiagnostics(val sourceSha256: String, val width: Int, val height: Int,
    val reconstructionVersion: Int, val acceptedRegionCount: Int, val findings: List<SavedOcrFinding>,
    val proposedPanels: List<SavedOcrBox> = emptyList()) {
    fun validate(page: ChapterTranslationPage) {
        require(sourceSha256.matches(Regex("[a-f0-9]{64}")) && sourceSha256 == page.sourceSha256)
        require(width > 0 && height > 0 && width.toLong() * height <= ChapterTranslationStore.MAX_SURFACE_PIXELS && reconstructionVersion in 1..2)
        require(acceptedRegionCount >= findings.size && acceptedRegionCount <= 4096 && findings.size <= MAX_FINDINGS && proposedPanels.size <= 24)
        require(findings.map { it.ordinal }.distinct().size == findings.size)
        findings.forEach { value ->
            require(value.ordinal in 0 until acceptedRegionCount && value.bounds.valid(width, height))
            require(value.recognizerScript in SCRIPTS && (value.mlKitDerivedConfidence == null || value.mlKitDerivedConfidence.isFinite() && value.mlKitDerivedConfidence > 0f && value.mlKitDerivedConfidence <= 1f))
            require(value.lines.size <= MAX_LINES && value.lines.all { it.valid(width, height) })
            require(value.nativeLetteringIndex == null || value.nativeLetteringIndex in page.lettering.indices)
            require(value.writableBounds == null || value.writableBounds.valid(width, height))
            require(value.fittedSizeAtOne == null || value.fittedSizeAtOne.isFinite() && value.fittedSizeAtOne in 1f..4096f)
            require(value.nativeLetteringIndex == null || value.outcome in setOf(SavedOcrOutcome.TRANSLATED, SavedOcrOutcome.REUSED))
            require(value.proposedPanel == null || value.proposedPanel in proposedPanels.indices)
            require(value.smallKanaNeighbourOrdinal == null || value.smallKanaNeighbourOrdinal in 0 until acceptedRegionCount && value.smallKanaNeighbourOrdinal != value.ordinal)
        }
        require(proposedPanels.all { it.valid(width, height) })
    }
    companion object {
        const val MAX_FINDINGS = 64
        const val MAX_LINES = 8
        const val MAX_TASK_BYTES = 256 * 1024
        val SCRIPTS = setOf("UNKNOWN", "LATIN", "DEVANAGARI", "CHINESE", "JAPANESE", "KOREAN")
    }
}
