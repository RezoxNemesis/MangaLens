package com.mangalens.ui.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import com.mangalens.orez.agent.OrezTaskRecovery
import com.mangalens.core.compute.NativeComputePrecondition
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mangalens.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object SubtitleInputs {
    suspend fun capture(context: Context, requested: SubtitleMediaSource): SubtitleSourceIdentity = withContext(Dispatchers.IO) {
        val source = requested.captureSnapshot()
        require(source.uri.length in 1..16000 && source.headers.size <= 32)
        source.providerCaptions?.let {
            it.validate()
            require(source.sourceResolutionId?.matches(Regex("[a-f0-9]{32}")) == true) { "Provider captions need the current accepted video selection." }
        }
        if (source.captionDocumentOnly) {
            require(source.headers.isEmpty() && source.uri == source.providerCaptions?.sourcePageUrl) {
                "Browser source captions need their captured page and original caption inventory."
            }
        }
        val uri = Uri.parse(source.uri)
        val descriptor = descriptor(source)
        if (source.captionDocumentOnly) {
            // This fingerprints a captured caption source; it is never media-byte or PCM proof.
            return@withContext SubtitleSourceIdentity(source, SubtitleGenerationStore.digest(descriptor + "|caption-document-only-v1"), false)
        }
        if (source.fragmentPlan != null) return@withContext SubtitleFragmentSources.capture(context, source)
        if (uri.scheme in listOf("content", "file", "android.resource") || uri.scheme == null) {
            if (uri.scheme == "content") runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val input = if (uri.scheme == null) File(source.uri).inputStream() else context.contentResolver.openInputStream(uri)
                ?: error("This video is unavailable. Reopen it with the document picker.")
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var total = 0L
            input.use { while (true) {
                currentCoroutineContext().ensureActive()
                val n = it.read(buffer); if (n < 0) break
                total += n; require(total <= 4L * 1024 * 1024 * 1024) { "Use a video under 4 GiB." }; hash.update(buffer, 0, n)
            } }
            require(total > 0) { "Video source is empty." }
            val contentHash = hash.digest().joinToString("") { "%02x".format(it) }
            SubtitleSourceIdentity(source.copy(headers = source.headers.toMap()), SubtitleGenerationStore.digest(descriptor + contentHash), true)
        } else {
            require(uri.scheme == "http" || uri.scheme == "https") { "Unsupported video source." }
            val validator = runCatching { SubtitleNetworkSource(source).use { it.proof() } }.getOrNull()
            SubtitleSourceIdentity(source.copy(headers = source.headers.toMap()), SubtitleGenerationStore.digest(descriptor + validator?.fingerprint.orEmpty()),
                validator != null, validator?.etag, validator?.size, validator?.url)
        }
    }
    private fun descriptor(source: SubtitleMediaSource): String =
        (listOf(source.uri, source.headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }) +
            if (source.providerCaptions == null) emptyList() else listOf(source.sourceResolutionId.orEmpty(), source.providerCaptions.fingerprint()))
            .joinToString("|") { "${it.length}:$it" }
    internal fun fragmentDescriptor(source: SubtitleMediaSource): String {
        val plan = requireNotNull(source.fragmentPlan).captured()
        require(!source.captionDocumentOnly && plan.sourceUrl == source.uri && source.sourceResolutionId?.matches(Regex("[a-f0-9]{32}")) == true) {
            "Fragment audio needs its exact accepted native source resolution."
        }
        return SubtitleGenerationStore.digest(descriptor(source) + listOf("subtitle-fragment-source-v1", plan.version,
            plan.sha256(), source.sourceResolutionId.orEmpty(), source.cacheKey).joinToString("|") { "${it.length}:$it" })
    }
    fun config(context: Context, sourceLanguage: String, options: SubtitleTargetOptions? = null): SubtitleGenerationConfig {
        val model = File(context.filesDir, "speech/whisper.bin")
        return SubtitleGenerationConfig(sourceLanguage = sourceLanguage.trim().lowercase(java.util.Locale.ROOT),
            modelSha256 = model.takeIf { it.isFile && it.length() in 1_000_000..600_000_000 }?.let(SubtitleGenerationStore::fileHash)).let { config -> options?.let(config::withTarget) ?: config }
    }
    fun pcmHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            if (buffer.remaining() < 4) { digest.update(buffer.array(), 0, buffer.position()); buffer.clear() }
            buffer.putFloat(sample)
        }
        digest.update(buffer.array(), 0, buffer.position())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class SubtitleGenerationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("subtitle_task") ?: return@withContext Result.failure()
        val generation = inputData.getString("subtitle_generation") ?: return@withContext Result.failure()
        val store = SubtitleGenerationStore.shared(applicationContext)
        val workerCaller = currentCoroutineContext()
        try {
            var captured = store.get(id)?.takeIf { it.generation == generation } ?: return@withContext Result.success()
            suspend fun checkOwner() {
                if (captured.source.source.captionDocumentOnly &&
                    !store.browserCaptionAuthority.permits(browserCaptionBinding(captured))) throw BrowserCaptionAuthorityRetired()
                enforceSubtitleOwnerGate(store, captured) { OrezTaskRecovery.allowSubtitleWork(applicationContext, store, it) }
                if (captured.source.source.captionDocumentOnly &&
                    !store.browserCaptionAuthority.permits(browserCaptionBinding(captured))) throw BrowserCaptionAuthorityRetired()
            }
            checkOwner()
            check(!captured.source.source.captionDocumentOnly || captured.config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION && captured.config.modelSha256 == null) { "Browser source captions cannot recognise audio." }
            if (!com.mangalens.core.compute.ResourceGovernorRuntime.shared.awaitBoundary(
                    com.mangalens.core.compute.ResourceWorkKind.BACKGROUND) { store.current(id, generation) })
                return@withContext Result.success()
            checkOwner()
            if (captured.config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION && captured.source.source.providerCaptions != null) {
                setForeground(notification(captured, "Checking original provider captions"))
                val translator = SubtitleCueTranslator(applicationContext)
                try {
                    val handled = ProviderCaptionProcessor(store, captured, ::checkOwner,
                        translate = translator::translate, progress = { current ->
                            setForeground(notification(current, "Original provider captions • ${current.translatedCueCount}/${current.sourceCueCount} translated"))
                        }).process()
                    if (handled) return@withContext if (store.get(id)?.status == SubtitleGenerationStatus.COMPLETED) Result.success() else Result.failure()
                } finally { translator.close() }
                check(!captured.source.source.captionDocumentOnly) {
                    "Original source captions are unavailable for this browser video. Audio capture remains a separate explicit action."
                }
                check(captured.config.modelSha256 != null) {
                    "Original provider captions are unavailable. Install or import a multilingual Whisper model to recognise the original audio."
                }
            }
            setForeground(notification(captured, "Checking video and speech model"))
            SubtitleFragmentSources.withSource(applicationContext, captured.source, current = ::checkOwner) { provenSource ->
            if (captured.source.source.fragmentPlan != null)
                captured = store.bindFragmentSource(id, generation, provenSource) ?: return@withSource Result.success()
            withContext(NativeComputePrecondition { waited ->
                workerCaller.ensureActive()
                checkOwner()
                if (waited) {
                    val fresh = SubtitleInputs.capture(applicationContext, captured.source.source)
                    when (assessSubtitleSource(captured.source, fresh)) {
                        SubtitleSourceCheck.UNVERIFIED -> {
                            store.fail(id, generation, "Cannot verify this video after waiting for native compute. Saved speech has been kept.", requireValidation = true)
                            throw SubtitleNetworkUnverified("Cannot verify this video after waiting for native compute. Saved speech has been kept.")
                        }
                        SubtitleSourceCheck.CHANGED -> {
                            store.fail(id, generation, "Video changed while waiting for native compute. Generate a new task.", invalidate = true)
                            throw SubtitleNetworkChanged("Video changed while waiting for native compute. Generate a new task.")
                        }
                        SubtitleSourceCheck.MATCH -> Unit
                    }
                    val sourceOnlyRepair = captured.config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION && captured.audioComplete &&
                        !captured.pcmValidationRequired
                    if (sourceOnlyRepair && !canTrustSubtitleSource(captured.source, fresh)) {
                        store.fail(id, generation, "Saved original speech needs fresh source proof before target repair.", requireValidation = true)
                        error("Saved original speech needs fresh source proof before target repair.")
                    } else if (!sourceOnlyRepair && SubtitleInputs.config(applicationContext, captured.config.sourceLanguage).modelSha256 != captured.config.modelSha256) {
                        store.fail(id, generation, "Speech model changed while waiting for native compute. Saved windows have been kept.")
                        error("Speech model changed while waiting for native compute. Saved windows have been kept.")
                    }
                    checkOwner()
                    workerCaller.ensureActive()
                }
            }) {
                generationLane.withLock {
                    checkOwner()
                    val source = SubtitleInputs.capture(applicationContext, captured.source.source)
                    when (assessSubtitleSource(captured.source, source)) {
                        SubtitleSourceCheck.UNVERIFIED -> {
                            store.fail(id, generation, "Cannot verify this online video version. Completed windows have been kept; retry when the source is available.", requireValidation = true)
                            return@withLock Result.failure()
                        }
                        SubtitleSourceCheck.CHANGED -> {
                            store.fail(id, generation, "Video source changed. Generate a new subtitle task for the current video.", invalidate = true)
                            return@withLock Result.failure()
                        }
                        SubtitleSourceCheck.MATCH -> Unit
                    }
                    if (canTrustSubtitleSource(captured.source, source) || captured.windows.isEmpty()) store.confirmValidated(id, generation)
                    val targetOnly = captured.config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION && captured.audioComplete &&
                        canTrustSubtitleSource(captured.source, source) && !captured.pcmValidationRequired
                    if (targetOnly) {
                        checkOwner()
                        val task = store.running(id, generation) ?: return@withLock Result.success()
                        val translator = SubtitleCueTranslator(applicationContext)
                        try {
                            val processor = SubtitleWindowProcessor(store, task, ::checkOwner, translator::translate)
                            for (window in task.windows) {
                                if (!com.mangalens.core.compute.ResourceGovernorRuntime.shared.awaitBoundary(
                                        com.mangalens.core.compute.ResourceWorkKind.BACKGROUND) { store.current(id, generation) })
                                    throw CancellationException("Subtitle generation was paused or replaced.")
                                val progress = processor.translateSaved(window)
                                progress.error?.let { store.noteTargetFailure(id, generation, it) }
                                if (progress.failedTargets >= 3) {
                                    store.fail(id, generation, progress.error ?: "The translation provider could not complete this window.")
                                    return@withLock Result.failure()
                                }
                                setForeground(notification(store.get(id) ?: task, "Repairing saved speech translations • window ${window.index + 1}"))
                            }
                            val finalSource = SubtitleInputs.capture(applicationContext, task.source.source)
                            when (assessSubtitleSource(task.source, finalSource)) {
                                SubtitleSourceCheck.UNVERIFIED -> {
                                    store.fail(id, generation, "Cannot verify the final video version. Original speech and target checkpoints have been kept.", requireValidation = true)
                                    return@withLock Result.failure()
                                }
                                SubtitleSourceCheck.CHANGED -> {
                                    store.fail(id, generation, "Video changed while repairing translations. Generate a new task.", invalidate = true)
                                    return@withLock Result.failure()
                                }
                                SubtitleSourceCheck.MATCH -> Unit
                            }
                            checkOwner()
                            store.finish(id, generation)
                            return@withLock Result.success()
                        } finally { translator.close() }
                    }
                    val installed = SubtitleInputs.config(applicationContext, captured.config.sourceLanguage)
                    check(installed.modelSha256 != null) { "Install or import a multilingual Whisper model first." }
                    check(installed.modelSha256 == captured.config.modelSha256) { "Speech model changed. Generate a new task to use the installed model." }
                    checkOwner()
                    val task = store.running(id, generation) ?: return@withLock Result.success()
                    val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val engine = VideoSpeechEngine(applicationContext, workerScope)
                    try {
                        engine.loadInstalled()
                        check(engine.state.value.ready) { engine.state.value.status }
                        check(SubtitleInputs.config(applicationContext, captured.config.sourceLanguage).modelSha256 == captured.config.modelSha256) {
                            "Speech model changed while loading. Generate a task for the installed model."
                        }
                        var index = 0
                        var requestedLanguage = task.detectedLanguage ?: task.config.sourceLanguage
                        val translator = if (task.config.pipeline == SubtitlePipeline.SOURCE_TRANSLATION) SubtitleCueTranslator(applicationContext) else null
                        val processor = translator?.let { SubtitleWindowProcessor(store, task, ::checkOwner, it::translate) }
                        try {
                        checkOwner()
                        SubtitleAudioDecoder(applicationContext).decode(task.source.source, task.source.strongEtag, task.source.networkSize, task.source.networkUrl) { audio, startMs, progress, durationMs ->
                            currentCoroutineContext().ensureActive()
                            checkOwner()
                            if (!com.mangalens.core.compute.ResourceGovernorRuntime.shared.awaitBoundary(
                                    com.mangalens.core.compute.ResourceWorkKind.BACKGROUND) { store.current(id, generation) })
                                throw CancellationException("Subtitle generation was paused or replaced.")
                            checkOwner()
                            val endMs = startMs + audio.size * 1000L / 16000
                            val hash = SubtitleInputs.pcmHash(audio)
                            if (processor != null) {
                                val progress = processor.process(index, startMs, endMs, hash, durationMs, !SpeechWindowPolicy.hasActivity(audio)) {
                                    val cues = withTimeout(120_000) { engine.inferOriginalChunk(audio, startMs, task.config.sourceLanguage, task.config.threads) }
                                    SubtitleRecognizedWindow(cues, engine.detectedLanguageSnapshot()?.takeIf { it.matches(Regex("[a-z]{2,3}")) }
                                        ?: task.config.sourceLanguage.takeIf { it != "auto" })
                                }
                                checkOwner()
                                if (index == task.windows.lastIndex) store.confirmValidated(id, generation, pcmVerified = true)
                                progress.error?.let { store.noteTargetFailure(id, generation, it) }
                                if (progress.failedTargets >= 3) error(progress.error ?: "The translation provider could not complete this window.")
                            } else {
                                val previous = task.windows.getOrNull(index)
                                if (previous != null) {
                                    if (previous.startMs != startMs || previous.endMs != endMs || previous.pcmSha256 != hash) {
                                        store.fail(id, generation, "Decoded video audio changed. Generate a fresh task for this source.", invalidate = true)
                                        throw CancellationException("Changed source audio invalidated its old checkpoints.")
                                    }
                                    checkOwner()
                                    if (index == task.windows.lastIndex) store.confirmValidated(id, generation, pcmVerified = true)
                                } else {
                                    val silent = !SpeechWindowPolicy.hasActivity(audio)
                                    val cues = if (silent) emptyList() else withTimeout(120_000) {
                                        engine.inferEnglishChunk(audio, startMs, requestedLanguage, task.config.threads)
                                    }
                                    val detected = engine.detectedLanguageSnapshot()
                                    if (requestedLanguage == "auto" && detected != null) requestedLanguage = detected
                                    checkOwner()
                                    if (!store.checkpoint(id, generation, SubtitleWindow(index, startMs, endMs, hash, cues, silent), durationMs, detected))
                                        throw CancellationException("Subtitle checkpoint generation was replaced.")
                                }
                            }
                            index++
                            val latest = store.get(id)?.takeIf { it.generation == generation } ?: throw CancellationException()
                            setProgress(workDataOf("processed_windows" to index, "progress" to progress))
                            setForeground(notification(latest, "${index} windows checked • ${(progress * 100).toInt()}%"))
                        }
                        check(index >= task.windows.size) { "Audio ended before all saved windows could be verified." }
                        val finalSource = SubtitleInputs.capture(applicationContext, task.source.source)
                        when (assessSubtitleSource(task.source, finalSource)) {
                            SubtitleSourceCheck.UNVERIFIED -> {
                                store.fail(id, generation, "Cannot verify the final online video version. Completed windows have been kept; resume to verify them before export.", requireValidation = true)
                                return@withLock Result.failure()
                            }
                            SubtitleSourceCheck.CHANGED -> {
                                store.fail(id, generation, "Video changed during subtitle generation. Generate a fresh task.", invalidate = true)
                                return@withLock Result.failure()
                            }
                            SubtitleSourceCheck.MATCH -> Unit
                        }
                        check(SubtitleInputs.config(applicationContext, task.config.sourceLanguage).modelSha256 == task.config.modelSha256) {
                            "Speech model changed during generation. Completed windows have been kept."
                        }
                        checkOwner()
                        store.completeAudio(id, generation, index)
                        checkOwner()
                        store.finish(id, generation)
                        Result.success()
                        } finally { translator?.close() }
                    } finally {
                        withContext(NonCancellable) {
                            try { engine.close() }
                            catch (failure: Throwable) {
                                currentCoroutineContext()[SubtitleFragmentSources.HeldFragmentSubtitleSource]?.retainUnreleased(engine)
                                throw failure
                            } finally { workerScope.cancel() }
                        }
                    }
                }
            }
            }
        } catch (_: BrowserCaptionAuthorityRetired) {
            // No browser authority survives process death or source retirement. The exact old
            // generation is retired on IO; saved journals cannot revive a live operation.
            store.retireBrowserGeneration(id, generation)
            Result.success()
        } catch (blocked: SubtitleOwnedWorkBlocked) {
            // A workflow stop is authoritative. An unacknowledged IO failure
            // defers work without replacing the pending stop with native FAILED.
            if (blocked.retry) {
                runCatching { store.interrupted(id, generation) }
                if (store.current(id, generation)) Result.retry() else Result.success()
            } else Result.success()
        } catch (deferred: com.mangalens.core.compute.ResourcePausedException) {
            if (store.deferForResources(id, generation, deferred.message ?: "Waiting for device resources; saved progress will resume automatically."))
                Result.retry() else Result.success()
        } catch (unverified: SubtitleNetworkUnverified) {
            store.fail(id, generation, unverified.message ?: "Video byte version could not be verified.", requireValidation = true)
            Result.failure()
        } catch (changed: SubtitleNetworkChanged) {
            store.fail(id, generation, changed.message ?: "Video bytes changed during generation.", invalidate = true)
            Result.failure()
        } catch (timeout: TimeoutCancellationException) {
            store.fail(id, generation, "A speech window exceeded its two-minute budget. Resume with the same model, or generate a new task with a smaller model.")
            Result.failure()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { store.interrupted(id, generation) } }
            throw cancelled
        } catch (failure: Exception) {
            store.fail(id, generation, failure.message ?: "Subtitle generation failed. Completed windows have been kept.")
            Result.failure()
        }
    }
    private fun notification(task: SubtitleGenerationTask, stage: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("full_subtitles", "Video subtitle generation", NotificationManager.IMPORTANCE_LOW))
        val notificationId = 200_000 + ((task.id + task.generation).hashCode() and 0x0fffffff)
        val open = PendingIntent.getActivity(applicationContext, notificationId, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "full_subtitles").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Subtitles • " + task.source.source.label.take(70)).setContentText(stage).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).build()
        return ForegroundInfo(notificationId, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }
    companion object { private val generationLane = Mutex() }
}

