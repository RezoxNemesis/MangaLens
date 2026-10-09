package com.mangalens

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.orez.OrezModelManager
import com.mangalens.oreznative.OrezNativeEngine
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeModelTest {
    @Test fun verifiedOptionalModelGeneratesCancelsAndPreservesOwners() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = OrezModelManager(context).modelFile
        // Ordinary local test runs do not install the optional 491 MB Lite resource.
        if (InstrumentationRegistry.getArguments().getString("require_model") == "true") {
            assertTrue("CI resource preflight did not install the optional model", model.isFile)
        } else {
            assumeTrue("Optional model is not installed for this local test run", model.isFile)
        }
        assertEquals(OrezModelManager.MODEL_BYTES, model.length())
        assertTrue("Native library unavailable on the running ABI", OrezNativeEngine.available)
        val engine = OrezNativeEngine()
        val peer = OrezNativeEngine()
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            assertTrue("Native model did not load", engine.load(model.absolutePath))
            OrezNativeEngine().close()
            val prompt = "<|im_start|>system\nYou are a helpful assistant.\n<|im_end|>\n<|im_start|>user\nSay hello in English. A smile: 😀\n<|im_end|>\n<|im_start|>assistant\n"
            val answer = engine.generate(prompt, 32)
            assertTrue("Model generated an empty answer", answer.isNotBlank())
            assertFalse("JNI returned invalid UTF-8", answer.contains('\uFFFD'))
            val replacement = File.createTempFile("orez-peer-model-", ".gguf", context.cacheDir)
            val refused = OrezNativeEngine()
            try {
                replacement.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46, 3, 0, 0, 0))
                assertFalse("A different file borrowed the already mapped model", refused.load(replacement.path))
                assertFalse("Refused feature acquired a model lease", refused.isLoaded)
                assertEquals(model.canonicalPath, OrezNativeEngine.sharedModelPath)
                refused.close()
                assertTrue("Refused replacement unloaded the original owner", engine.generate(prompt, 8).isNotBlank())
            } finally {
                refused.close()
                replacement.delete()
            }
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val report = "ABI=" + android.os.Build.SUPPORTED_ABIS.first() +
                "\nmodel_bytes=" + model.length() + "\nelapsed_ms=" +
                (android.os.SystemClock.elapsedRealtime() - start) + "\nprocess_pss_kib=" + memory.totalPss +
                "\nanswer=" + answer + "\n"
            val qa = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
            File(qa, "native-model.txt").writeText(report)
            android.util.Log.i("MangaLensNativeQA", report)
            assertTrue("Second feature could not acquire the shared model", peer.load(model.absolutePath))
            engine.close()
            assertEquals("Closed feature must not generate using another feature's lease", "", engine.generate(prompt, 8))
            assertTrue("Closing one feature unloaded the model still owned by another", peer.generate(prompt, 8).isNotBlank())

            peer.newGeneration().use { cancelled ->
                cancelled.cancel()
                assertEquals("Pre-cancelled request generated output", "", peer.generate(prompt, 512, cancelled))
            }
            val worker = Executors.newSingleThreadExecutor()
            val request = peer.newGeneration()
            try {
                val longPrompt = "<|im_start|>user\n" + "Explain this story carefully. ".repeat(250) +
                    "\nWrite a long detailed story.\n<|im_end|>\n<|im_start|>assistant\n"
                val result = worker.submit<String> { peer.generate(longPrompt, 512, request) }
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                while (!request.isRunning && !result.isDone && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                assertTrue("CPU decode did not enter its abort callback", request.isRunning)
                engine.newGeneration().use { unrelated -> unrelated.cancel() }
                assertFalse("Cancelling another feature stopped this request", result.isDone)
                val cancelStart = android.os.SystemClock.elapsedRealtime()
                request.cancel()
                assertEquals("Cancelled generation returned partial output", "", result.get(10, TimeUnit.SECONDS))
                val cancelMs = android.os.SystemClock.elapsedRealtime() - cancelStart
                File(qa, "native-model.txt").appendText("cancel_elapsed_ms=$cancelMs\n")
                assertTrue("Model was unusable after cancellation", peer.generate(prompt, 8).isNotBlank())
            } finally {
                request.cancel()
                worker.shutdown()
                assertTrue("Cancelled native worker did not stop", worker.awaitTermination(10, TimeUnit.SECONDS))
                request.close()
            }
        } finally {
            peer.close()
            engine.close()
            assertEquals("Unloaded model must not generate", "", engine.generate("hello", 8))
        }
    }
}
