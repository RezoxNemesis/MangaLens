package com.mangalens.orez.agent

import com.mangalens.core.translation.TranslationRefinementPolicy
import com.mangalens.core.translation.TranslationRefinementRequest
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.orez.OrezModelCatalog
import com.mangalens.orez.OrezModelPin
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import com.mangalens.ui.video.SubtitleGenerationConfig
import com.mangalens.ui.video.SubtitleRefinementPin
import com.mangalens.ui.video.SubtitleTargetOptions
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** AUTHORED_UNRUN: exact durable/native style mapping and model requirement, no inference. */
class OrezNamedStyleCaptureTest {
    private val named get() = listOf(TranslationStyleProfile.MANGA, TranslationStyleProfile.LITERAL)
    private val descriptor get() = OrezModelCatalog.lite
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }

    @Test fun nativeChapterWhitelistAndActualJournalPreserveCapturedNamedRequests() = runTest {
        named.forEach { style ->
            val pin = descriptor.let { OrezModelPin(it.id, it.sha256, it.bytes) }
            val request = TranslationRefinementRequest(true, style, pin, TranslationRefinementPolicy.INPUT_PROFILE_VERSION)
            val options = OrezTranslationOptions("hi", style.id, localRefinement = true, refinementRequest = request)
            val plan = OrezAgentRuntime().decide("Translate this chapter into Hindi", OrezAgentContext(
                hasActiveChapter = true, activeChapterId = "a".repeat(32), translationOptions = options)).plan!!
            OrezDurablePlanRules.validate(plan)
            val journal = Journal()
            assertTrue(OrezTaskStore(journal).checkpoint(plan))
            val restored = OrezTaskStore(journal).load(plan.id)!!
            OrezDurablePlanRules.validate(restored)
            assertEquals(plan.authorization, restored.authorization)
            val captured = restored.authorization!!.translation!!
            assertEquals(style.id, captured.styleId)
            assertEquals(request, captured.refinementRequest)
            assertEquals(style, captured.nativeChapterConfig().style())
            assertEquals(captured.nativeChapterConfig(), captured.nativeChapterConfig().orezChapterOptions().nativeChapterConfig())
        }
    }

    @Test fun nativeSubtitleContractAdmitsOnlyExactlyCapturedRefinedNamedProfiles() {
        named.forEach { style ->
            val options = SubtitleGenerationConfig(threads = 2).withTarget(SubtitleTargetOptions(
                targetLanguage = "hi-latn", style = style.id, localRefinement = true,
                refinementPin = descriptor.let { SubtitleRefinementPin(it.id, it.sha256, it.bytes) }, capturedStyle = style,
                refinementInputProfileRevision = TranslationRefinementPolicy.INPUT_PROFILE_VERSION)).orezOptions()
            OrezSubtitleContract.validate(options)
            assertEquals(style, options.nativeConfig("b".repeat(64)).capturedStyle)
            assertEquals(options, options.nativeConfig("b".repeat(64)).orezOptions())
            assertThrows(IllegalArgumentException::class.java) {
                OrezSubtitleContract.validate(options.copy(capturedStyle = TranslationStyleProfile.NATURAL))
            }
        }
    }

    @Test fun namedSubtitleProfilesCannotSilentlyUseTheUnrefinedBasicProvider() {
        named.forEach { style ->
            val options = SubtitleGenerationConfig(threads = 2).withTarget(SubtitleTargetOptions(
                targetLanguage = "hi", style = style.id, capturedStyle = style)).orezOptions()
            assertThrows(IllegalArgumentException::class.java) { OrezSubtitleContract.validate(options) }
        }
        for (style in listOf(TranslationStyleProfile.NATURAL, TranslationStyleProfile.FAITHFUL)) {
            OrezSubtitleContract.validate(SubtitleGenerationConfig(threads = 2).withTarget(
                SubtitleTargetOptions("hi", style = style.id, capturedStyle = style)).orezOptions())
        }
    }

    @Test fun namedNativeSubtitleFingerprintsNeverAliasFaithfulOrEachOther() {
        val configs = (named + TranslationStyleProfile.FAITHFUL).map { style ->
            SubtitleGenerationConfig(threads = 2).withTarget(SubtitleTargetOptions("hi", style = style.id,
                localRefinement = true, refinementPin = descriptor.let { SubtitleRefinementPin(it.id, it.sha256, it.bytes) }, capturedStyle = style))
        }
        assertEquals(3, configs.map { it.fingerprint() }.distinct().size)
        configs.forEach { assertEquals(it.fingerprint(), it.orezOptions().nativeConfig("").fingerprint()) }
    }
}
