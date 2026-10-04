package com.mangalens.ui.video

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SpeechAudioProcessorTest {
    @Test fun media3EmptyDrainBuffersDoNotCrashOrChangeAudio() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = VideoSpeechEngine(InstrumentationRegistry.getInstrumentation().targetContext, scope)
        val processor = engine.processor
        try {
            processor.configure(AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
            processor.flush()
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertEquals(0, processor.output.remaining())
            val input = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
            val samples = shortArrayOf(123, -456, 32767, -32768, 42, 0, 12, -12)
            samples.forEach { input.putShort(it) }
            input.flip()
            processor.queueInput(input)
            assertEquals(0, input.remaining())
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { assertEquals(it, output.short) }
            assertEquals(0, output.remaining())
            repeat(3) {
                processor.queueInput(AudioProcessor.EMPTY_BUFFER)
                assertEquals(0, processor.output.remaining())
            }
            processor.queueEndOfStream()
            assertTrue(processor.isEnded)
            processor.reset()
        } finally { engine.close(); scope.cancel() }
    }
}
