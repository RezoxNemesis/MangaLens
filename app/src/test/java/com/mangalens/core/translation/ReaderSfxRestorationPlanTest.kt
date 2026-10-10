package com.mangalens.core.translation

import com.mangalens.core.translation.memory.*
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. Geometry describes real saved dimensions, not fabricated crop authority. */
class ReaderSfxRestorationPlanTest {
    private val native = SavedMangaLettering("Bang!", "धम!", 40, 50, 130, 150, "sans-serif", 0, -1, 24f, "ALIGN_CENTER", 50, 67, 113, 133,
        originalSourceBounds = SavedOriginalSourceBounds(left = 200, top = 200, right = 450, bottom = 400))
    private val page = ChapterTranslationPage(37, "original", "a".repeat(64), imageWidth = 250, imageHeight = 333,
        lettering = listOf(native), originalWidth = 1001, originalHeight = 1009)
    @Test fun entireWritablePatchIsMappedOutwardInsteadOfOnlyTheRecognizedGlyphBox() {
        assertEquals(MemoryRegionBounds(160, 151, 521, 455), ReaderSfxRestorationPlan.originalPatch(page, 0, native))
        assertEquals(SavedOriginalSourceBounds(left = 200, top = 200, right = 450, bottom = 400), native.originalSourceBounds)
    }
    @Test fun aSinglePixelOfAnotherNativePatchRejectsOriginalRestoration() {
        val other = native.copy(left = 129, top = 80, right = 160, bottom = 170)
        assertNull(ReaderSfxRestorationPlan.originalPatch(page.copy(lettering = listOf(native, other)), 0, native))
    }
    @Test fun touchingNativeEdgesDoNotInventAnIntersection() {
        val other = native.copy(left = 130, top = 80, right = 160, bottom = 170)
        assertNotNull(ReaderSfxRestorationPlan.originalPatch(page.copy(lettering = listOf(native, other)), 0, native))
    }
    @Test fun anIndexOrExpectedNativeChangeCannotChooseTheSamePatch() {
        assertNull(ReaderSfxRestorationPlan.originalPatch(page, 1, native))
        assertNull(ReaderSfxRestorationPlan.originalPatch(page, 0, native.copy(translated = "धड़ाम!")))
    }
    @Test fun absentOriginalDimensionsOrBoundsRemainReadOnlyLegacy() {
        assertNull(ReaderSfxRestorationPlan.originalPatch(page.copy(originalWidth = null), 0, native))
        val old = native.copy(originalSourceBounds = null)
        assertNull(ReaderSfxRestorationPlan.originalPatch(page.copy(lettering = listOf(old)), 0, old))
    }
    @Test fun invalidWritableNativeGeometryCannotCreateAnOriginalCrop() {
        val bad = native.copy(left = -1)
        assertNull(ReaderSfxRestorationPlan.originalPatch(page.copy(lettering = listOf(bad)), 0, bad))
    }
    @Test fun onlyExplicitKeepAlongsideAndAnnotateNeedRestoration() {
        val personal = PersonalMangaLettering(native, native, 1)
        assertFalse(ReaderSfxRestorationPlan.needsOriginal(personal))
        for (mode in MemorySfxPresentation.entries) assertEquals(mode != MemorySfxPresentation.REPLACE,
            ReaderSfxRestorationPlan.needsOriginal(personal.copy(regionPresentation = MemoryRegionPresentation(MemoryUserRegionKind.SFX, mode))))
    }
    @Test fun ordinaryManualKindsDoNotChangeRenderedNativePixels() {
        for (kind in MemoryUserRegionKind.entries.filter { it != MemoryUserRegionKind.SFX }) assertFalse(ReaderSfxRestorationPlan.needsOriginal(
            PersonalMangaLettering(native, native, 1, MemoryRegionPresentation(kind))))
    }
    @Test fun selectedCropBudgetStaysBelowTheExistingOrdinaryPreviewBudget() {
        val source = MemorySourceProof("chapter", 37, "/actual-original", "a".repeat(64), 10000, 10000, MemoryRegionBounds(0, 0, 10000, 10000))
        val plan = ReaderBubbleCropPlan.create(source, ReaderSfxRestorationPlan.MAX_DECODE_PIXELS)
        assertTrue(plan.predictedWidth.toLong() * plan.predictedHeight <= 500000)
        assertTrue(plan.acceptsActualDecode(plan.predictedWidth, plan.predictedHeight))
        assertFalse(plan.acceptsActualDecode(1000, 1000))
    }
}