object SubtitleGenerationJobs {
    suspend fun start(context: Context, source: SubtitleSourceIdentity, config: SubtitleGenerationConfig, force: Boolean = false,
        ownerRequestId: String? = null, allowOwnerReplacement: Boolean = true): SubtitleGenerationTask =
        startCaptured(context, source, config, force, ownerRequestId, allowOwnerReplacement, null)
    internal suspend fun startBrowser(context: Context, source: SubtitleSourceIdentity, config: SubtitleGenerationConfig,
        operation: BrowserCaptionOperation): SubtitleGenerationTask =
        startCaptured(context, source, config, true, null, false, operation)
    private suspend fun startCaptured(context: Context, source: SubtitleSourceIdentity, config: SubtitleGenerationConfig, force: Boolean,
        ownerRequestId: String?, allowOwnerReplacement: Boolean, browserOperation: BrowserCaptionOperation?): SubtitleGenerationTask =
        withContext(NonCancellable + Dispatchers.IO) {
            val store = SubtitleGenerationStore.shared(context)
            val outcome = store.startResult(source, config, force, ownerRequestId, allowOwnerReplacement, browserOperation)
            val task = outcome.task
            outcome.replaced?.takeIf { it.generation != task.generation }?.let { WorkManager.getInstance(context).cancelUniqueWork(name(it)).awaitCompletion() }
            enqueue(context, store, task)
            store.commandSnapshot(task)
        }
    suspend fun pause(context: Context, id: String, generation: String): SubtitleGenerationTask? = withContext(NonCancellable + Dispatchers.IO) {
        val store = SubtitleGenerationStore.shared(context); val task = store.pause(id, generation) ?: return@withContext null
        WorkManager.getInstance(context).cancelUniqueWork(name(task)).awaitCompletion(); store.commandSnapshot(task)
    }
    suspend fun resume(context: Context, id: String, generation: String): SubtitleGenerationTask? =
        resumeCaptured(context, id, generation, null)
    internal suspend fun resumeBrowser(context: Context, id: String, generation: String, operation: BrowserCaptionOperation): SubtitleGenerationTask? =
        resumeCaptured(context, id, generation, operation)
    private suspend fun resumeCaptured(context: Context, id: String, generation: String,
        browserOperation: BrowserCaptionOperation?): SubtitleGenerationTask? = withContext(NonCancellable + Dispatchers.IO) {
        val store = SubtitleGenerationStore.shared(context); val old = store.get(id)?.takeIf { it.generation == generation } ?: return@withContext null
        val task = store.resume(id, generation, browserOperation) ?: return@withContext null
        WorkManager.getInstance(context).cancelUniqueWork(name(old)).awaitCompletion(); enqueue(context, store, task); store.commandSnapshot(task)
    }
    suspend fun cancel(context: Context, id: String, generation: String): SubtitleGenerationTask? = withContext(NonCancellable + Dispatchers.IO) {
        val store = SubtitleGenerationStore.shared(context); val task = store.cancel(id, generation) ?: return@withContext null
        WorkManager.getInstance(context).cancelUniqueWork(name(task)).awaitCompletion(); store.commandSnapshot(task)
    }
    suspend fun recoverPending(context: Context) = withContext(Dispatchers.IO) {
        val store = SubtitleGenerationStore.shared(context)
        for (task in store.states.value) if (store.current(task.id, task.generation)) runCatching { enqueue(context, store, task) }
    }
    private suspend fun enqueue(context: Context, store: SubtitleGenerationStore, task: SubtitleGenerationTask) {
        if (!store.current(task.id, task.generation)) return
        try {
            val request = OneTimeWorkRequestBuilder<SubtitleGenerationWorker>().setInputData(workDataOf("subtitle_task" to task.id,
                "subtitle_generation" to task.generation)).addTag("full-subtitles").addTag(name(task))
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(task), ExistingWorkPolicy.KEEP, request).awaitCompletion()
        } catch (failure: Exception) { store.fail(task.id, task.generation, "Unable to schedule subtitles: " + (failure.message ?: "WorkManager failed")); throw failure }
    }
    private fun name(task: SubtitleGenerationTask) = "full-subtitles-${task.id}-${task.generation}"
    private suspend fun Operation.awaitCompletion() = suspendCancellableCoroutine<Unit> { continuation ->
        result.addListener({ try { result.get(); if (continuation.isActive) continuation.resume(Unit) }
        catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(if (failure is ExecutionException) failure.cause ?: failure else failure) } }, Executor { it.run() })
    }
}
