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

@RunWith(AndroidJUnit4::class)
class NativeModelTest {
    @Test fun verifiedOptionalModelLoadsGeneratesAndUnloads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = OrezModelManager(context).modelFile
        // Ordinary local test runs do not install the optional 650 MB resource.
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
        } finally {
            peer.close()
            engine.close()
            assertEquals("Unloaded model must not generate", "", engine.generate("hello", 8))
        }
    }
}
