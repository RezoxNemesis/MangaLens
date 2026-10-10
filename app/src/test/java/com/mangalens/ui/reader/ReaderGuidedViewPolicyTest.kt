package com.mangalens.ui.reader
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. Pure display transforms; no decoded-image or gesture claim. */
class ReaderGuidedViewPolicyTest {
    @Test fun wholePageRetainsTheDefaultFittedView() { val p=ReaderGuidedViewPolicy.transform(400f,600f,.5f,ReaderGuidedViewPolicy.WHOLE_PAGE); assertEquals(ReaderGuidedTransform(1f,0f,0f),p) }
    @Test fun topHalfPanelCentersActualFittedCanvasWithoutChangingSourceCoordinates() { val p=ReaderGuidedViewPolicy.transform(400f,600f,.5f,ReaderPanelRect(0f,0f,1f,.5f)); assertEquals(400f/300f,p.scale,.001f); assertEquals(0f,p.x,0f); assertEquals(200f,p.y,.001f) }
    @Test fun lowerHalfCentersWithOppositeTranslation() { val p=ReaderGuidedViewPolicy.transform(400f,600f,.5f,ReaderPanelRect(0f,.5f,1f,1f)); assertEquals(-200f,p.y,.001f) }
    @Test fun narrowRightPanelUsesBothViewportAxesAndRemainsUniform() { val p=ReaderGuidedViewPolicy.transform(400f,600f,.5f,ReaderPanelRect(.5f,0f,1f,1f)); assertEquals(1f,p.scale,0f); assertEquals(-75f,p.x,.001f) }
    @Test fun tinyValidPanelCannotCreateAnUnboundedLayerScale() { val p=ReaderGuidedViewPolicy.transform(400f,600f,1f,ReaderPanelRect(.5f,.5f,.5001f,.5001f)); assertEquals(16f,p.scale,0f); assertTrue(p.x.isFinite()&&p.y.isFinite()) }
    @Test fun changedViewportRecomputesFitWithoutChangingTheNormalizedPanel() { val rect=ReaderPanelRect(0f,0f,.5f,.5f); val a=ReaderGuidedViewPolicy.transform(400f,600f,.5f,rect); val b=ReaderGuidedViewPolicy.transform(600f,400f,.5f,rect); assertNotEquals(a,b); assertEquals(ReaderPanelRect(0f,0f,.5f,.5f),rect) }
    @Test fun nonfiniteViewportCannotCreateAnImageTransform() { assertEquals(ReaderGuidedTransform(1f,0f,0f),ReaderGuidedViewPolicy.transform(Float.NaN,600f,.5f,ReaderGuidedViewPolicy.WHOLE_PAGE)) }
    @Test fun emptyOrOutOfRangePanelFallsBackWithoutInventingBounds() { for(r in listOf(ReaderPanelRect(0f,0f,0f,1f),ReaderPanelRect(-.1f,0f,1f,1f),ReaderPanelRect(0f,0f,Float.NaN,1f))) assertEquals(ReaderGuidedTransform(1f,0f,0f),ReaderGuidedViewPolicy.transform(400f,600f,.5f,r)) }
    @Test fun extremeInputsNeverEmitNonfiniteTranslation() { val p=ReaderGuidedViewPolicy.transform(Float.MAX_VALUE,Float.MAX_VALUE,1f,ReaderPanelRect(0f,0f,.25f,.25f)); assertTrue(p.scale.isFinite()&&p.x.isFinite()&&p.y.isFinite()) }
    @Test fun nextPanelStaysWithinCurrentPage() { assertEquals(ReaderGuidedStep(2,0),ReaderGuidedViewPolicy.advance(1,4,1)) }
    @Test fun previousPanelStaysWithinCurrentPage() { assertEquals(ReaderGuidedStep(0,0),ReaderGuidedViewPolicy.advance(1,4,-1)) }
    @Test fun lastPanelRequestsNextLogicalPage() { assertEquals(ReaderGuidedStep(3,1),ReaderGuidedViewPolicy.advance(3,4,1)) }
    @Test fun firstPanelRequestsPreviousLogicalPage() { assertEquals(ReaderGuidedStep(0,-1),ReaderGuidedViewPolicy.advance(0,4,-1)) }
    @Test fun fallbackWithoutPanelsStillAllowsNeighbourPageNavigation() { assertEquals(ReaderGuidedStep(0,1),ReaderGuidedViewPolicy.advance(0,0,1)); assertEquals(ReaderGuidedStep(0,-1),ReaderGuidedViewPolicy.advance(0,0,-1)) }
    @Test fun invalidSavedIndexIsClampedBeforeNavigation() { assertEquals(ReaderGuidedStep(3,0),ReaderGuidedViewPolicy.advance(Int.MAX_VALUE,5,-1)); assertEquals(0,ReaderGuidedViewPolicy.boundedIndex(-5,4)) }
}
