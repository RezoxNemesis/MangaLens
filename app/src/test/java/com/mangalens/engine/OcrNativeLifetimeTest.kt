package com.mangalens.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrNativeLifetimeTest {
    @Test fun cancellationWaitsForNativeCallbackBeforeOwnerCanReleaseImage() = runBlocking {
        lateinit var complete: (Result<Unit>) -> Unit
        var released = false
        var consumed = false
        val recognition = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitOcrCompletion<Unit> { complete = it }
                consumed = true
            } finally {
                released = true
            }
        }
        recognition.cancel()
        yield()
        try {
            assertFalse("The native operation still owns its image", released)
            assertFalse(recognition.isCompleted)
        } finally {
            complete(Result.success(Unit))
        }
        recognition.join()
        assertTrue(released)
        assertFalse("A cancelled operation must not publish its late reading", consumed)
    }

    @Test fun nativeFailureAfterCancellationStillReleasesOnlyAfterCallback() = runBlocking {
        lateinit var complete: (Result<Unit>) -> Unit
        var released = false
        val recognition = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitOcrCompletion<Unit> { complete = it }
            } finally {
                released = true
            }
        }
        recognition.cancel()
        yield()
        try {
            assertFalse(released)
        } finally {
            complete(Result.failure(IllegalStateException("recognizer failed")))
        }
        recognition.join()
        assertTrue(released)
        assertTrue(recognition.isCancelled)
    }

    @Test fun alreadyCancelledWorkDoesNotStartAnotherNativeOperation() = runBlocking {
        var started = false
        val recognition = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.cancel()
            awaitOcrCompletion<Unit> { started = true; it(Result.success(Unit)) }
        }
        recognition.join()
        assertFalse(started)
    }
}
