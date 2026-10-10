package com.mangalens.ui.reader
import org.junit.Assert.*
import org.junit.Test
/** Authored UNRUN. Captured requests remain authoritative during new display layouts. */
class ReaderDisplayRestorationTest {
    @Test fun continuousHorizontalRetainsItsPixelOffsetOnColdInitialState() { val s=ReaderPositionState.initial("horizontal",7,83); assertEquals("horizontal",s.mode); assertEquals(83,s.offset); assertTrue(s.restoring) }
    @Test fun newSingleAndSpreadDoNotRetainScrollOffsets() { for (m in listOf("single","spread")) assertEquals(0,ReaderPositionState.initial(m,4,99).offset) }
    @Test fun pendingNextCannotBeReplacedByOldPhysicalPageDuringCropChange() { val s=ReaderPositionState.initial("vertical",2,17).navigate(7,10); assertEquals(7,s.relayout(2,17,10).page) }
    @Test fun oldRestoreCannotFinishAfterExplicitComparisonRelayout() { val old=ReaderPositionState.initial("ltr",4,0); val next=old.relayout(4,0,10); assertEquals(next,next.restored(old,0,0,10)) }
    @Test fun settledHorizontalRelayoutPreservesActualOffsetAndRejectsOldObserver() { val old=ReaderPositionState.initial("horizontal",4,0); val settled=old.restored(old,4,51,10); val next=settled.relayout(4,51,10); assertEquals(51,next.offset); assertEquals(next,next.observed(settled.request,"horizontal",0,0,10)) }
    @Test fun widthChangeFromSpreadToPortraitRetainsSecondMember() { val old=ReaderPositionState.initial("spread",7,0); val wide=ReaderPageLayout("spread",10,true); val restored=old.restored(old,wide.logicalForPhysical(3,old.page),0,10); val narrow=restored.relayout(restored.page,0,10); assertEquals(7,narrow.page); assertEquals(7,ReaderPageLayout("spread",10,false).physicalForLogical(narrow.page)) }
    @Test fun modeChangeCancelsDisplayRestoreAndKeepsLogicalSelection() { val old=ReaderPositionState.initial("spread",7,0); val changed=old.switchMode("horizontal",6,0,10); assertEquals(7,changed.page); assertEquals(changed,changed.restored(old,6,0,10)) }
    @Test fun displayRequestDoesNotPublishCheckpointBeforeActualLayout() { val old=ReaderPositionState.initial("vertical",4,0); val settled=old.restored(old,4,41,10); val sample=settled.checkpoint()!!; val next=settled.relayout(4,41,10); assertNull(next.checkpoint()); assertFalse(next.acceptsCheckpoint(sample)) }
}
