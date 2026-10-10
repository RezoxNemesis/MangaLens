package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

/** Shared-store behavior, not a browser-only helper: this mode must never become audio proof. */
class BrowserCaptionDocumentModeTest {
    private fun source(f:CaptionFixture)=f.source.copy(source=f.source.source.copy(uri=f.page,
        headers=emptyMap(),captionDocumentOnly=true))

    private fun acceptedStart(store:SubtitleGenerationStore, requested:SubtitleSourceIdentity,
        config:SubtitleGenerationConfig):SubtitleGenerationTask {
        val operation=store.browserCaptionAuthority.begin(BrowserCaptionAuthorityKey(
            requireNotNull(requested.source.sourceResolutionId),requested.fingerprint,config.fingerprint()))
        return store.startBrowser(requested,config,operation)
    }
    @Test fun captionDocumentModeCannotAdmitWhisper() { CaptionFixture().use { f ->
        val store=f.store()
        // Each request has a live matching operation. Failure must be the mode policy, rather
        // than merely rejecting a missing browser operation before testing that policy.
        assertThrows(IllegalArgumentException::class.java) { acceptedStart(store,source(f), SubtitleGenerationConfig()) }
    } }
    @Test fun captionDocumentModeCannotAdmitANewlyInstalledAudioModel() { CaptionFixture().use { f ->
        assertThrows(IllegalArgumentException::class.java) { acceptedStart(f.store(),source(f),f.config.copy(modelSha256="b".repeat(64))) }
    } }
    @Test fun captionDocumentModeCannotInventAnInventoryForItsAcceptedPage() { CaptionFixture().use { f ->
        assertThrows(IllegalArgumentException::class.java) { acceptedStart(f.store(),source(f).copy(source=source(f).source.copy(providerCaptions=null)),f.config) }
    } }
    @Test fun captionDocumentModeCannotReplaceItsAcceptedPageWithAMediaUrl() { CaptionFixture().use { f ->
        assertThrows(IllegalArgumentException::class.java) { acceptedStart(f.store(),source(f).copy(source=source(f).source.copy(uri="https://video.fixture.invalid/media")),f.config) }
    } }
    @Test fun captionDocumentModeRejectsPcmWindowsBeforeTheyEnterTheDurableJournal()=BrowserCaptionStoreFixture().use { f ->
        val store=f.store();val task=f.start(store,f.operation(store))
        assertThrows(IllegalArgumentException::class.java) {
            store.checkpoint(task.id,task.generation,SubtitleWindow(0,0,8000,"b".repeat(64),
                sourceCues=listOf(SpeechCue(1000,3000,"Do not open the door.")),detectedLanguage="en"),8000,"en")
        }
        assertTrue(requireNotNull(store.get(task.id)).windows.isEmpty())
        assertFalse(requireNotNull(store.get(task.id)).audioComplete)
    }
    @Test fun actualProviderDocumentModeSurvivesReopenWithoutRestoringLiveAuthority()=BrowserCaptionStoreFixture().use { f ->
        val store=f.store();val task=f.start(store,f.operation(store));val document=f.document(store,task)
        val receipt=requireNotNull(document.providerCaptionReceipt)
        document.windows.forEach { window -> window.sourceCues.forEachIndexed { index,cue ->
            assertTrue(store.checkpointProviderTarget(task.id,task.generation,window.index,receipt,SubtitleTranslatedCue(index,cue.text)))
        } }
        val complete=requireNotNull(store.finish(task.id,task.generation))
        assertTrue(complete.source.source.captionDocumentOnly)
        assertEquals(f.source.source.providerCaptions?.sourcePageUrl,complete.source.source.uri)
        assertFalse(complete.source.verifiable);assertFalse(complete.audioComplete)
        assertTrue(hasVerifiedProviderCaptions(complete));assertNull(complete.config.modelSha256)
        val reopened=f.store();val saved=requireNotNull(reopened.get(task.id))
        assertTrue(saved.source.source.captionDocumentOnly)
        assertTrue(saved.validationPending);assertFalse(saved.pcmValidationRequired)
        reopened.confirmValidated(saved.id,saved.generation,pcmVerified=true)
        assertTrue(requireNotNull(reopened.get(task.id)).validationPending)
        assertThrows(BrowserCaptionAuthorityRetired::class.java) {
            reopened.confirmProviderDocument(saved.id,saved.generation,receipt,saved.windows)
        }
        assertNull(reopened.exportVerified(saved.id,saved.generation))
        val restart=f.start(reopened,f.operation(reopened))
        assertNotEquals(saved.generation,restart.generation)
        assertEquals(saved.config,restart.config)
        val current=f.document(reopened,restart)
        val currentReceipt=requireNotNull(current.providerCaptionReceipt)
        current.windows.forEach { window -> window.sourceCues.forEachIndexed { index,cue ->
            assertTrue(reopened.checkpointProviderTarget(restart.id,restart.generation,window.index,currentReceipt,SubtitleTranslatedCue(index,cue.text)))
        } }
        val verified=requireNotNull(reopened.finish(restart.id,restart.generation))
        assertTrue(hasVerifiedProviderCaptions(verified));assertNotNull(reopened.exportVerified(restart.id,restart.generation))
    }
}
