package com.mangalens.engine

import com.mangalens.core.translation.ChapterTranslationPage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OcrSourceResolutionLifetimeTest {
    private val proof = OcrSourceResolutionProof("a".repeat(32), "b".repeat(32), "c".repeat(32), 2,
        "/private/chapters/source.jpg", "d".repeat(64), 720, 9170, 180, 2292)
    private val request = OcrSourceResolutionRequest(OcrBox(50f, 1500f, 175f, 1587.5f), 180, 2292)

    @Test fun everyCapturedIdentityFieldMustStillMatchBeforeOpeningDecoder() = runBlocking {
        for (changed in listOf(proof.copy(taskId = "e".repeat(32)), proof.copy(generation = "e".repeat(32)),
            proof.copy(chapterId = "e".repeat(32)), proof.copy(pageIndex = 3), proof.copy(sourcePath = "/private/chapters/other.jpg"),
            proof.copy(sourceSha256 = "e".repeat(64)), proof.copy(originalHeight = 9169), proof.copy(decodedWidth = 179))) {
            val opens = AtomicInteger(); val native = AtomicInteger()
            try {
                withQualifiedOriginalOcrCrop(proof, request, { changed }, {
                    opens.incrementAndGet(); OcrOriginalCropLease("pixels", 500, 351, { true }, {})
                }) { _, _ -> native.incrementAndGet() }
                fail("Changed proof must fence this crop: $changed")
            } catch (_: CancellationException) { }
            assertEquals(0, opens.get()); assertEquals(0, native.get())
        }
    }

    @Test fun sourceMutationBetweenDecodeAndNativeStartNeverUsesThosePixels() = runBlocking {
        var closed = 0; var native = 0
        try {
            withQualifiedOriginalOcrCrop(proof, request, { proof }, {
                OcrOriginalCropLease("changed pixels", 500, 351, { false }, { closed++ })
            }) { _, _ -> native++ }
            fail("Unverified crop must fail closed")
        } catch (_: OcrOriginalSourceChangedException) { }
        assertEquals(0, native); assertEquals(1, closed)
    }

    @Test fun ownerInvalidationDuringDecoderReadReleasesCropWithoutStartingNative() = runBlocking {
        var current: OcrSourceResolutionProof? = proof; var closed = 0; var native = 0
        try {
            withQualifiedOriginalOcrCrop(proof, request, { current }, {
                current = null
                OcrOriginalCropLease("old pixels", 500, 351, { true }, { closed++ })
            }) { _, _ -> native++ }
            fail("Invalidated generation must not infer")
        } catch (_: CancellationException) { }
        assertEquals(0, native); assertEquals(1, closed)
    }

    @Test fun callerCancellationWaitsActualNativeReturnBeforeReleasingOriginalCrop() = runBlocking {
        val nativeStarted = CompletableDeferred<Unit>(); val complete = CompletableDeferred<(Result<String>) -> Unit>()
        val closed = AtomicInteger()
        val job = launch {
            withQualifiedOriginalOcrCrop(proof, request, { proof }, {
                OcrOriginalCropLease("source pixels", 500, 351, { true }, { closed.incrementAndGet() })
            }) { lease, _ ->
                assertEquals("source pixels", lease.resource)
                awaitOcrCompletion<String> { callback -> complete.complete(callback); nativeStarted.complete(Unit) }
            }
        }
        withTimeout(1000) { nativeStarted.await() }; job.cancel()
        yield()
        assertFalse("Native still owns crop", job.isCompleted)
        assertEquals(0, closed.get())
        complete.await()(Result.success("native returned")); job.join()
        assertEquals(1, closed.get())
    }

    @Test fun cancellationBeforeDecoderEntryStartsNoReadAndFreshRequestStillWorks() = runBlocking {
        val queued = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val opens = AtomicInteger()
        val old = launch {
            withQualifiedOriginalOcrCrop(proof, request, {
                queued.complete(Unit); release.await(); proof
            }, { opens.incrementAndGet(); OcrOriginalCropLease("pixels", 500, 351, { true }, {}) }) { _, _ -> "old" }
        }
        withTimeout(1000) { queued.await() }; old.cancel(); release.complete(Unit); old.join(); assertEquals(0, opens.get())
        val result = withQualifiedOriginalOcrCrop(proof, request, { proof }, {
            opens.incrementAndGet(); OcrOriginalCropLease("fresh", 500, 351, { true }, {})
        }) { lease, _ -> lease.resource }
        assertEquals("fresh", result); assertEquals(1, opens.get())
    }

    @Test fun sourceChangeDuringNativeReturnDiscardsTheResultButPreservesCleanup() = runBlocking {
        var valid = true; var closed = 0
        try {
            withQualifiedOriginalOcrCrop(proof, request, { proof }, {
                OcrOriginalCropLease("original", 500, 351, { valid }, { closed++ })
            }) { _, _ -> valid = false; "completed but stale" }
            fail("Source qualification must survive native return")
        } catch (_: OcrOriginalSourceChangedException) { }
        assertEquals(1, closed)
    }

    @Test fun ownerInvalidationDuringHashVerificationCannotStartNative() = runBlocking {
        var current: OcrSourceResolutionProof? = proof; var native = 0; var closed = 0
        try {
            withQualifiedOriginalOcrCrop(proof, request, { current }, {
                OcrOriginalCropLease("pixels", 500, 351, { current = null; true }, { closed++ })
            }) { _, _ -> native++ }
            fail("Hash success does not override a later owner invalidation")
        } catch (_: CancellationException) { }
        assertEquals(0, native); assertEquals(1, closed)
    }

    @Test fun failedDecoderStillHonorsInvalidationInsteadOfFallingBackToOldPage() = runBlocking {
        var current: OcrSourceResolutionProof? = proof
        try {
            withQualifiedOriginalOcrCrop<String, String>(proof, request, { current }, { current = null; null }) { _, _ -> "unexpected" }
            fail("Null crop must not revive an invalidated page")
        } catch (_: CancellationException) { }
    }

    @Test fun cancellationAtIoLeaseHandoffReleasesTheCreatedCropExactlyOnce() = runBlocking {
        val created = CompletableDeferred<Unit>(); val releaseRead = CompletableDeferred<Unit>()
        val closed = AtomicInteger()
        val job = launch {
            acquireOriginalOcrCropOnIo {
                withContext(NonCancellable) {
                    created.complete(Unit); releaseRead.await()
                    OcrOriginalCropLease("created before cancellation", 500, 351, { true }, { closed.incrementAndGet() })
                }
            }
        }
        withTimeout(1000) { created.await() }; job.cancel(); yield()
        assertEquals(0, closed.get())
        releaseRead.complete(Unit); job.join()
        assertEquals("An IO result discarded by prompt cancellation still owns pixels", 1, closed.get())
    }

    @Test fun actualFirstChapterPageContractDoesNotSilentlyFallBackToLostPixels() = runBlocking {
        val page = ChapterTranslationPage(0, proof.sourcePath, proof.sourceSha256)
        val captured = proof.copy(pageIndex = page.index)
        var opens = 0; var native = 0; var closed = 0
        val result = withQualifiedOriginalOcrCrop(captured, request, { captured }, {
            opens++; OcrOriginalCropLease("original page0 pixels", 500, 351, { true }, { closed++ })
        }) { lease, _ -> native++; lease.resource }
        assertEquals("original page0 pixels", result)
        assertEquals(1, opens); assertEquals(1, native); assertEquals(1, closed)
    }
}
