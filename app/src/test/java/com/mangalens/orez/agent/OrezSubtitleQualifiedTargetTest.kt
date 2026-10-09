package com.mangalens.orez.agent

import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.ui.video.SubtitleGenerationStore
import com.mangalens.ui.video.SubtitleJournalIo
import com.mangalens.ui.video.SubtitleMediaSource
import com.mangalens.ui.video.SubtitleOutputMode
import com.mangalens.ui.video.SubtitlePipeline
import com.mangalens.ui.video.SubtitleSourceIdentity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OrezSubtitleQualifiedTargetTest {
    private val selected = OrezMediaSelection("file:///explicit-original-speech.wav")
    private val context = OrezAgentContext(selectedMedia = selected,
        subtitleOptions = OrezSubtitleOptions(sourceLanguage = "en", threads = 2))

    private fun plan(request: String) = OrezAgentRuntime().decide(request, context).plan!!

    @Test fun actualAndroidRequestCapturesHinglishRatherThanDefaultEnglishBeforeDispatch() {
        val plan = plan("Generate Hinglish dual subtitles for this selected video")
        assertEquals(OrezTaskStatus.RUNNING, plan.status)
        val options = plan.authorization!!.subtitle!!
        assertEquals("hi-latn", options.targetLanguage)
        assertEquals(SubtitleOutputMode.DUAL, options.outputMode)
        assertEquals(SubtitlePipeline.SOURCE_TRANSLATION, options.pipeline)
        assertEquals("en", options.sourceLanguage)
        assertEquals(2, options.threads)
        assertEquals(TranslationStyleProfile.NATURAL, options.capturedStyle)
        assertEquals("hi-latn", plan.steps.last().call.arguments["targetLanguage"])
    }

    @Test fun explicitRomanHindiAliasesStillSelectTheirScriptBeforeADualOrBilingualQualifier() {
        for (alias in listOf("Hinglish", "Roman Hindi", "Hindi Latin", "Romanized Hindi", "Romanised Hindi", "hi-latn")) {
            for (qualifier in listOf("dual", "bilingual")) {
                val plan = plan("Create $alias $qualifier captions for this selected video")
                assertEquals("$alias $qualifier", OrezTaskStatus.RUNNING, plan.status)
                assertEquals("$alias $qualifier", "hi-latn", plan.authorization!!.subtitle!!.targetLanguage)
                assertEquals(SubtitleOutputMode.DUAL, plan.authorization.subtitle!!.outputMode)
            }
        }
    }

    @Test fun requestedHindiDualOutputDoesNotSilentlyRemainEnglish() {
        val plan = plan("Generate Hindi dual subtitles for this selected video")
        assertEquals("hi", plan.authorization!!.subtitle!!.targetLanguage)
        assertEquals(SubtitleOutputMode.DUAL, plan.authorization.subtitle!!.outputMode)
        assertEquals("hi", plan.steps.last().call.arguments["targetLanguage"])
    }

    @Test fun ownDownloadChainCarriesTheSameQualifiedTargetWithoutChangingItsSourceAuthority() {
        val url = "https://fixture.invalid/original.mp4"
        val plan = OrezAgentRuntime().decide("Download $url then generate Hinglish dual subtitles",
            OrezAgentContext(subtitleOptions = context.subtitleOptions)).plan!!
        assertEquals(OrezTaskStatus.RUNNING, plan.status)
        assertEquals("hi-latn", plan.authorization!!.subtitle!!.targetLanguage)
        assertEquals(SubtitleOutputMode.DUAL, plan.authorization.subtitle!!.outputMode)
        assertEquals(setOf(url), plan.authorization.urls)
        assertEquals(listOf("enqueue_download", "inspect_downloaded_media", "generate_subtitles"), plan.steps.map { it.call.name })
        assertEquals(OrezOutputReference(1, OrezOutputField.MEDIA_SOURCE_ID), plan.steps.last().references["sourceId"])
        assertEquals("hi-latn", plan.steps.last().call.arguments["targetLanguage"])
    }

    @Test fun actualOptionsMapperAndColdNativeJournalRetainTargetModeAndOwnedIdentity() {
        val root = Files.createTempDirectory("orez-qualified-target").toFile()
        fun store() = SubtitleGenerationStore(root, object : SubtitleJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
        })
        try {
            val plan = plan("Generate Hinglish dual subtitles for this selected video")
            val captured = plan.authorization!!.subtitle!!
            val config = captured.nativeConfig("b".repeat(64))
            val identity = SubtitleSourceIdentity(SubtitleMediaSource(selected.uri), "a".repeat(64))
            val owner = OrezDurablePlanRules.requestId(plan.id, 1)
            val native = store()
            val task = native.start(identity, config, ownerRequestId = owner, allowOwnerReplacement = false)
            native.pause(task.id, task.generation)
            val cold = store()
            val reopened = requireNotNull(cold.get(task.id))
            assertEquals("hi-latn", reopened.config.targetLanguage)
            assertEquals(SubtitleOutputMode.DUAL, reopened.config.outputMode)
            assertEquals(SubtitlePipeline.SOURCE_TRANSLATION, reopened.config.pipeline)
            assertEquals(captured, reopened.config.orezOptions())
            assertEquals(config.fingerprint(), reopened.config.fingerprint())
            assertEquals(owner, reopened.ownerRequestId)
            assertEquals(task.generation, cold.start(identity, config, ownerRequestId = owner, allowOwnerReplacement = false).generation)
            val english = cold.start(identity, OrezSubtitleOptions(sourceLanguage = "en", threads = 2).nativeConfig("b".repeat(64)))
            assertNotEquals("A Hinglish dual request must never share the English legacy config identity", task.id, english.id)
        } finally { root.deleteRecursively() }
    }

    @Test fun unsupportedExplicitQualifiedLanguageRemainsVisibleInsteadOfChangingToEnglish() {
        val plan = plan("Generate Japanese dual subtitles for this selected video")
        assertEquals(OrezTaskStatus.FAILED, plan.status)
        assertEquals("ja", plan.authorization!!.subtitle!!.targetLanguage)
        assertEquals("ja", plan.steps.last().call.arguments["targetLanguage"])
    }

    @Test fun sourceLabelDoesNotSupplyATargetWhenTheUserLeavesItsSettingsUnchanged() {
        val request = OrezAgentRuntime().decide("Generate dual subtitles for this selected video",
            context.copy(selectedMedia = selected.copy(label = "Hinglish dual subtitles"))).plan!!
        assertEquals("en", request.authorization!!.subtitle!!.targetLanguage)
        assertEquals(SubtitleOutputMode.DUAL, request.authorization.subtitle!!.outputMode)
    }

    @Test fun importedTextWithTheExactRequestStillCannotAuthorizeNativeSubtitleWork() {
        val result = OrezAgentRuntime().decide("Generate Hinglish dual subtitles for this selected video",
            context.copy(origin = OrezTrustOrigin.WEB_CONTENT, explicitUserRequest = false))
        assertEquals(OrezTaskStatus.FAILED, result.plan!!.status)
        assertFalse(result.continueToBrain)
    }

    @Test fun explicitEnglishDestinationPrecedesAHindiQualifiedCueDescription() {
        assertEquals("en", OrezSubtitleRequest.target("Translate Hindi dual subtitles to English for this selected video", "en"))
        val request = plan("Generate Hindi dual subtitles to English for this selected video")
        assertEquals("en", request.authorization!!.subtitle!!.targetLanguage)
        assertEquals("en", request.steps.last().call.arguments["targetLanguage"])
        assertEquals(SubtitleOutputMode.DUAL, request.authorization.subtitle!!.outputMode)
    }

    @Test fun explicitEnglishDestinationPrecedesARomanHindiQualifiedCueDescription() {
        assertEquals("en", OrezSubtitleRequest.target("Translate Hinglish bilingual captions into English", "en"))
        val request = plan("Generate Hinglish bilingual captions into English for this selected video")
        assertEquals("en", request.authorization!!.subtitle!!.targetLanguage)
        assertEquals("en", request.steps.last().call.arguments["targetLanguage"])
    }

    @Test fun explicitHinglishDestinationPrecedesEnglishOriginalCueDescription() {
        val request = plan("Generate English original subtitles into Hinglish for this selected video")
        assertEquals("hi-latn", request.authorization!!.subtitle!!.targetLanguage)
        assertEquals(SubtitlePipeline.SOURCE_TRANSLATION, request.authorization.subtitle!!.pipeline)
        assertEquals("hi-latn", request.steps.last().call.arguments["targetLanguage"])
    }

    @Test fun explicitUnsupportedKnownDestinationDoesNotFallBackToTheHindiCueDescription() {
        val request = plan("Generate Hindi dual subtitles to Japanese for this selected video")
        assertEquals(OrezTaskStatus.FAILED, request.status)
        assertEquals("ja", request.authorization!!.subtitle!!.targetLanguage)
        assertEquals("ja", request.steps.last().call.arguments["targetLanguage"])
    }

    @Test fun explicitUnsupportedUnknownDestinationRemainsVisibleAheadOfKnownCueDescription() {
        val request = plan("Generate Hindi dual subtitles into Klingon for this selected video")
        assertEquals(OrezTaskStatus.FAILED, request.status)
        assertEquals("klingon", request.authorization!!.subtitle!!.targetLanguage)
        assertEquals("klingon", request.steps.last().call.arguments["targetLanguage"])
    }

    @Test fun sourceLanguageInCapturedDecodeSettingsNeverOverridesAnExplicitDestination() {
        val request = OrezAgentRuntime().decide("Generate Hindi bilingual captions to English for this selected video",
            context.copy(subtitleOptions = context.subtitleOptions.copy(sourceLanguage = "hi"))).plan!!
        assertEquals(OrezTaskStatus.RUNNING, request.status)
        assertEquals("hi", request.authorization!!.subtitle!!.sourceLanguage)
        assertEquals("en", request.authorization.subtitle!!.targetLanguage)
    }
}
