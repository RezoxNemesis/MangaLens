package com.mangalens

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.translation.*
import com.mangalens.orez.*
import com.mangalens.oreznative.OrezNativeEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual model/lifetime provenance. Generated text is not a translation-quality assertion. */
@RunWith(AndroidJUnit4::class)
class OrezRefinementProvenanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun capturedDisabledAndMissingModelRequestsIgnoreAmbientPreference() = runBlocking {
        val preferences = context.getSharedPreferences("mangalens_ocr", Context.MODE_PRIVATE)
        val existed = preferences.contains("local_refinement")
        val before = preferences.getBoolean("local_refinement", false)
        val refiner = TranslationOrezRefiner(context)
        try {
            preferences.edit().putBoolean("local_refinement", true).commit()
            val disabled = refiner.refineCaptured("Wait here.", "Yahan ruko.", "hi-latn",
                TranslationRefinementRequest(false, TranslationStyleProfile.custom("Use formal Hindi.")))
            assertEquals("Yahan ruko.", disabled.text)
            assertEquals(TranslationRefinementStatus.DISABLED, disabled.status)
            assertNull(disabled.receipt)
            val unavailable = refiner.refineCaptured("Wait here.", "Yahan ruko.", "hi-latn",
                TranslationRefinementRequest(true, TranslationStyleProfile.custom("Use formal Hindi."), null))
            assertEquals(TranslationRefinementStatus.UNAVAILABLE, unavailable.status)
            assertNull("Missing captured model borrowed an ambient model", unavailable.receipt)
        } finally {
            refiner.close()
            preferences.edit().apply { if (existed) putBoolean("local_refinement", before) else remove("local_refinement") }.commit()
        }
    }

    @Test fun pinnedLocalAnswerKeepsExactReceiptAcrossReopenAndAmbientTierChanges() = runBlocking {
        val modelPreferences = context.getSharedPreferences("orez_model", Context.MODE_PRIVATE)
        val tierKey = "selected_tier"
        val hadTier = modelPreferences.contains(tierKey)
        val previousTier = modelPreferences.getString(tierKey, null)
        val manager = OrezModelManager(context)
        manager.verifyExistingModels()
        val required = InstrumentationRegistry.getArguments().getString("require_model") == "true"
        if (required) assertTrue("Optional verified Orez model missing", manager.runtimeModelFiles().isNotEmpty())
        else assumeTrue("Optional verified Orez model is not installed", manager.runtimeModelFiles().isNotEmpty())
        assertTrue("Native Orez library unavailable on running ABI", OrezNativeEngine.available)
        val first = OrezLocalModelService(manager)
        val second = OrezLocalModelService(OrezModelManager(context))
        val peer = OrezNativeEngine()
        try {
            val pin = requireNotNull(first.captureModelPin()) { "Verified model could not safely be captured." }
            val candidate = manager.verifiedModelCandidates(pin).first().file
            assertTrue("Peer could not acquire captured verified weights", peer.load(candidate.canonicalPath))
            val prompt = "Say hello in English. Return only one short greeting."
            val answer = requireNotNull(first.answerWithReceipt(prompt, emptyList(), pinnedModel = pin)) {
                "Pinned local generation failed or exceeded its time budget."
            }
            assertEquals(pin, answer.model)
            assertTrue(answer.text.isNotBlank())
            val changedTier = if (pin.modelId == OrezModelCatalog.core.id) OrezModelTier.LITE else OrezModelTier.CORE
            manager.selectTier(changedTier)
            val invalidPin = pin.copy(sha256 = if (pin.sha256 == "a".repeat(64)) "b".repeat(64) else "a".repeat(64))
            assertNull("Unavailable captured pin fell back to selected or loaded weights",
                second.answerWithReceipt(prompt, emptyList(), pinnedModel = invalidPin))
            assertEquals(candidate.canonicalPath, OrezNativeEngine.sharedModelPath)
            first.close()
            val reopened = requireNotNull(second.answerWithReceipt(prompt, emptyList(), pinnedModel = pin)) {
                "Cold service failed to resolve captured weights after ambient tier changed."
            }
            assertEquals(pin, reopened.model)
            assertTrue(reopened.text.isNotBlank())
            second.close()
            assertTrue("Closing refiner feature unloaded another feature's model lease",
                peer.generate("<|im_start|>user\nSay hello.\n<|im_end|>\n<|im_start|>assistant\n", 8).isNotBlank())
            File(context.getExternalFilesDir(null), "qa/refinement/model-receipt.json").apply { parentFile!!.mkdirs() }
                .writeText(JSONObject().put("source_sha", BuildConfig.SOURCE_SHA)
                    .put("model_id", pin.modelId).put("model_sha256", pin.sha256).put("model_bytes", pin.bytes)
                    .put("first_answer", answer.text).put("reopened_answer", reopened.text)
                    .put("ambient_tier", changedTier.name).put("claim", "Model lifetime/provenance only; no style or translation-quality claim").toString(2))
        } finally {
            first.close(); second.close(); peer.close()
            modelPreferences.edit().apply { if (hadTier) putString(tierKey, previousTier) else remove(tierKey) }.commit()
            manager.refresh()
        }
    }
}
