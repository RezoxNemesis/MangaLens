package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class PlaybackLifecyclePolicyTest {
    private val id = "a".repeat(32)

    @Test fun defaultActivityStopPausesButConfigurationChangeDoesNot() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        assertFalse(state.stopPresentation(ui, changingConfiguration = true))
        assertTrue(state.stopPresentation(ui, changingConfiguration = false))
    }

    @Test fun backgroundNeedsActualServiceAcknowledgmentAndSurvivesViewModelClear() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        assertTrue(state.requestBackground(ui, true))
        assertTrue(state.stopPresentation(ui, false))
        assertFalse(state.backgroundActive)
        assertTrue(state.acknowledgeService(id))
        assertFalse(state.stopPresentation(ui, false))
        state.detachPresentation(ui); state.removeClient()
        assertFalse(state.canRelease)
        state.serviceStopped(id)
        assertTrue(state.canRelease)
    }

    @Test fun anOldPresentationCannotPauseOrReopenTheReplacement() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val first = state.attachPresentation(); state.acceptSource(first)
        val second = state.attachPresentation(); assertTrue(state.acceptSource(second))
        val revision = state.sourceRevision
        assertFalse(state.stopPresentation(first, false))
        assertFalse(state.detachPresentation(first))
        assertFalse(state.acceptSource(first))
        assertFalse(state.requestBackground(first, true))
        assertEquals(revision, state.sourceRevision)
        assertTrue(state.ownsPresentation(second))
    }

    @Test fun staleNotificationAndAnotherSessionCannotControlCurrentPlayback() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        state.requestBackground(ui, true); state.acknowledgeService(id)
        val old = state.controlReceipt()
        assertTrue(state.acceptsControl(old))
        state.acceptSource(ui)
        assertFalse(state.acceptsControl(old))
        assertFalse(state.acceptsControl(PlaybackControlReceipt("b".repeat(32), state.sourceRevision)))
        assertTrue(state.acceptsControl(state.controlReceipt()))
    }

    @Test fun pipRetainsSameSessionAndClosingItHonorsExplicitBackground() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        assertTrue(state.setPictureInPicture(ui, true))
        assertFalse(state.stopPresentation(ui, false))
        assertTrue(state.setPictureInPicture(ui, false))
        assertTrue(state.shouldPause)
        state.requestBackground(ui, true); state.acknowledgeService(id)
        assertTrue(state.setPictureInPicture(ui, true))
        assertTrue(state.setPictureInPicture(ui, false))
        assertFalse(state.shouldPause)
    }

    @Test fun lateServiceStartAfterDisableDoesNotReviveAudio() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        state.requestBackground(ui, true)
        state.requestBackground(ui, false)
        assertFalse(state.acknowledgeService(id))
        assertTrue(state.stopPresentation(ui, false))
    }

    @Test fun serviceFailureAndStopRemoveRetentionAndNotificationAuthority() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        state.requestBackground(ui, true); state.acknowledgeService(id)
        val receipt = state.controlReceipt()
        state.stopBackground(id)
        assertFalse(state.backgroundActive)
        assertFalse(state.acceptsControl(receipt))
        assertTrue(state.stopPresentation(ui, false))
        state.detachPresentation(ui); state.removeClient()
        state.serviceStopped(id)
        assertTrue(state.canRelease)
    }

    @Test fun rotationDetachesOnlyTheOldViewWithoutPausingTheSamePlayer() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        assertFalse(state.detachPresentation(ui, changingConfiguration = true))
        assertFalse(state.shouldPause)
        val newUi = state.attachPresentation()
        assertFalse(state.ownsPresentation(ui))
        assertTrue(state.ownsPresentation(newUi))
        assertEquals(1L, state.sourceRevision)
    }

    @Test fun acceptedPendingBackgroundRetainsThePlayerAcrossScreenCloseUntilStartFails() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        state.requestBackground(ui, true)
        state.detachPresentation(ui); state.removeClient()
        assertFalse("An accepted start must keep its exact player until service acknowledgment or failure", state.canRelease)
        state.stopBackground(id)
        assertTrue(state.canRelease)
    }

    @Test fun oldServiceShutdownCannotClearTheNewEnableOrAcknowledgeItsStart() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        state.requestBackground(ui, true); val first = state.backgroundEpoch
        assertTrue(state.acknowledgeService(id, first))
        state.requestBackground(ui, false)
        state.requestBackground(ui, true); val second = state.backgroundEpoch
        assertTrue(second > first)
        state.serviceStopped(id, first)
        assertTrue("Old service cleanup must preserve the new accepted start", state.backgroundRequested)
        assertFalse(state.acknowledgeService(id, first))
        assertTrue(state.acknowledgeService(id, second))
        assertTrue(state.backgroundActive)
    }

    @Test fun queuedControlsFromTheReleasedMediaSessionCannotSeekOrResumeANewerServiceRequest() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        state.requestBackground(ui, true); val oldEpoch = state.backgroundEpoch
        state.acknowledgeService(id, oldEpoch)
        val receipt = state.controlReceipt()
        state.requestBackground(ui, false); state.requestBackground(ui, true)
        state.acknowledgeService(id, state.backgroundEpoch)
        assertFalse(state.acceptsServiceControl(receipt, oldEpoch))
        assertTrue(state.acceptsServiceControl(receipt, state.backgroundEpoch))
    }

    @Test fun delayedServiceStartResumesOnlyTheStopItActuallyPaused() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        state.requestBackground(ui, true); val epoch = state.backgroundEpoch
        assertTrue(state.stopPresentation(ui, false))
        state.retainBackgroundContinuation()
        state.acknowledgeService(id, epoch)
        assertTrue(state.consumeBackgroundContinuation(id, epoch))
        assertFalse(state.consumeBackgroundContinuation(id, epoch))
    }

    @Test fun userReturnsBeforeTheDelayedServiceStartsAndTheirPausedStateIsRetained() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        state.requestBackground(ui, true); val epoch = state.backgroundEpoch
        state.stopPresentation(ui, false); state.retainBackgroundContinuation()
        state.resumePresentation(ui)
        state.acknowledgeService(id, epoch)
        assertFalse("Delayed acknowledgment must not override a foreground user's pause", state.consumeBackgroundContinuation(id, epoch))
    }

    @Test fun pendingAutomaticResumeIsRetiredBySourceReplacementAndNewBackgroundIntent() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        state.requestBackground(ui, true); state.stopPresentation(ui, false); state.retainBackgroundContinuation()
        val epoch = state.backgroundEpoch
        state.acceptSource(ui)
        state.acknowledgeService(id, epoch)
        assertFalse(state.consumeBackgroundContinuation(id, epoch))
        state.requestBackground(ui, true); state.retainBackgroundContinuation()
        state.requestBackground(ui, false); state.requestBackground(ui, true)
        state.acknowledgeService(id, state.backgroundEpoch)
        assertFalse(state.consumeBackgroundContinuation(id, state.backgroundEpoch))
    }

    @Test fun disablingBackgroundRetiresCommandsButWaitsForActualServiceDetachBeforeReleasingThePlayer() {
        val state = PlaybackLifecyclePolicy(id)
        state.addClient(); val ui = state.attachPresentation(); state.acceptSource(ui)
        state.requestBackground(ui, true); val service = state.backgroundEpoch
        state.acknowledgeService(id, service)
        state.requestBackground(ui, false)
        state.detachPresentation(ui); state.removeClient()
        assertFalse(state.backgroundActive)
        assertFalse(state.acceptsControl(state.controlReceipt()))
        assertFalse("The actual service still has its MediaSession/listener until onDestroy detaches", state.canRelease)
        state.serviceStopped(id, service)
        assertTrue(state.canRelease)
    }
}
