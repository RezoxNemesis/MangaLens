package com.mangalens.ui.reader
import com.mangalens.core.translation.SavedMangaLettering
import org.junit.Assert.*
import org.junit.Test
/** Authored UNRUN. Uses actual draw metadata; does not claim bitmap or Compose acceptance. */
class ReaderDisplayGeometryTest {
    private val native=SavedMangaLettering("original", "saved",20,40,60,100,"sans-serif",0,-16777216,18f,"ALIGN_CENTER",20,40,60,100)
    private fun capture()=ReaderBubbleDrawCapture(listOf(ReaderBubbleHitTarget(ReaderBubbleTap(37,5,native),100,200)))
    @Test fun defaultTransformIsIdentityAtSeveralPoints() { for (x in listOf(0f,25f,99f)) { val p=ReaderDisplayGeometry.toCanvas(100f,200f,x,40f,0f)!!; assertEquals(x,p.x,0f); assertEquals(40f,p.y,0f) } }
    @Test fun cropCenterRemainsStationaryAndEdgesMapToRetainedPixels() { val p=ReaderDisplayGeometry.toCanvas(100f,200f,0f,0f,.1f)!!; assertEquals(10f,p.x,.001f); assertEquals(20f,p.y,.001f); val c=ReaderDisplayGeometry.toCanvas(100f,200f,50f,100f,.1f)!!; assertEquals(50f,c.x,0f); assertEquals(100f,c.y,0f) }
    @Test fun rightAndBottomOuterEdgesDoNotAcquireLettering() { assertNull(ReaderDisplayGeometry.toCanvas(100f,200f,100f,20f,.1f)); assertNull(ReaderDisplayGeometry.toCanvas(100f,200f,20f,200f,.1f)) }
    @Test fun invalidPointerAndCanvasCannotAcquireCoordinates() { assertNull(ReaderDisplayGeometry.toCanvas(Float.NaN,200f,0f,0f,.1f)); assertNull(ReaderDisplayGeometry.toCanvas(100f,200f,Float.NaN,0f,.1f)); assertNull(ReaderDisplayGeometry.toCanvas(0f,200f,0f,0f,.1f)) }
    @Test fun splitVisibleRangeUsesInverseCropCoordinates() { val a=ReaderDisplayGeometry.visible(100f,200f,.1f,.25f); assertEquals(10f,a.left,.001f); assertEquals(30f,a.right,.001f); assertEquals(20f,a.top,.001f); assertEquals(180f,a.bottom,.001f) }
    @Test fun zeroSplitRetainsNoTranslatedHitArea() { val c=capture(); c.painted(100f,200f,ReaderDisplayGeometry.visible(100f,200f,0f,0f)); assertNull(c.hit(ReaderBubbleCanvasFrame(0f,0f,100f,200f),40f,60f)) }
    @Test fun croppedPaintAndInverseTapSelectTheExactNativeIndex() { val c=capture(); c.painted(100f,200f,ReaderDisplayGeometry.visible(100f,200f,.1f)); val hit=c.hit(ReaderBubbleCanvasFrame(80f,10f,100f,200f),117.5f,60f,.1f); assertEquals(37,hit?.pageIndex); assertEquals(5,hit?.letteringIndex); assertSame(native,hit?.expectedNative) }
    @Test fun unpaintedSideOfSplitCannotOpenUnderlyingNativeLettering() { val c=capture(); c.painted(100f,200f,ReaderDisplayGeometry.visible(100f,200f,0f,.3f)); assertNull(c.hit(ReaderBubbleCanvasFrame(0f,0f,100f,200f),40f,60f)); assertNotNull(c.hit(ReaderBubbleCanvasFrame(0f,0f,100f,200f),25f,60f)) }
    @Test fun sideBySideTapOnOriginalCannotUseTranslatedFrame() { val c=capture(); c.painted(100f,200f); assertNull(c.hit(ReaderBubbleCanvasFrame(100f,0f,100f,200f),40f,60f)); assertNotNull(c.hit(ReaderBubbleCanvasFrame(100f,0f,100f,200f),140f,60f)) }
    @Test fun changedActualDrawSizeRequiresItsOwnPaint() { val c=capture(); c.painted(100f,200f); assertNull(c.hit(ReaderBubbleCanvasFrame(0f,0f,200f,400f),80f,120f,.1f)) }
    @Test fun invalidVisiblePaintRetiresPreviousNativeTargets() { val c=capture(); c.painted(100f,200f); c.painted(100f,200f,ReaderDisplayVisibleArea(-1f,0f,100f,200f)); assertNull(c.hit(ReaderBubbleCanvasFrame(0f,0f,100f,200f),40f,60f)) }
    @Test fun inverseDisplayCannotAlterOriginalBoundsOrSavedText() { val before=native.copy(); val c=capture(); c.painted(100f,200f,ReaderDisplayGeometry.visible(100f,200f,.15f)); c.hit(ReaderBubbleCanvasFrame(0f,0f,100f,200f),40f,60f,.15f); assertEquals(before,native) }
}
