package com.mangalens.ui.reader
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.mangalens.core.reader.ChapterPage
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. These receipts are UI data, never native/source crop credentials. */
class ReaderGuidedSessionTest {
    private val sha="a".repeat(64)
    private val owner=ReaderGuidedOwner("chapter",37,"/managed/original.png",sha+":incarnation",9L,false)
    private val page=ChapterPage(37,"content://same-document",owner.sourcePath,contentRevision=owner.contentRevision)
    private val panels=listOf(ReaderPanelRect(0f,0f,1f,.5f),ReaderPanelRect(0f,.5f,1f,1f))
    private fun session(index:Int=0)=ReaderGuidedSession(owner,mutableIntStateOf(index),mutableStateOf(false))
    private fun receipt()=ReaderPanelDetection(sha,320,480,panels)
    @Test fun beforeActualReceiptTheViewIsWholePage() { assertEquals(ReaderGuidedViewPolicy.WHOLE_PAGE,session().panelFor(page,9L)) }
    @Test fun actualReceiptSelectsItsOriginalNormalizedPanel() { val s=session();s.accept(s.detectionTicket()!!,receipt(),false);assertEquals(panels[0],s.panelFor(page,9L));assertFalse(s.busy) }
    @Test fun changedPageIndexCannotConsumeAnotherOriginalImageProposal() { val s=session();s.accept(s.detectionTicket()!!,receipt(),false);assertEquals(ReaderGuidedViewPolicy.WHOLE_PAGE,s.panelFor(page.copy(index=42),9L)) }
    @Test fun repairedSameBytesWithNewIncarnationRetiresOldViewGeometry() { val s=session();s.accept(s.detectionTicket()!!,receipt(),false);assertEquals(ReaderGuidedViewPolicy.WHOLE_PAGE,s.panelFor(page.copy(contentRevision=sha+":new-incarnation"),9L)) }
    @Test fun changedManagedPathRetiresOldViewGeometry() { val s=session();s.accept(s.detectionTicket()!!,receipt(),false);assertEquals(ReaderGuidedViewPolicy.WHOLE_PAGE,s.panelFor(page.copy(localPath="/managed/replacement.png"),9L)) }
    @Test fun changedPresentationEpochCannotReuseG1View() { val s=session();s.accept(s.detectionTicket()!!,receipt(),false);assertEquals(ReaderGuidedViewPolicy.WHOLE_PAGE,s.panelFor(page,10L)) }
    @Test fun explicitWholePageRetainsSelectionForReturnToPanel() { val s=session();s.accept(s.detectionTicket()!!,receipt(),false);s.advance(1);s.showWhole();assertEquals(ReaderGuidedViewPolicy.WHOLE_PAGE,s.panelFor(page,9L));s.showPanel();assertEquals(panels[1],s.panelFor(page,9L)) }
    @Test fun previousPageCanStartAtItsActualLastDetectedPanel() { val s=session();s.accept(s.detectionTicket()!!,receipt(),true);assertEquals(1,s.panelIndex);assertEquals(panels[1],s.panelFor(page,9L)) }
    @Test fun restoredOutOfRangeIndexCannotInventAThirdPanel() { val s=session(99);s.accept(s.detectionTicket()!!,receipt(),false);assertEquals(1,s.panelIndex) }
    @Test fun failedOriginalDetectionRetiresPreviousPanelWithoutChangingOriginalPage() { val s=session();s.accept(s.detectionTicket()!!,receipt(),false);s.unavailable(s.detectionTicket()!!);assertTrue(s.failed);assertEquals(ReaderGuidedViewPolicy.WHOLE_PAGE,s.panelFor(page,9L));assertEquals(owner.sourcePath,page.localPath) }
    @Test fun backgroundRetirementRemovesTheReceiptUntilFreshResume() { val s=session();s.accept(s.detectionTicket()!!,receipt(),false);s.retire();assertNull(s.detection);assertTrue(s.busy);assertEquals(ReaderGuidedViewPolicy.WHOLE_PAGE,s.panelFor(page,9L));s.resume();s.accept(s.detectionTicket()!!,receipt(),false);assertEquals(panels[0],s.panelFor(page,9L)) }
    @Test fun mismatchedActualDigestCannotBeAcceptedAsTheCapturedRevision() { val s=session();assertTrue(runCatching{s.accept(s.detectionTicket()!!,receipt().copy(sourceSha256="b".repeat(64)),false)}.isFailure);assertNull(s.detection) }
    @Test fun invalidPanelListCannotEnterTheViewState() { val s=session();assertTrue(runCatching{s.accept(s.detectionTicket()!!,receipt().copy(panels=emptyList()),false)}.isFailure);assertNull(s.detection) }
    @Test fun rtlOwnerRetainsDetectorOrderWithoutSortingByProjectedLettering() { val o=owner.copy(rightToLeft=true);val s=ReaderGuidedSession(o,mutableIntStateOf(0),mutableStateOf(false));val reversed=receipt().copy(panels=panels.asReversed());s.accept(s.detectionTicket()!!,reversed,false);assertEquals(panels[1],s.panelFor(page,9L));s.advance(1);assertEquals(panels[0],s.panelFor(page,9L)) }
    @Test fun returningHeldResultCannotRepublishAfterSynchronousPauseRetirement() {
        val s=session();val old=s.detectionTicket()!!;s.retire()
        assertFalse(s.accept(old,receipt(),false));assertNull(s.detection);assertTrue(s.busy)
    }
    @Test fun returningHeldFailureCannotReplaceRetiredStateAfterPause() {
        val s=session();val old=s.detectionTicket()!!;s.retire()
        assertFalse(s.unavailable(old));assertFalse(s.failed);assertNull(s.detection)
    }
    @Test fun pauseResumeBeforeOldJobSettlesStillRejectsTheOldSuccess() {
        val s=session();val old=s.detectionTicket()!!;val revision=s.lifecycleRevision;s.retire();s.resume()
        assertNotEquals(revision,s.lifecycleRevision)
        val fresh=s.detectionTicket()!!;assertNotEquals(old,fresh)
        assertFalse(s.accept(old,receipt(),true));assertTrue(s.accept(fresh,receipt(),false));assertEquals(0,s.panelIndex)
    }
    @Test fun oldFailureCannotRemoveTheFreshResumeReceipt() {
        val s=session();val old=s.detectionTicket()!!;s.retire();s.resume();s.accept(s.detectionTicket()!!,receipt(),false)
        assertFalse(s.unavailable(old));assertEquals(panels[0],s.panelFor(page,9L));assertFalse(s.failed)
    }
}
