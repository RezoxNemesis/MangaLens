package com.mangalens

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.model.ContentType
import com.mangalens.ui.MangaLensViewModel
import com.mangalens.ui.capturedVideoSelection
import com.mangalens.ui.video.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Root VM callbacks; source compiled here, device execution belongs to the parent gate. */
@RunWith(AndroidJUnit4::class)
class VideoPairPublicationIntegrationTest {
    private fun root(action: (MangaLensViewModel) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val models = ViewModelStore()
            try {
                val app = ApplicationProvider.getApplicationContext<Application>()
                action(ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(app))[MangaLensViewModel::class.java])
            } finally { models.clear() }
        }
    }

    @Test fun initialResolvedPairCapturesHeadersAndWaitsForItsOwnMeasuredDuration() = root { vm ->
        val video = mutableMapOf("Cookie" to "video-only"); val audio = mutableMapOf("Cookie" to "audio-only")
        vm.acceptResolvedVideo("https://v.example/video.mp4", video, "https://page.example/watch", "https://a.example/audio.m4a", audio)
        video["Cookie"] = "changed"; audio["Cookie"] = "changed"
        val captured = vm.state.value.capturedVideoSelection()!!
        assertEquals("video-only", captured.videoHeaders["Cookie"]); assertEquals("audio-only", captured.audioHeaders["Cookie"])
        assertNull(captured.toOrezSelection())
        vm.acceptVideoReady(VideoReadyObservation(captured, 8_000_000))
        val selected = vm.state.value.capturedVideoSelection()!!.toOrezSelection()!!
        assertEquals(selected.resolutionId, selected.audio!!.resolutionId)
        assertEquals(8_000_000L, selected.expectedDurationUs)
        assertEquals("audio-only", selected.audio!!.headers["Cookie"])
    }

    @Test fun stalePlayerCallbackCannotPublishAfterAUnrelatedOpenOrModeChange() = root { vm ->
        vm.acceptResolvedVideo("https://old.example/v", emptyMap(), "https://page.example/watch", "https://old.example/a", emptyMap())
        val old = vm.state.value.capturedVideoSelection()!!
        val replacement = VideoPlaybackPublication.capture("https://new.example/v", emptyMap(), old.pageUrl, "https://new.example/a", emptyMap())
        vm.setUrl("https://unrelated.example/")
        var commits = 0
        assertFalse(vm.acceptVideoRefresh(old, replacement) { commits++; true })
        vm.acceptVideoReady(VideoReadyObservation(old, 8_000_000))
        assertEquals(0, commits); assertNull(vm.state.value.videoResolutionId); assertNull(vm.state.value.videoAudioResolutionId)
        vm.acceptResolvedVideo("https://next.example/v", emptyMap(), "https://page.example/next", null, emptyMap())
        vm.setMode(ContentType.IMAGE_CHAPTER)
        assertNull(vm.state.value.videoUrl); assertNull(vm.state.value.videoExpectedDurationUs)
    }

    @Test fun rejectedPlaybackCommitPreservesPairAndAcceptedRefreshRotatesItsWholeIdentity() = root { vm ->
        vm.acceptResolvedVideo("https://old.example/v", mapOf("Cookie" to "old-v"), "https://page.example/watch", "https://old.example/a", mapOf("Cookie" to "old-a"))
        val old = vm.state.value.capturedVideoSelection()!!
        vm.acceptVideoReady(VideoReadyObservation(old, 8_000_000))
        val replacement = VideoPlaybackPublication.capture("https://new.example/v", mapOf("Cookie" to "new-v"), old.pageUrl,
            "https://new.example/a", mapOf("Cookie" to "new-a"))
        assertFalse(vm.acceptVideoRefresh(old, replacement) { false })
        assertEquals(old.resolutionId, vm.state.value.videoResolutionId)
        assertTrue(vm.acceptVideoRefresh(old, replacement) { true })
        val current = vm.state.value.capturedVideoSelection()!!
        assertEquals(replacement, current); assertNotEquals(old.resolutionId, current.resolutionId)
        assertNull(current.durationUs); assertEquals("new-a", current.audioHeaders["Cookie"])
        vm.acceptVideoReady(VideoReadyObservation(old, 8_000_000))
        assertNull(vm.state.value.videoExpectedDurationUs)
    }

    @Test fun newerRootIntentDuringCommitIsNeverOverwrittenByTheOldRefresh() = root { vm ->
        vm.acceptResolvedVideo("https://old.example/v", emptyMap(), "https://page.example/watch", null, emptyMap())
        val old = vm.state.value.capturedVideoSelection()!!
        val replacement = VideoPlaybackPublication.capture("https://new.example/v", emptyMap(), old.pageUrl, null, emptyMap())
        assertFalse(vm.acceptVideoRefresh(old, replacement) { vm.setUrl("https://new-user-intent.example/"); true })
        assertEquals("https://new-user-intent.example/", vm.state.value.url)
        assertNull(vm.state.value.videoUrl)
    }
}
