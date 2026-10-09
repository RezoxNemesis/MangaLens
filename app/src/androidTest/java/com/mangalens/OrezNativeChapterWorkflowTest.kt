package com.mangalens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.room.Room
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.reader.ChapterLibrary
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.ChapterTranslationJobs
import com.mangalens.core.translation.ChapterTranslationStatus
import com.mangalens.core.translation.ChapterTranslationPageStatus
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.agent.*
import com.mangalens.orez.agent.OrezChapterTools.Companion.outputs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Actual Room + managed PNGs + native foreground OCR/translation worker; no remote fixture/model needed. */
@RunWith(AndroidJUnit4::class)
class OrezNativeChapterWorkflowTest {
    private class ControlledHost(private val native: OrezChapterHost, var receipt: OrezChapterReceipt) : OrezChapterHost by native {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var holdLookup = false; var holdResume = false; var crashAfterResume = false
        var failResumeScheduling = false; var crashLookupOnce = false
        override suspend fun findOwned(requestId: String): OrezChapterReceipt? {
            if (crashLookupOnce) { crashLookupOnce = false; throw CancellationException("process loss after durable native-stop intent") }
            if (holdLookup) { entered.complete(Unit); release.await() }
            return receipt.takeIf { it.ownerRequestId == requestId }
        }
        override suspend fun pause(receipt: OrezChapterReceipt) = receipt.copy(status = OrezNativeChapterStatus.PAUSED).also { this.receipt = it }
        override suspend fun cancel(receipt: OrezChapterReceipt) = receipt.copy(status = OrezNativeChapterStatus.CANCELLED).also { this.receipt = it }
        override suspend fun resume(receipt: OrezChapterReceipt): OrezChapterReceipt {
            this.receipt = receipt.copy(generation = "2".repeat(32), status = OrezNativeChapterStatus.RUNNING)
            if (holdResume) { entered.complete(Unit); release.await() }
            if (crashAfterResume) throw CancellationException("process loss after native commit before Orez receipt")
            if (failResumeScheduling) throw java.io.IOException("scheduling failed after native resume committed")
            return this.receipt
        }
    }
    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val token = UUID.randomUUID().toString()
        val id = ChapterLibrary.id("content://orez-native-workflow/$token")
        val library = ChapterLibrary(context)
        val pages = (1..2).map { index ->
            val source = File(context.filesDir, "chapters/orez-native-$token-$index.png")
            source.parentFile!!.mkdirs()
            image(source, if (index == 1) "We should go home now." else "Our friends are waiting.")
            ChapterPage(index, "content://orez-native-workflow/$token/$index", source.absolutePath)
        }
        val chapter = SavedChapter(id, "Orez native workflow fixture", "content://orez-native-workflow/$token", pages)
        val dbName = "orez-native-workflow-$token.db"
        init { library.save(chapter) }
        fun database() = Room.databaseBuilder(context, OrezRoomDatabase::class.java, dbName).build()
        fun plan() = OrezAgentRuntime().decide("Translate this chapter into English", OrezAgentContext(
            hasActiveChapter = true, activeChapterId = id,
            translationOptions = OrezTranslationOptions(targetLanguage = "en", ocrScript = "LATIN", highAccuracy = false,
                localRefinement = false))).plan!!
        // The tests own an actual Room journal; reopening follows its new DAO rather
        // than consulting a different application database for the same owner string.
        fun nativeHost(journal: () -> OrezTaskStore) = OrezNativeChapterHost(context, ownerPlan = { requestId ->
            OrezDownloadTaskLink.candidates(requestId).firstNotNullOfOrNull { planId ->
                journal().load(planId)?.takeIf { plan -> plan.steps.any { step ->
                    step.call.name == "translate_saved_chapter" && OrezDurablePlanRules.requestId(plan.id, step.index) == requestId
                } }
            }
        })
        suspend fun queuedPlan(journal: OrezTaskStore): Pair<OrezTaskPlan, com.mangalens.core.translation.ChapterTranslationTask> {
            val proposed = plan(); val options = proposed.authorization!!.translation!!
            val host = nativeHost { journal }
            val inspected = requireNotNull(host.inspect(id)) { "The actual saved fixture could not be inspected." }
            val intent = proposed.copy(status = OrezTaskStatus.RUNNING, steps = proposed.steps.map {
                if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED,
                    outputs = inspected.outputs(OrezDurablePlanRules.requestId(proposed.id, 0)), outputKind = OrezOutputKind.SAVED_CHAPTER)
                else it.copy(status = OrezStepStatus.RUNNING)
            })
            // Inspection and exact translation intent are durable before the native
            // owner exists; an unqualified lookup can now resolve committed scope.
            check(journal.checkpoint(intent))
            val task = ChapterTranslationStore.shared(context).start(chapter, options.nativeChapterConfig(),
                ownerRequestId = OrezDurablePlanRules.requestId(proposed.id, 1))
            assertNull("An owner string without a committed plan must not grant receipt access.",
                OrezNativeChapterHost(context, ownerPlan = { null }).observe(task.id))
            val receipt = requireNotNull(host.observe(task.id)) { "Committed fixture owner/source/config scope did not produce a receipt." }
            OrezChapterTools.verify(receipt, inspected, options, task.ownerRequestId!!)
            val saved = intent.copy(steps = intent.steps.map {
                if (it.index == 1) it.copy(outputs = receipt.outputs(task.ownerRequestId!!)) else it
            })
            check(journal.checkpoint(saved))
            return saved to task
        }
        fun image(file: File, text: String) {
            val bitmap = Bitmap.createBitmap(900, 1100, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.LTGRAY)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                canvas.drawRoundRect(35f, 100f, 865f, 390f, 90f, 90f, paint)
                paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                canvas.drawRoundRect(35f, 100f, 865f, 390f, 90f, 90f, paint)
                paint.style = Paint.Style.FILL; paint.textSize = 54f; paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                canvas.drawText(text, 90f, 255f, paint)
                FileOutputStream(file).use { stream -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.fd.sync() }
            } finally { bitmap.recycle() }
        }
        suspend fun cleanup() {
            ChapterTranslationJobs.removeChapter(context, id)
            library.remove(id)
            context.deleteDatabase(dbName)
        }
    }

    @Test fun savedChapterRunsThroughNativeWorkerAndReopensWithTypedVerifiedResult() = runBlocking {
        val fixture = Fixture(); var db = fixture.database()
        try {
            var journal = OrezTaskStore(db.tasks()); val plan = fixture.plan(); journal.checkpoint(plan)
            val host = fixture.nativeHost { journal }
            fun executor() = OrezTaskExecutor(journal, OrezChapterTools(host, plan.authorization!!) {
                journal.isExecuting(plan.id, plan.executionEpoch)
            })
            var result = executor().run(plan.id)
            // Reopen Room after native creation/pending receipt. The foreground native job
            // keeps running independently of this orchestration instance and UI navigation.
            db.close(); db = fixture.database(); journal = OrezTaskStore(db.tasks())
            result = withTimeout(90_000) {
                while (result is OrezTaskExecutor.Result.Pending) {
                    delay(300)
                    result = executor().run(plan.id)
                }
                result
            }
            assertTrue("Native workflow stopped: $result", result is OrezTaskExecutor.Result.Completed || result == OrezTaskExecutor.Result.AlreadyFinished)
            val saved = journal.load(plan.id)!!
            assertEquals(OrezTaskStatus.COMPLETED, saved.status)
            assertEquals(OrezOutputKind.SAVED_CHAPTER, saved.steps[0].outputKind)
            assertEquals(OrezOutputKind.CHAPTER_TRANSLATION, saved.steps[1].outputKind)
            assertEquals(plan.authorization, saved.authorization)
            assertEquals("2", saved.steps[1].outputs["completedPages"])
            val receipt = host.observe(saved.steps[1].outputs.getValue("translationTaskId"))!!
            assertEquals(OrezDurablePlanRules.requestId(plan.id, 1), receipt.ownerRequestId)
            assertEquals(OrezNativeChapterStatus.COMPLETED, receipt.status)
            assertTrue(receipt.translatedRegions >= 2)
            val repeated = host.start(receipt.chapter, plan.authorization!!.translation!!, receipt.ownerRequestId!!, allowReplacement = false)
            assertEquals(receipt.generation, repeated.generation)
            assertEquals(OrezTaskExecutor.Result.AlreadyFinished, executor().run(plan.id))
            assertTrue(fixture.library.list().any { it.id == fixture.id && it.pages.all { page -> File(page.localPath!!).isFile } })
        } finally { db.close(); fixture.cleanup() }
    }

    @Test fun changedSavedSourceCannotReachNativeTranslationOrClaimCompletion() = runBlocking {
        val fixture = Fixture(); val db = fixture.database()
        try {
            val journal = OrezTaskStore(db.tasks()); val plan = fixture.plan(); journal.checkpoint(plan)
            val native = OrezChapterTools(OrezNativeChapterHost(fixture.context), plan.authorization!!)
            val executor = OrezTaskExecutor(journal, OrezDurableTools { step, requestId ->
                native.execute(step, requestId).also {
                    if (step.index == 0) fixture.image(File(fixture.pages[0].localPath!!), "The source has changed.")
                }
            })
            assertTrue(executor.run(plan.id) is OrezTaskExecutor.Result.Failed)
            val failed = journal.load(plan.id)!!
            assertEquals(OrezStepStatus.COMPLETED, failed.steps[0].status)
            assertEquals(OrezStepStatus.FAILED, failed.steps[1].status)
            assertTrue(ChapterTranslationStore.shared(fixture.context).states.value.none { it.ownerRequestId == OrezDurablePlanRules.requestId(plan.id, 1) })
        } finally { db.close(); fixture.cleanup() }
    }

    @Test fun acceptedCancelFinishesNativeControlAfterItsCallingUiScopeIsCancelled() = runBlocking {
        val fixture = Fixture(); val db = fixture.database()
        try {
            val store = OrezTaskStore(db.tasks()); val proposed = fixture.plan()
            val snapshot = OrezNativeChapterHost(fixture.context).inspect(fixture.id)!!
            val requestId = OrezDurablePlanRules.requestId(proposed.id, 1)
            val receipt = OrezChapterReceipt("c".repeat(32), "1".repeat(32), requestId, snapshot,
                proposed.authorization!!.translation!!, OrezNativeChapterStatus.RUNNING, 0)
            val plan = proposed.copy(steps = proposed.steps.map { if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED,
                outputs = snapshot.outputs(OrezDurablePlanRules.requestId(proposed.id, 0)), outputKind = OrezOutputKind.SAVED_CHAPTER)
                else it.copy(status = OrezStepStatus.RUNNING, outputs = receipt.outputs(requestId)) })
            store.checkpoint(plan)
            val host = ControlledHost(OrezNativeChapterHost(fixture.context), receipt).apply { holdLookup = true }
            val controls = OrezTaskControls(fixture.context, store, host)
            val caller = launch { controls.cancel(plan.id) }
            withTimeout(10_000) { host.entered.await() }
            caller.cancel() // The command was accepted before navigation cleared the ViewModel scope.
            host.release.complete(Unit)
            withTimeout(10_000) { caller.join() }
            assertEquals(OrezTaskStatus.CANCELLED, store.load(plan.id)!!.status)
            assertEquals(OrezNativeChapterStatus.CANCELLED, host.receipt.status)
        } finally { db.close(); fixture.cleanup() }
    }

    @Test fun nativeResumeIsNotDispatchableUntilReceiptCommitAndRecoversItsCommitGap() = runBlocking {
        val fixture = Fixture(); var db = fixture.database()
        try {
            var store = OrezTaskStore(db.tasks()); val proposed = fixture.plan()
            val snapshot = OrezNativeChapterHost(fixture.context).inspect(fixture.id)!!
            val requestId = OrezDurablePlanRules.requestId(proposed.id, 1)
            val receipt = OrezChapterReceipt("c".repeat(32), "1".repeat(32), requestId, snapshot,
                proposed.authorization!!.translation!!, OrezNativeChapterStatus.PAUSED, 0)
            val plan = proposed.copy(steps = proposed.steps.map { if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED,
                outputs = snapshot.outputs(OrezDurablePlanRules.requestId(proposed.id, 0)), outputKind = OrezOutputKind.SAVED_CHAPTER)
                else it.copy(status = OrezStepStatus.RUNNING, outputs = receipt.outputs(requestId)) })
            store.checkpoint(plan); store.pause(plan.id)
            val host = ControlledHost(OrezNativeChapterHost(fixture.context), receipt).apply { holdResume = true; crashAfterResume = true }
            val dispatched = mutableListOf<OrezTaskPlan>()
            val controls = OrezTaskControls(fixture.context, store, host, enqueueTask = { dispatched += it })
            val caller = launch { try { controls.resume(plan.id) } catch (_: CancellationException) { } }
            withTimeout(10_000) { host.entered.await() }
            val transitioning = store.load(plan.id)!!
            assertEquals(OrezTaskStatus.WAITING, transitioning.status)
            assertTrue(transitioning.resuming && transitioning.pausedByUser)
            assertEquals("1".repeat(32), transitioning.steps[1].outputs["generation"])
            assertTrue(dispatched.isEmpty())
            host.release.complete(Unit); caller.join()
            db.close(); db = fixture.database(); store = OrezTaskStore(db.tasks())
            assertTrue(store.load(plan.id)!!.resuming)
            host.holdResume = false; host.crashAfterResume = false
            OrezTaskControls(fixture.context, store, host, enqueueTask = { dispatched += it }).resume(plan.id)
            val resumed = store.load(plan.id)!!
            assertFalse(resumed.resuming || resumed.pausedByUser)
            assertEquals(OrezTaskStatus.PLANNED, resumed.status)
            assertEquals("2".repeat(32), resumed.steps[1].outputs["generation"])
            assertEquals(1, dispatched.size)
            assertEquals("2".repeat(32), dispatched.single().steps[1].outputs["generation"])
        } finally { db.close(); fixture.cleanup() }
    }

    @Test fun pendingPauseAndCancelRecoverTheOwnedGenerationAfterBothCommitGaps() = runBlocking {
        for (control in OrezPendingControl.entries) {
            val fixture = Fixture(); var db = fixture.database()
            try {
                var store = OrezTaskStore(db.tasks()); val proposed = fixture.plan()
                val native = OrezNativeChapterHost(fixture.context); val snapshot = native.inspect(fixture.id)!!
                val requestId = OrezDurablePlanRules.requestId(proposed.id, 1)
                val receipt = OrezChapterReceipt("c".repeat(32), "1".repeat(32), requestId, snapshot,
                    proposed.authorization!!.translation!!, OrezNativeChapterStatus.PAUSED, 0)
                val plan = proposed.copy(steps = proposed.steps.map { if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED,
                    outputs = snapshot.outputs(OrezDurablePlanRules.requestId(proposed.id, 0)), outputKind = OrezOutputKind.SAVED_CHAPTER)
                    else it.copy(status = OrezStepStatus.RUNNING, outputs = receipt.outputs(requestId)) })
                store.checkpoint(plan); store.pause(plan.id)
                val host = ControlledHost(native, receipt).apply { crashAfterResume = true }
                try { OrezTaskControls(fixture.context, store, host, enqueueTask = {}).resume(plan.id); fail("Expected native commit gap") }
                catch (_: CancellationException) { }
                assertEquals("2".repeat(32), host.receipt.generation)
                assertTrue(store.load(plan.id)!!.resuming)
                host.crashLookupOnce = true
                val controls = OrezTaskControls(fixture.context, store, host, enqueueTask = {})
                try {
                    if (control == OrezPendingControl.CANCEL) controls.cancel(plan.id) else controls.pause(plan.id)
                    fail("Expected stop commit gap")
                } catch (_: CancellationException) { }
                db.close(); db = fixture.database(); store = OrezTaskStore(db.tasks())
                val pending = store.load(plan.id)!!
                assertEquals(control, pending.pendingControl)
                assertTrue(pending.resuming)
                assertEquals(OrezTaskStatus.WAITING, pending.status)
                val restored = OrezTaskControls(fixture.context, store, host, enqueueTask = {})
                if (control == OrezPendingControl.CANCEL) restored.cancel(plan.id, restoreOnly = true)
                else restored.pause(plan.id, restoreOnly = true)
                val stopped = store.load(plan.id)!!
                assertNull(stopped.pendingControl)
                assertFalse(stopped.resuming)
                assertEquals("2".repeat(32), stopped.steps[1].outputs["generation"])
                assertEquals(if (control == OrezPendingControl.CANCEL) OrezNativeChapterStatus.CANCELLED else OrezNativeChapterStatus.PAUSED, host.receipt.status)
                assertEquals(if (control == OrezPendingControl.CANCEL) OrezTaskStatus.CANCELLED else OrezTaskStatus.WAITING, stopped.status)
            } finally { db.close(); fixture.cleanup() }
        }
    }

    @Test fun ordinarySchedulingFailureAfterNativeCommitCanReconcileSameOwnedGeneration() = runBlocking {
        val fixture = Fixture(); var db = fixture.database()
        try {
            var store = OrezTaskStore(db.tasks()); val proposed = fixture.plan()
            val native = OrezNativeChapterHost(fixture.context); val snapshot = native.inspect(fixture.id)!!
            val requestId = OrezDurablePlanRules.requestId(proposed.id, 1)
            val receipt = OrezChapterReceipt("c".repeat(32), "1".repeat(32), requestId, snapshot,
                proposed.authorization!!.translation!!, OrezNativeChapterStatus.PAUSED, 0)
            val plan = proposed.copy(steps = proposed.steps.map { if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED,
                outputs = snapshot.outputs(OrezDurablePlanRules.requestId(proposed.id, 0)), outputKind = OrezOutputKind.SAVED_CHAPTER)
                else it.copy(status = OrezStepStatus.RUNNING, outputs = receipt.outputs(requestId)) })
            store.checkpoint(plan); store.pause(plan.id)
            val host = ControlledHost(native, receipt).apply { failResumeScheduling = true }
            try { OrezTaskControls(fixture.context, store, host, enqueueTask = {}).resume(plan.id); fail("Expected scheduling failure") }
            catch (_: java.io.IOException) { }
            db.close(); db = fixture.database(); store = OrezTaskStore(db.tasks())
            assertTrue(store.load(plan.id)!!.resuming)
            assertEquals("1".repeat(32), store.load(plan.id)!!.steps[1].outputs["generation"])
            host.failResumeScheduling = false
            OrezTaskControls(fixture.context, store, host, enqueueTask = {}).resume(plan.id, restoreOnly = true)
            val ready = store.load(plan.id)!!
            assertFalse(ready.resuming)
            assertEquals("2".repeat(32), ready.steps[1].outputs["generation"])
            assertEquals(OrezTaskStatus.PLANNED, ready.status)
        } finally { db.close(); fixture.cleanup() }
    }

    @Test fun nativeWorkerHonorsPendingOwnerStopWithoutAnyOrezViewModel() = runBlocking {
        for (control in OrezPendingControl.entries) {
            val fixture = Fixture(); val journal = OrezTaskStore(OrezRoomDatabase.get(fixture.context).tasks())
            var planId: String? = null
            try {
                val (plan, task) = fixture.queuedPlan(journal); planId = plan.id
                journal.beginStop(plan.id, control)!! // Durable commit followed by loss of the UI control caller.
                ChapterTranslationJobs.start(fixture.context, fixture.chapter, task.config,
                    ownerRequestId = task.ownerRequestId, allowOwnerReplacement = false)
                val workName = ChapterTranslationJobs.workName(task.id, task.generation)
                withTimeout(20_000) {
                    while (WorkManager.getInstance(fixture.context).getWorkInfosForUniqueWork(workName).get()
                        .none { it.state == WorkInfo.State.SUCCEEDED }) delay(100)
                }
                val stopped = ChapterTranslationStore.shared(fixture.context).get(task.id)!!
                assertEquals(if (control == OrezPendingControl.CANCEL) ChapterTranslationStatus.CANCELLED else ChapterTranslationStatus.PAUSED, stopped.status)
                assertTrue(stopped.pages.all { it.status == ChapterTranslationPageStatus.PENDING && it.lettering.isEmpty() && it.cleanedPath == null })
                val acknowledged = journal.load(plan.id)!!
                assertNull(acknowledged.pendingControl)
                assertEquals(if (control == OrezPendingControl.CANCEL) OrezTaskStatus.CANCELLED else OrezTaskStatus.WAITING, acknowledged.status)
            } finally { planId?.let { journal.cancel(it) }; fixture.cleanup() }
        }
    }

    @Test fun applicationRecoveryAppliesPendingStopThroughActualNativeControlWithoutOrezUi() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val journal = OrezTaskStore(OrezRoomDatabase.get(context).tasks())
        val fixtures = mutableListOf<Fixture>()
        val pending = mutableListOf<Pair<OrezTaskPlan, com.mangalens.core.translation.ChapterTranslationTask>>()
        try {
            repeat(9) {
                val fixture = Fixture().also(fixtures::add)
                val queued = fixture.queuedPlan(journal).also(pending::add)
                journal.beginStop(queued.first.id, OrezPendingControl.CANCEL)!!
            }
            OrezTaskRecovery.recoverPendingControls(context)
            pending.forEach { (plan, task) ->
                assertEquals(ChapterTranslationStatus.CANCELLED, ChapterTranslationStore.shared(context).get(task.id)!!.status)
                assertEquals(OrezTaskStatus.CANCELLED, journal.load(plan.id)!!.status)
                assertNull(journal.load(plan.id)!!.pendingControl)
            }
        } finally {
            pending.forEach { journal.cancel(it.first.id) }
            fixtures.forEach { it.cleanup() }
        }
    }

    @Test fun headlessStopCannotMutateANewerUnrelatedNativeGenerationOrReaderOwner() = runBlocking {
        val fixture = Fixture(); val db = fixture.database()
        try {
            val journal = OrezTaskStore(db.tasks()); val nativeStore = ChapterTranslationStore.shared(fixture.context)
            val (plan, task) = fixture.queuedPlan(journal)
            nativeStore.pause(task.id, task.generation)!!
            journal.beginStop(plan.id, OrezPendingControl.CANCEL)!!
            val newIntent = nativeStore.resume(task.id, task.generation)!!
            assertTrue(OrezTaskRecovery.allowChapterWork(fixture.context, nativeStore, newIntent, journal))
            assertEquals(newIntent, nativeStore.get(task.id))
            assertEquals(OrezPendingControl.CANCEL, journal.load(plan.id)!!.pendingControl)
            val reader = nativeStore.start(fixture.chapter, task.config, ownerRequestId = null)
            assertTrue(OrezTaskRecovery.allowChapterWork(fixture.context, nativeStore, reader, journal))
            assertEquals(reader, nativeStore.get(task.id))
            assertEquals(OrezPendingControl.CANCEL, journal.load(plan.id)!!.pendingControl)
        } finally { db.close(); fixture.cleanup() }
    }
}
