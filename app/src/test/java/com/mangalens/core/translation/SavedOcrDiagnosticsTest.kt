package com.mangalens.core.translation

import org.junit.Assert.*
import org.junit.Test

class SavedOcrDiagnosticsTest {
    private val sha = "a".repeat(64)
    private fun finding() = SavedOcrFinding(0, SavedOcrBox(10, 10, 30, 30), "JAPANESE", .83f,
        listOf(SavedOcrBox(10, 10, 30, 30)), SavedOcrOutcome.GEOMETRY_CONFLICT)
    private fun record() = SavedPageOcrDiagnostics(sha, 100, 200, 2, 1, listOf(finding()))
    private fun page() = ChapterTranslationPage(37, "/managed/source", sha, ChapterTranslationPageStatus.FAILED)
    @Test fun genuineFiniteConfidenceAndActualOutcomeRoundTrip() { val value = record(); value.validate(page()); assertEquals(value, SavedOcrDiagnosticsCodec.decode(SavedOcrDiagnosticsCodec.encode(value))) }
    @Test fun unavailableConfidenceRoundTripsWithoutReplacingItWithAScore() { val value = record().copy(findings = listOf(finding().copy(mlKitDerivedConfidence = null))); assertNull(SavedOcrDiagnosticsCodec.decode(SavedOcrDiagnosticsCodec.encode(value)).findings.single().mlKitDerivedConfidence) }
    @Test fun sourceDigestMismatchRejectsRecordedBoxes() { try { record().validate(page().copy(sourceSha256 = "b".repeat(64))); fail() } catch (_: IllegalArgumentException) { } }
    @Test fun fakeNonfiniteOrZeroConfidenceFailsValidation() {
        for (score in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 1.1f)) try { record().copy(findings = listOf(finding().copy(mlKitDerivedConfidence = score))).validate(page()); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectionCannotBorrowANativeLetteringIndex() { try { record().copy(findings = listOf(finding().copy(nativeLetteringIndex = 0))).validate(page()); fail() } catch (_: IllegalArgumentException) { } }
    @Test fun duplicateOrdinalsAreRejected() { try { record().copy(acceptedRegionCount = 2, findings = listOf(finding(), finding())).validate(page()); fail() } catch (_: IllegalArgumentException) { } }
    @Test fun claimedGutterGroupMustExist() { try { record().copy(findings = listOf(finding().copy(proposedPanel = 3))).validate(page()); fail() } catch (_: IllegalArgumentException) { } }
    @Test fun boundedBudgetOmitsDetailsWithoutInventingAcceptedCount() {
        val value = record().copy(acceptedRegionCount = 10, findings = (0 until 10).map { finding().copy(ordinal = it) })
        val summaryBytes = SavedOcrDiagnosticsCodec.bytes(value.copy(findings = emptyList()))
        val bounded = SavedOcrDiagnosticsCodec.bounded(value, summaryBytes)!!
        assertEquals(10, bounded.acceptedRegionCount); assertTrue(bounded.findings.isEmpty()); assertTrue(SavedOcrDiagnosticsCodec.bytes(bounded) <= summaryBytes)
    }
    @Test fun exhaustedBudgetDoesNotDisplaceNativeText() { assertNull(SavedOcrDiagnosticsCodec.bounded(record(), 0)); assertNull(SavedOcrDiagnosticsCodec.bounded(record(), 1)) }
    @Test fun versionedWritableFitGeometryRoundTrips() {
        val value = record().copy(findings = listOf(finding().copy(writableBounds = SavedOcrBox(1, 1, 40, 40), fittedSizeAtOne = 12.5f)))
        value.validate(page()); assertEquals(value, SavedOcrDiagnosticsCodec.decode(SavedOcrDiagnosticsCodec.encode(value)))
    }
    @Test fun invalidWritableBoxAndFitDoNotBecomeDiagnostics() {
        try { record().copy(findings = listOf(finding().copy(writableBounds = SavedOcrBox(0, 0, 500, 40)))).validate(page()); fail() } catch (_: IllegalArgumentException) { }
        try { record().copy(findings = listOf(finding().copy(fittedSizeAtOne = Float.NaN))).validate(page()); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun unclassifiedGeometryIsDistinctFromAFuriganaSuggestion() {
        assertEquals(SavedOcrGeometryKind.UNCLASSIFIED_TEXT, finding().geometryKind)
        val candidate = record().copy(acceptedRegionCount = 2, findings = listOf(finding().copy(geometryKind = SavedOcrGeometryKind.SMALL_KANA_ADJACENT, smallKanaNeighbourOrdinal = 1)))
        candidate.validate(page()); assertEquals(candidate, SavedOcrDiagnosticsCodec.decode(SavedOcrDiagnosticsCodec.encode(candidate)))
    }
}
