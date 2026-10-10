package com.mangalens.ui.reader

import com.mangalens.core.translation.*
import org.junit.Assert.*
import org.junit.Test

class ReaderOcrDiagnosticsPolicyTest {
    private val sha = "a".repeat(64)
    private val diagnostics = SavedPageOcrDiagnostics(sha, 20, 40, 2, 0, emptyList())
    @Test fun changedSourceShaRetiresBoxes() { assertFalse(ReaderOcrDiagnosticsPolicy.matchesRevision(diagnostics, "b".repeat(64) + ":new")) }
    @Test fun sameSourceIncarnationMayDisplayItsOwnRecordedPass() { assertTrue(ReaderOcrDiagnosticsPolicy.matchesRevision(diagnostics, sha + ":verified-repair")) }
    @Test fun legacyMissingMetadataStaysUnavailable() { assertFalse(ReaderOcrDiagnosticsPolicy.matchesRevision(null, sha)); assertEquals("unavailable", ReaderOcrDiagnosticsPolicy.confidence(null)) }
    @Test fun absentIncarnationDigestUsesAcceptedSavedPresentationRatherThanInventingAHash() { assertTrue(ReaderOcrDiagnosticsPolicy.matchesRevision(diagnostics, "")) }
    @Test fun invalidConfidenceNeverDisplaysAPretendPercentage() { for (value in listOf(Float.NaN, 0f, -1f, 2f)) assertEquals("unavailable", ReaderOcrDiagnosticsPolicy.confidence(value)) }
    @Test fun actualConfidenceUsesBoundedLocaleIndependentFormatting() { assertEquals("0.83", ReaderOcrDiagnosticsPolicy.confidence(.831f)) }
    @Test fun deferredCapacityIsNotMislabelledAsRecognizedTextRejection() { assertFalse(ReaderOcrDiagnosticsPolicy.rejected(SavedOcrOutcome.DEFERRED_LIMIT)); assertTrue(ReaderOcrDiagnosticsPolicy.rejected(SavedOcrOutcome.GEOMETRY_CONFLICT)); assertTrue(ReaderOcrDiagnosticsPolicy.rejected(SavedOcrOutcome.FITTING_DEFERRED)) }
}
