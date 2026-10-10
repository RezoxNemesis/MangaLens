package com.mangalens.core.translation

import android.graphics.BitmapFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class NativeIndexWarmState(val running: Boolean = false, val paused: Boolean = false,
    val verifiedTasks: Int = 0, val totalTasks: Int = 0, val blockedTasks: Int = 0, val headerLimitedTasks: Int = 0,
    val receivedBytes: Long = 0, val error: String? = null)

/** One application-owned read lane; pause retains bounded in-process digest state and no open descriptor. */
internal class NativeIndexWarmer(private val native: ChapterTranslationStore,
    private val decodeDimensions: (ByteArray) -> Pair<Int, Int>? = { bytes ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }, private val openHandle: (java.io.File) -> NativeIndexReadHandle = ::AndroidNativeIndexReadHandle) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val controls = Any()
    private var job: Job? = null
    private var jobOwner: Any? = null
    private val warm = hashMapOf<String, NativeIndexWarmReceipt>()
    private val blocked = hashMapOf<String, ChapterTranslationTask>()
    private val headerLimited = hashMapOf<String, ChapterTranslationTask>()
    private class Session(val plan: NativeIndexWarmPlan) {
        var file = 0
        var cursor: NativeIndexIncrementalHasher? = null
        val verified = arrayListOf<NativeIndexVerifiedFile>()
    }
    private var current: Session? = null
    private val mutable = MutableStateFlow(NativeIndexWarmState())
    val state: StateFlow<NativeIndexWarmState> = mutable

    fun start(owner: Any) = synchronized(controls) {
        // Cancellation is retirement; capacity remains owned until the real pass/close finally returns.
        if (job?.isCompleted == false) return@synchronized
        jobOwner = owner
        mutable.value = mutable.value.copy(running = true, paused = false, error = null)
        job = scope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                gate.withLock { blocked.clear(); headerLimited.clear() }
                repeat(4096) {
                    ensureActive()
                    if (android.os.SystemClock.elapsedRealtime() - started >= 30L * 60 * 1000) {
                        mutable.value = mutable.value.copy(error = "Indexing paused at its time limit. Continue to finish saved chapters.")
                        return@launch
                    }
                    if (!pass()) return@launch
                    delay(16)
                }
                mutable.value = mutable.value.copy(error = "Indexing paused at its pass limit. Continue to finish saved chapters.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = "Saved chapter indexing could not continue. Retry or validate its pages in Reader.") }
            finally { mutable.value = mutable.value.copy(running = false, paused = current != null || mutable.value.error != null) }
        }
    }
    fun pause(owner: Any) = synchronized(controls) { if (jobOwner === owner) job?.cancel() }

    suspend fun refreshInventory() = gate.withLock {
        val tasks = native.nativeIndexTasks()
        blocked.entries.removeAll { (_, task) -> !native.nativeIndexTaskCurrent(task) }
        headerLimited.entries.removeAll { (_, task) -> !native.nativeIndexTaskCurrent(task) }
        warm.entries.removeAll { (_, receipt) -> !native.nativeIndexTaskCurrent(receipt.task) || !receipt.isCurrent() }
        publishCounts(tasks.size, 0)
    }

    suspend fun receipt(task: ChapterTranslationTask): NativeIndexWarmReceipt? = gate.withLock {
        warm[task.id]?.takeIf { it.task === task && native.nativeIndexTaskCurrent(task) && it.isCurrent() }
    }

    /** True means more passes remain; every received file byte shares this pass's actual budget. */
    internal suspend fun pass(budget: NativeIndexPassBudget = NativeIndexPassBudget()): Boolean = gate.withLock {
        val tasks = native.nativeIndexTasks()
        blocked.entries.removeAll { (_, task) -> !native.nativeIndexTaskCurrent(task) }
        headerLimited.entries.removeAll { (_, task) -> !native.nativeIndexTaskCurrent(task) }
        warm.entries.removeAll { (_, receipt) -> !native.nativeIndexTaskCurrent(receipt.task) || !receipt.isCurrent() }
        if (current?.plan?.task?.let { !native.nativeIndexTaskCurrent(it) } == true) current = null
        try { while (budget.remaining > 0) {
            currentCoroutineContext().ensureActive()
            val session = current ?: tasks.firstOrNull { it.id !in warm && blocked[it.id] !== it }?.let { task ->
                val plan = native.prepareNativeIndexWarmPlan(task)
                if (plan == null) { blocked[task.id] = task; null } else Session(plan).also { current = it }
            }
            if (session == null) {
                val remaining = tasks.any { it.id !in warm && blocked[it.id] !== it }
                if (remaining) continue
                return@withLock false
            }
            try {
                require(native.nativeIndexTaskCurrent(session.plan.task))
                while (session.file < session.plan.files.size && budget.remaining > 0) {
                    val cursor = session.cursor ?: NativeIndexIncrementalHasher(session.plan.files[session.file], openHandle).also { session.cursor = it }
                    val complete = cursor.pass(budget, decodeDimensions) ?: break
                    session.verified += complete; session.file++; session.cursor = null
                }
                if (session.file == session.plan.files.size) {
                    currentCoroutineContext().ensureActive()
                    val receipt = native.finishNativeIndexWarm(session.plan, session.verified)
                    currentCoroutineContext().ensureActive()
                    if (receipt == null) blocked[session.plan.task.id] = session.plan.task else warm[receipt.task.id] = receipt
                    current = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                blocked[session.plan.task.id] = session.plan.task
                if (failure is NativeIndexHeaderLimitException) headerLimited[session.plan.task.id] = session.plan.task
                current = null
            }
        }
        tasks.any { it.id !in warm && blocked[it.id] !== it }
        } finally { publishCounts(tasks.size, budget.used) }
    }

    private fun publishCounts(total: Int, bytes: Long) {
        mutable.value = mutable.value.copy(verifiedTasks = warm.size, totalTasks = total,
            blockedTasks = blocked.size, headerLimitedTasks = headerLimited.size, receivedBytes = mutable.value.receivedBytes + bytes)
    }

    companion object {
        @Volatile private var shared: Pair<ChapterTranslationStore, NativeIndexWarmer>? = null
        fun shared(native: ChapterTranslationStore): NativeIndexWarmer = synchronized(this) {
            shared?.takeIf { it.first === native }?.second ?: NativeIndexWarmer(native).also { shared = native to it }
        }
    }
}
