package com.mangalens.ui.reader
import org.junit.Assert.*
import org.junit.Test
/** Authored UNRUN. Ordinals are independent of native indices and source URLs. */
class ReaderPageLayoutTest {
    @Test fun oldModesMapEveryOrdinalToOnePhysicalPage() { for (mode in listOf("vertical", "ltr", "rtl", "single", "horizontal")) { val l=ReaderPageLayout(mode,5,true); assertEquals(1,l.columns); for (p in 0..4) { assertEquals(p,l.physicalForLogical(p)); assertEquals(p,l.logicalForPhysical(p,0)) } } }
    @Test fun oldRtlRetainsPhysicalReversal() { assertTrue(ReaderPageLayout("rtl",3,false).reversed); assertFalse(ReaderPageLayout("ltr",3,false).reversed) }
    @Test fun portraitSpreadFallsBackToOneCompletePage() { val l=ReaderPageLayout("spread",5,false); assertEquals(1,l.columns); assertEquals(listOf(3),l.ordinals(3)) }
    @Test fun wideSpreadGroupsOrdinalsIncludingOddTail() { val l=ReaderPageLayout("spread",5,true); assertEquals(3,l.physicalCount); assertEquals(listOf(0,1),l.ordinals(0)); assertEquals(listOf(4),l.ordinals(2)) }
    @Test fun pendingSecondMemberRemainsTheAcceptedLogicalDestination() { val l=ReaderPageLayout("spread",10,true); assertEquals(3,l.physicalForLogical(7)); assertEquals(7,l.logicalForPhysical(3,7)) }
    @Test fun gestureToAnotherSpreadChoosesItsFirstLogicalMember() { assertEquals(8,ReaderPageLayout("spread",10,true).logicalForPhysical(4,7)) }
    @Test fun rightToLeftSpreadReversesMembersAndPhysicalGestures() { val l=ReaderPageLayout("spread",5,true,true); assertTrue(l.reversed); assertEquals(listOf(3,2),l.ordinals(1)); assertEquals(listOf(4),l.ordinals(2)) }
    @Test fun spreadNextAndPreviousAdvanceWholeGroups() { val l=ReaderPageLayout("spread",6,true); assertEquals(3,l.advance(1,1)); assertEquals(1,l.advance(3,-1)); assertFalse(l.canAdvance(5,1)); assertFalse(l.canAdvance(0,-1)) }
    @Test fun oldNextAndPreviousRemainOneOrdinal() { val l=ReaderPageLayout("rtl",3,true); assertEquals(2,l.advance(1,1)); assertEquals(0,l.advance(1,-1)) }
    @Test fun emptyChapterNeverCreatesPhantomMembers() { val l=ReaderPageLayout("spread",0,true); assertEquals(0,l.physicalCount); assertTrue(l.ordinals(0).isEmpty()); assertFalse(l.canAdvance(0,1)) }
    @Test fun invalidPhysicalGroupsCannotEnumerateNeighbours() { val l=ReaderPageLayout("spread",3,true); assertTrue(l.ordinals(-1).isEmpty()); assertTrue(l.ordinals(2).isEmpty()) }
    @Test fun countAndHugeNavigationCannotOverflow() { val l=ReaderPageLayout("spread",Int.MAX_VALUE,true); assertEquals(1073741824,l.physicalCount); assertEquals(Int.MAX_VALUE-1,l.advance(0,Int.MAX_VALUE)); assertEquals(0,l.advance(4,Int.MIN_VALUE)) }
}
