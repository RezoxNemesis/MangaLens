package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class PlaybackContinuationFenceTest {
    @Test fun staleVmRevisionCannotReplacePlayerPublishDescriptorsOrRestartAnotherSource() {
        val requests = PlaybackRefreshRequests()
        val request = requests.begin()
        val effects = mutableListOf<String>()
        val stream = PlaybackStreamIdentity("https://fixture.example/current")

        val accepted = requests.publish(request, stream, stream,
            apply = { effects += "descriptors" },
            restartUnchanged = { effects += "restart" },
            beforeApply = { effects += "revision check"; false })

        assertFalse(accepted)
        assertEquals(listOf("revision check"), effects)
    }

    @Test fun cancellationFenceRunsBeforeTheNativeReplacementCallback() {
        val requests = PlaybackRefreshRequests()
        val request = requests.begin()
        requests.cancel()
        val effects = mutableListOf<String>()
        val stream = PlaybackStreamIdentity("https://fixture.example/current")

        assertFalse(requests.publish(request, stream, stream,
            apply = { effects += "descriptors" },
            restartUnchanged = { effects += "restart" },
            beforeApply = { effects += "native replacement"; true }))
        assertTrue(effects.isEmpty())
    }

    @Test fun validSameIdentityRefreshChecksReplacementBeforePublishingAndRestarting() {
        val requests = PlaybackRefreshRequests()
        val request = requests.begin()
        val effects = mutableListOf<String>()
        val stream = PlaybackStreamIdentity("https://fixture.example/current")

        assertTrue(requests.publish(request, stream, stream,
            apply = { effects += "descriptors" },
            restartUnchanged = { effects += "restart" },
            beforeApply = { effects += "accepted replacement"; true }))
        assertEquals(listOf("accepted replacement", "descriptors", "restart"), effects)
    }

    @Test fun replacementCallbackCannotPublishAfterAnotherRequestTakesOwnership() {
        val requests = PlaybackRefreshRequests()
        val request = requests.begin()
        val effects = mutableListOf<String>()
        val stream = PlaybackStreamIdentity("https://fixture.example/current")

        assertFalse(requests.publish(request, stream, stream,
            apply = { effects += "descriptors" },
            restartUnchanged = { effects += "restart" },
            beforeApply = { requests.begin(); true }))
        assertTrue(effects.isEmpty())
    }
}
