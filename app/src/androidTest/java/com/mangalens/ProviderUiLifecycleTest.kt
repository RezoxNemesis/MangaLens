package com.mangalens

import org.junit.Assert.*
import org.junit.Test

class ProviderUiLifecycleTest {
    @Test fun acceptedLaunchWaitsForResumedInsteadOfReadingAnEmptyGlobalList() {
        val owner = ProviderUiLifecycle<Any>()
        val epoch = owner.beginLaunch()
        assertNull(owner.resumed())
        val activity = Any()
        assertFalse(owner.observe(activity, epoch, ProviderUiLifecycle.State.CREATED))
        assertNull(owner.resumed())
        owner.observe(activity, epoch, ProviderUiLifecycle.State.RESUMED)
        assertSame(activity, owner.resumed())
    }

    @Test fun oldResumedActivityCannotSupplyTheNewLaunchState() {
        val owner = ProviderUiLifecycle<Any>()
        val first = Any()
        owner.observe(first, owner.beginLaunch(), ProviderUiLifecycle.State.RESUMED)
        val next = owner.beginLaunch()
        assertNull(owner.resumed())
        assertEquals(listOf(first), owner.retired())
        val second = Any()
        owner.observe(second, next, ProviderUiLifecycle.State.RESUMED)
        assertSame(second, owner.resumed())
    }

    @Test fun cleanupOwnsStartedAndPausedActivitiesAndNotOnlyTheResumedOne() {
        val owner = ProviderUiLifecycle<Any>()
        val first = Any()
        owner.observe(first, owner.beginLaunch(), ProviderUiLifecycle.State.PAUSED)
        val second = Any()
        owner.observe(second, owner.beginLaunch(), ProviderUiLifecycle.State.STARTED)
        assertEquals(setOf(first, second), owner.close().toSet())
        assertNull(owner.resumed())
        assertFalse(owner.canDetach())
        owner.observe(first, 1, ProviderUiLifecycle.State.DESTROYED)
        owner.observe(second, 2, ProviderUiLifecycle.State.DESTROYED)
        assertTrue(owner.canDetach())
    }

    @Test fun closeBeforeCreationKeepsTheListenerAndFinishesTheLateOwnedActivity() {
        val owner = ProviderUiLifecycle<Any>()
        val epoch = owner.beginLaunch()
        assertTrue(owner.close().isEmpty())
        assertFalse(owner.canDetach())
        val late = Any()
        assertTrue(owner.observe(late, epoch, ProviderUiLifecycle.State.CREATED))
        assertNull(owner.resumed())
        assertFalse(owner.canDetach())
        owner.observe(late, epoch, ProviderUiLifecycle.State.DESTROYED)
        assertTrue(owner.canDetach())
    }

    @Test fun staleCallbackCannotReclaimNewActivityAndDuplicateResumeDoesNotThrow() {
        val owner = ProviderUiLifecycle<Any>()
        val firstEpoch = owner.beginLaunch()
        val secondEpoch = owner.beginLaunch()
        val second = Any()
        owner.observe(second, secondEpoch, ProviderUiLifecycle.State.RESUMED)
        assertTrue(owner.observe(Any(), firstEpoch, ProviderUiLifecycle.State.RESUMED))
        assertSame(second, owner.resumed())
        owner.observe(second, secondEpoch, ProviderUiLifecycle.State.RESUMED)
        assertSame(second, owner.resumed())
    }

    @Test fun currentRecreationUsesTheReplacementOnlyOnceItResumes() {
        val owner = ProviderUiLifecycle<Any>()
        val epoch = owner.beginLaunch()
        val old = Any()
        owner.observe(old, epoch, ProviderUiLifecycle.State.RESUMED)
        owner.observe(old, epoch, ProviderUiLifecycle.State.PAUSED)
        val replacement = Any()
        owner.observe(replacement, epoch, ProviderUiLifecycle.State.CREATED)
        assertNull(owner.resumed())
        owner.observe(old, epoch, ProviderUiLifecycle.State.DESTROYED)
        owner.observe(replacement, epoch, ProviderUiLifecycle.State.RESUMED)
        assertSame(replacement, owner.resumed())
    }

    @Test fun synchronousDispatchFailureReleasesThePendingListenerOnClose() {
        val owner = ProviderUiLifecycle<Any>()
        val epoch = owner.beginLaunch()
        owner.launchFailed(epoch)
        owner.close()
        assertTrue(owner.canDetach())
    }
}
