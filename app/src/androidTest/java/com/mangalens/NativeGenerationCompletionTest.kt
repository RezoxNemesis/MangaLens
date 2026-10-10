package com.mangalens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.orez.OrezModelCatalog
import com.mangalens.orez.OrezModelManager
import com.mangalens.orez.OrezModelPin
import com.mangalens.oreznative.OrezNativeEngine
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real pinned weights verify the new receipt without claiming localization quality or Android latency. */
@RunWith(AndroidJUnit4::class)
class NativeGenerationCompletionTest {
    @Test fun pinnedGenerationDistinguishesEogTokenLimitAndItsOwnCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = OrezModelManager(instrumentation.targetContext)
        kotlinx.coroutines.runBlocking { manager.verifyExistingModels() }
        val descriptor = OrezModelCatalog.lite
        val pin = OrezModelPin(descriptor.id, descriptor.sha256, descriptor.bytes)
        val candidates = manager.verifiedModelCandidates(pin)
        if (InstrumentationRegistry.getArguments().getString("require_model") == "true")
            assertFalse("Install the verified pinned Lite acceptance pack", candidates.isEmpty())
        else assumeTrue("Pinned optional Lite model is not installed", candidates.isNotEmpty())
        val model = candidates.single().file
        assertTrue(OrezNativeEngine.available)
        val engine = OrezNativeEngine()
        val peer = OrezNativeEngine()
        val prompt = "<|im_start|>system\nReply with one short English greeting.\n<|im_end|>\n" +
            "<|im_start|>user\nSay hello, friend.\n<|im_end|>\n<|im_start|>assistant\n"
        try {
            assertTrue(engine.load(model.canonicalPath))
            assertTrue(peer.load(model.canonicalPath))
            val limited = engine.generateWithReceipt(prompt, 1)
            assertEquals("TOKEN_LIMIT", limited.termination)
            assertEquals(1, limited.generatedTokens)
            assertEquals(1, limited.tokenLimit)
            assertTrue("The real model must produce partial text for the limit control", limited.text.isNotBlank())
            assertEquals("Legacy String generation must preserve this same partial text", limited.text, engine.generate(prompt, 1))
            val completed = engine.generateWithReceipt(prompt, 288)
            assertEquals("EOG", completed.termination)
            assertTrue(completed.text.isNotBlank())
            assertTrue(completed.promptTokens in 1..4096)
            assertTrue(completed.generatedTokens in 1..288)
            assertTrue(completed.nativeLockWaitUs >= 0 && completed.setupUs >= 0 && completed.prefillUs >= 0 && completed.decodeUs >= 0)
            assertEquals("Detailed output must retain the same greedy generation", completed.text, engine.generate(prompt, 288))
            engine.newGeneration().use { request ->
                request.cancel()
                val cancelled = engine.generateWithReceipt(prompt, 288, request)
                assertEquals("CANCELLED", cancelled.termination)
                assertEquals("", cancelled.text)
                assertFalse(request.isRunning)
            }
            engine.close()
            assertTrue("Closing the receipt caller must preserve its peer's model owner", peer.generate(prompt, 8).isNotBlank())
        } finally { engine.close(); peer.close() }
    }
}
