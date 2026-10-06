package com.mangalens

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.orez.OrezBrainResponse
import com.mangalens.orez.OrezIntent
import com.mangalens.ui.ai.OrezAiViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OrezChatCancellationTest {
    @Test fun stoppingAReplyAllowsTheNextMessageWithoutAnErrorReply() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val tag = UUID.randomUUID().toString()
        val answerText = "Completed reply $tag"
        val store = ViewModelStore()
        lateinit var vm: OrezAiViewModel
        instrumentation.runOnMainSync {
            vm = OrezAiViewModel(app) { _, _ ->
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                OrezBrainResponse(answerText, OrezIntent.GENERAL)
            }
            store.put("cancellation-qa", vm)
            vm.sendMessage("Tell me a story $tag")
        }
        try {
            withTimeout(5_000) { entered.await() }
            val beforeStop = vm.messages.value.map { it.id }.toSet()
            instrumentation.runOnMainSync { assertTrue(vm.typing); vm.stopReply() }
            withTimeout(5_000) {
                while (true) {
                    var ready = false
                    instrumentation.runOnMainSync { ready = !vm.typing }
                    if (ready) break
                    delay(20)
                }
            }
            instrumentation.runOnMainSync { vm.sendMessage("Tell me another story $tag") }
            withTimeout(5_000) {
                while (true) {
                    var ready = false
                    instrumentation.runOnMainSync { ready = !vm.typing }
                    if (ready && vm.messages.value.any { it.text == answerText }) break
                    delay(20)
                }
            }
            assertEquals(2, calls.get())
            assertFalse(vm.messages.value.filter { it.id !in beforeStop }.any { it.text.startsWith("I hit a recoverable error") })
            instrumentation.runOnMainSync { assertFalse(vm.typing) }
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }
}
