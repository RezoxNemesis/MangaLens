package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class PlaybackMutationGuardTest {
    @Test fun aReentrantSourceReplacementPreventsOldPreparePauseSpeedAndDescriptorWrites() {
        var owned = true
        var speed = 2f
        var playing = true
        var source = "old"
        var prepareCount = 0
        val accepted = mutatePlaybackWhileOwned({ owned }, listOf(
            { source = "replacement"; owned = false },
            { speed = 1.25f }, { prepareCount++ }, { playing = false }, { source = "caption-old" }
        ))
        assertFalse(accepted)
        assertEquals("replacement", source)
        assertEquals(2f, speed)
        assertTrue(playing)
        assertEquals(0, prepareCount)
    }

    @Test fun laterPickerSelectionCanInvalidateAnAttachmentWithoutMutatingTheOldPlayer() {
        var mutations = 0
        assertFalse(mutatePlaybackWhileOwned({ false }, listOf({ mutations++ })))
        assertEquals(0, mutations)
    }

    @Test fun lastCallbackReplacementIsRejectedAndSuccessfulActionsPreserveOrder() {
        val actions = mutableListOf<String>()
        var owned = true
        assertFalse(mutatePlaybackWhileOwned({ owned }, listOf({ actions += "source" }, { actions += "prepare"; owned = false })))
        assertEquals(listOf("source", "prepare"), actions)
        owned = true
        assertTrue(mutatePlaybackWhileOwned({ owned }, listOf({ actions += "speed" }, { actions += "paused" })))
        assertEquals(listOf("source", "prepare", "speed", "paused"), actions)
    }

    @Test fun pausedRefreshRetainsTheIntentCapturedWithItsLatePositionAndSpeed() {
        var playing = false
        var position = 7300L
        var speed = 1.5f
        val capturedPlaying = playing
        // Resolver work finished before this player-thread capture. Source replacement
        // and prepare must not convert the current user's pause into an implicit Play.
        assertTrue(preparePlaybackReplacementWhileOwned({ true }, 4L, capturedPlaying,
            replaceSource = { position = 7300L }, prepare = { speed = 1.5f },
            setPlayWhenReady = { playing = it }))
        assertFalse(playing)
        assertEquals(7300L, position)
        assertEquals(1.5f, speed)
    }

    @Test fun playingRefreshRestoresItsCapturedIntentAfterPreparation() {
        var playing = true
        val captured = playing
        assertTrue(preparePlaybackReplacementWhileOwned({ true }, 4L, captured,
            replaceSource = { playing = false }, prepare = {}, setPlayWhenReady = { playing = it }))
        assertTrue(playing)
    }

    @Test fun aFreshExplicitOpenStartsPlaybackAfterAPreviousPausedSource() {
        var playing = false
        assertTrue(preparePlaybackReplacementWhileOwned({ true }, null, playing,
            replaceSource = {}, prepare = {}, setPlayWhenReady = { playing = it }))
        assertTrue(playing)
    }

    @Test fun reentrantPrepareCannotPublishTheOldPausedIntentIntoANewOwner() {
        var owned = true
        var playing = true
        var playWrites = 0
        assertFalse(preparePlaybackReplacementWhileOwned({ owned }, 4L, false,
            replaceSource = {}, prepare = { owned = false },
            setPlayWhenReady = { playWrites++; playing = it }))
        assertEquals(0, playWrites)
        assertTrue(playing)
    }

    @Test fun alreadySupersededRefreshCannotTouchSourcePrepareOrPlayIntent() {
        val mutations = mutableListOf<String>()
        assertFalse(preparePlaybackReplacementWhileOwned({ false }, 4L, false,
            replaceSource = { mutations += "source" }, prepare = { mutations += "prepare" },
            setPlayWhenReady = { mutations += "play" }))
        assertTrue(mutations.isEmpty())
    }
}
