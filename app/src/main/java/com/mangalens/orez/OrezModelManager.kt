package com.mangalens.orez

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mangalens.oreznative.OrezNativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class OrezModelState(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val bytes: Long = 0L,
    val total: Long = OrezModelCatalog.lite.bytes,
    val error: String? = null,
    val optimized: Boolean = false,
    val legacyInstalled: Boolean = false,
    val selectedTier: OrezModelTier = OrezModelTier.LITE,
    val selectedInstalled: Boolean = false,
    val activeTier: OrezModelTier? = null,
    val installedTiers: Set<OrezModelTier> = emptySet(),
    val verifying: Boolean = false,
    val ready: Boolean = false,
    val rollbackAvailable: Boolean = false
) {
    val progress: Float
        get() = if (total <= 0L) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)

    val selectedDescriptor: OrezModelDescriptor?
        get() = OrezModelCatalog.descriptor(selectedTier)
}

class OrezModelManager private constructor(val context: Context, recoverTransfers: Boolean) {
    constructor(context: Context) : this(context, true)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val transfers = OrezModelTransferPreferences(context)
    private val directory = File(context.filesDir, "orez_models")
    private val store = stores.computeIfAbsent(directory.canonicalPath) { OrezModelActivationStore(directory) }
    private val verificationMutex = verificationLocks.computeIfAbsent(directory.canonicalPath) { Mutex() }
    @Volatile private var verificationJob: Job? = null
    @Volatile private var verifying = false
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<OrezModelState> = _state

    init {
        scheduleVerification()
        if (recoverTransfers) synchronizeWorkState(emptyList())
    }

    val optimizedModelFile: File
        get() = fileFor(OrezModelCatalog.lite)

    val coreModelFile: File
        get() = fileFor(OrezModelCatalog.core)

    val legacyModelFile: File
        get() = File(context.filesDir, "orez_models/qwen2.5-0.5b-q6_k.gguf")

    val modelFile: File
        get() = runtimeModelFiles().firstOrNull()
            ?: fileFor(OrezModelCatalog.descriptor(_state.value.selectedTier) ?: OrezModelCatalog.lite)

    fun fileFor(descriptor: OrezModelDescriptor): File =
        File(context.filesDir, "orez_models/" + descriptor.fileName)

    fun refresh() {
        _state.value = readState()
        scheduleVerification()
    }

    /** Explicit health checks rehash on IO, without downloading or changing the selection. */
    fun recheck() { scheduleVerification(force = true) }

    @Synchronized private fun scheduleVerification(force: Boolean = false) {
        if (verificationJob?.isActive == true || (!force && !needsVerification())) return
        verifying = true
        _state.value = readState()
        verificationJob = verificationScope.launch { verifyExistingModels(force) }
    }

    private fun needsVerification(): Boolean = store.needsVerification() ||
        OrezModelCatalog.availableDescriptors.any { descriptor ->
            !store.hasJournal(descriptor.tier.name) &&
                ((fileFor(descriptor).isFile && !store.isChecked(fileFor(descriptor))) || store.savedCandidate(descriptor.tier.name, descriptor) != null)
        } || (!store.hasJournal(OrezModelActivationStore.LEGACY_SLOT) && legacyModelFile.isFile && !store.isChecked(legacyModelFile))

    /** Existing installs are verified in place; legacy data and prior weights are retained. */
    suspend fun verifyExistingModels(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        verificationMutex.withLock {
            verifying = true
            _state.value = readState()
            try {
                platformIssue()?.let { issue ->
                    prefs.edit().putString(KEY_VERIFICATION_ERROR, issue).apply()
                    return@withLock false
                }
                val operation = currentCoroutineContext()
                val checkpoint = { operation.ensureActive() }
                val issues = store.revalidate(checkpoint, force).toMutableList()
                for (descriptor in OrezModelCatalog.availableDescriptors) {
                    val slot = descriptor.tier.name
                    if (store.runtimeFiles(slot).isNotEmpty()) continue
                    val original = fileFor(descriptor)
                    val candidate = original.takeIf { it.isFile } ?: store.savedCandidate(slot, descriptor)
                    if (candidate == null) continue
                    if (!force && store.isChecked(candidate)) {
                        store.checkIssue(candidate)?.let(issues::add)
                        continue
                    }
                    try { store.adoptExisting(slot, candidate, descriptor, checkpoint) }
                    catch (failure: java.io.IOException) { issues += failure.message ?: "Model verification failed." }
                    catch (failure: OrezModelCompatibilityException) { issues += failure.message ?: "Model compatibility check failed." }
                }
                if (store.runtimeFiles(OrezModelActivationStore.LEGACY_SLOT).isEmpty() && legacyModelFile.isFile &&
                    (force || !store.isChecked(legacyModelFile))) {
                    try { store.adoptLegacy(legacyModelFile, LEGACY_MODEL_BYTES, checkpoint) }
                    catch (failure: java.io.IOException) { issues += failure.message ?: "Legacy model verification failed." }
                    catch (failure: OrezModelCompatibilityException) { issues += failure.message ?: "Legacy model compatibility check failed." }
                } else if (store.runtimeFiles(OrezModelActivationStore.LEGACY_SLOT).isEmpty() && legacyModelFile.isFile) {
                    store.checkIssue(legacyModelFile)?.let(issues::add)
                }
                prefs.edit().putString(KEY_VERIFICATION_ERROR, issues.distinct().joinToString("\n").takeIf { it.isNotBlank() }).apply()
                if (force) prefs.edit().remove(KEY_UNAVAILABLE_PATH).remove(KEY_UNAVAILABLE_UNTIL).remove(KEY_RUNTIME_ERROR).apply()
                runtimeModelFiles().isNotEmpty()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                prefs.edit().putString(KEY_VERIFICATION_ERROR, "Could not save model verification. Free storage and recheck model health; existing models were kept. " + (failure.message ?: "")).apply()
                runtimeModelFiles().isNotEmpty()
            } finally {
                verifying = false
                _state.value = readState()
            }
        }
    }

    /** Pins and GGUF compatibility are checked before the atomic activation journal changes. */
    suspend fun activateVerifiedCandidate(candidate: File, descriptor: OrezModelDescriptor, ownershipCheckpoint: () -> Unit = {}): File = withContext(Dispatchers.IO) {
        verificationMutex.withLock {
            require(descriptor == OrezModelCatalog.descriptor(descriptor.tier)) { "Only pinned production OREZ descriptors may be activated." }
            platformIssue()?.let { throw OrezModelCompatibilityException(it) }
            val operation = currentCoroutineContext()
            val checkpoint = { operation.ensureActive(); ownershipCheckpoint() }
            val artifact = if (candidate.canonicalFile == fileFor(descriptor).canonicalFile) {
                store.adoptExisting(descriptor.tier.name, candidate, descriptor, checkpoint)
            } else store.activate(descriptor.tier.name, candidate, descriptor, checkpoint)
            prefs.edit().remove(KEY_VERIFICATION_ERROR).remove(KEY_RUNTIME_ERROR)
                .remove(KEY_UNAVAILABLE_PATH).remove(KEY_UNAVAILABLE_UNTIL).apply()
            _state.value = readState()
            artifact.file
        }
    }

    internal fun savedCandidate(descriptor: OrezModelDescriptor): File? =
        store.runtimeArtifacts(descriptor.tier.name).firstOrNull { it.sha256 == descriptor.sha256 }?.file
            ?: store.savedCandidate(descriptor.tier.name, descriptor)

    /** Rollback is local and preserves the user's tier and every model file. */
    suspend fun rollback(tier: OrezModelTier = readSelectedTier()): Boolean = withContext(Dispatchers.IO) {
        verificationMutex.withLock {
            val operation = currentCoroutineContext()
            val restored = try { store.rollback(tier.name) { operation.ensureActive() } }
            catch (failure: java.io.IOException) {
                prefs.edit().putString(KEY_RUNTIME_ERROR, "Could not save rollback. Free storage and retry; the active model was kept. " + (failure.message ?: "")).apply()
                _state.value = readState()
                return@withLock false
            }
            prefs.edit().putString(KEY_RUNTIME_ERROR, if (restored) "Previous verified model restored. The replacement was kept for review." else "No verified previous model is available. Recheck model health or resume the selected download.")
                .remove(KEY_UNAVAILABLE_PATH).remove(KEY_UNAVAILABLE_UNTIL).apply()
            _state.value = readState()
            restored
        }
    }

    internal suspend fun recordRuntimeLoaded(file: File) = withContext(Dispatchers.IO) {
        verificationMutex.withLock {
            try { store.markWorking(file) }
            catch (failure: java.io.IOException) {
                prefs.edit().putString(KEY_RUNTIME_ERROR, "The model loaded, but its health record could not be saved. Free storage and recheck model health.").apply()
                _state.value = readState()
                return@withLock
            }
            if (prefs.getString(KEY_UNAVAILABLE_PATH, null) == file.absolutePath) {
                prefs.edit().remove(KEY_UNAVAILABLE_PATH).remove(KEY_UNAVAILABLE_UNTIL).remove(KEY_RUNTIME_ERROR).apply()
            }
            _state.value = readState()
        }
    }

    internal fun recordRuntimeUnavailable(file: File?, message: String) {
        prefs.edit().putString(KEY_RUNTIME_ERROR, message)
            .putString(KEY_UNAVAILABLE_PATH, file?.absolutePath)
            .putLong(KEY_UNAVAILABLE_UNTIL, System.currentTimeMillis() + 30_000L).apply()
        _state.value = readState()
    }

    @Suppress("UNUSED_PARAMETER")
    fun synchronizeWorkState(work: List<androidx.work.WorkInfo>) {
        submitTransferCommand {
            // Observed lists may predate enqueue. Only a fresh database read can authorize replay.
            val current = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME)
                .get(30, java.util.concurrent.TimeUnit.SECONDS)
            val pending = transfers.synchronize(current) ?: return@submitTransferCommand
            val tier = pending.tier?.let { runCatching { OrezModelTier.valueOf(it) }.getOrNull() }
                ?.takeIf { OrezModelCatalog.descriptor(it) != null }
            if (tier == null || pending.id == null) {
                pending.id?.let { transfers.failed(it, "Saved model transfer settings are unavailable. Download again to resume the saved partial.") }
                return@submitTransferCommand
            }
            transfers.checkpoint(pending.id)
            enqueueSavedRequest(UUID.fromString(pending.id), tier)
        }
    }

    fun pause() {
        val pauseEpoch = transfers.requestPause()
        _state.value = _state.value.copy(downloading = false, error = "Pausing the saved model download.")
        submitTransferCommand {
            // The inactive record reaches disk before WorkManager cancellation can persist.
            transfers.pause(pauseEpoch)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                .result.get(30, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    fun selectTier(tier: OrezModelTier) {
        require(OrezModelCatalog.descriptor(tier) != null) {
            "OREZ model tier is not production-ready yet: " + tier.name
        }
        prefs.edit().putString(KEY_SELECTED_TIER, tier.name).apply()
        refresh()
    }

    fun enqueue(tier: OrezModelTier = _state.value.selectedTier) {
        val descriptor = OrezModelCatalog.descriptor(tier)
            ?: throw IllegalArgumentException("OREZ model tier is not available yet: " + tier.name)
        // An active transfer retains its progress/tier metadata and durable request identity.
        if (_state.value.downloading) return

        prefs.edit().putString(KEY_SELECTED_TIER, tier.name).apply()
        val transferId = UUID.randomUUID()
        _state.value = readState().copy(downloading = true, error = null)
        submitTransferCommand {
            val existingPart = File(fileFor(descriptor).parentFile, descriptor.fileName + ".part")
            if (!transfers.prepare(transferId.toString(), tier, existingPart.length(), descriptor.bytes)) return@submitTransferCommand
            enqueueSavedRequest(transferId, tier)
        }
    }

    private fun enqueueSavedRequest(transferId: UUID, tier: OrezModelTier) {
        val request = OneTimeWorkRequestBuilder<OrezModelDownloadWorker>()
            .setId(transferId)
            .setInputData(workDataOf(OrezModelDownloadWorker.KEY_ID to transferId.toString(), OrezModelDownloadWorker.KEY_TIER to tier.name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(15))
            .addTag(WORK_NAME).build()
        transfers.checkpoint(transferId.toString())
        // Pause cancellation is asynchronous. REPLACE queues the new ID even when the previous
        // request has not yet reached CANCELLED; the old native read retains its writer lane.
        val policy = ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
            .result.get(30, java.util.concurrent.TimeUnit.SECONDS)
        transfers.enqueued(transferId.toString())
    }

    private fun submitTransferCommand(action: () -> Unit) {
        transferCommands.trySend {
            try { action() }
            catch (failure: Exception) {
                prefs.edit().putString(KEY_ERROR, "Could not save model transfer controls. The partial and working models were kept. " + (failure.message ?: "Retry Download or Pause.")).apply()
            } finally { _state.value = readState() }
        }.getOrThrow()
    }

    fun isReady(): Boolean = runtimeModelFiles().any { memoryIssue(it) == null }

    fun isInstalled(tier: OrezModelTier): Boolean {
        if (OrezModelCatalog.descriptor(tier) == null || platformIssue() != null) return false
        return store.runtimeFiles(tier.name).isNotEmpty()
    }

    /**
     * Models are returned in safe execution order.
     *
     * The user's selected tier is attempted first. Core can fall back to Lite when
     * memory pressure makes the larger mmap unsafe. Selecting Lite never silently
     * upgrades to Core.
     */
    fun runtimeModelFiles(): List<File> {
        if (platformIssue() != null) return emptyList()
        val selected = readSelectedTier()
        val candidates = mutableListOf<File>()

        OrezModelCatalog.descriptor(selected)?.let { descriptor ->
            candidates += store.runtimeFiles(descriptor.tier.name)
        }

        if (selected != OrezModelTier.LITE && isInstalled(OrezModelTier.LITE)) {
            candidates += store.runtimeFiles(OrezModelTier.LITE.name)
        }

        candidates += store.runtimeFiles(OrezModelActivationStore.LEGACY_SLOT)
        val unavailable = prefs.getString(KEY_UNAVAILABLE_PATH, null)
            .takeIf { System.currentTimeMillis() < prefs.getLong(KEY_UNAVAILABLE_UNTIL, 0L) }
        return candidates.distinctBy { it.absolutePath }.filterNot { it.absolutePath == unavailable }
    }

    /** Only process-verified activation artifacts can satisfy a durable model pin. */
    internal fun verifiedModelCandidates(pinnedModel: OrezModelPin? = null): List<OrezModelCandidate> {
        if (platformIssue() != null) return emptyList()
        val verified = (OrezModelCatalog.availableDescriptors.flatMap { store.runtimeArtifacts(it.tier.name) } +
            store.runtimeArtifacts(OrezModelActivationStore.LEGACY_SLOT)).distinctBy { it.file.canonicalPath }
        // A captured job may keep Lite after the user selects Core, or vice versa.
        // Pinned resolution never walks ambient fallback order or unverified saved candidates.
        val ordered = if (pinnedModel != null) verified else runtimeModelFiles().mapNotNull { file ->
            verified.firstOrNull { it.file.canonicalPath == file.canonicalPath }
        }
        return OrezPinnedModelPolicy.select(pinnedModel, ordered.map {
            OrezModelCandidate(it.file, OrezModelPin(it.modelId, it.sha256, it.bytes))
        })
    }

    internal fun platformIssue(): String? = OrezModelRuntimePolicy.platformIssue(
        android.os.Build.VERSION.SDK_INT, android.os.Build.SUPPORTED_ABIS?.toList().orEmpty(), OrezNativeEngine.available
    )

    internal fun memoryIssue(file: File, alreadyLoaded: Boolean = false): String? {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val shared = alreadyLoaded || OrezNativeEngine.sharedModelPath == file.canonicalPath
        return OrezModelRuntimePolicy.memoryIssue(file.length(), info.availMem, info.lowMemory, shared)
    }

    private fun readState(): OrezModelState {
        val selected = readSelectedTier()
        val installedTiers = OrezModelCatalog.availableDescriptors
            .filter { descriptor ->
                isInstalled(descriptor.tier)
            }
            .mapTo(linkedSetOf()) { it.tier }

        val legacy = platformIssue() == null && store.runtimeFiles(OrezModelActivationStore.LEGACY_SLOT).isNotEmpty()
        val selectedDescriptor = OrezModelCatalog.descriptor(selected) ?: OrezModelCatalog.lite
        val selectedFile = store.runtimeFiles(selected.name).firstOrNull() ?: fileFor(selectedDescriptor)
        val downloading = prefs.getBoolean(KEY_DOWNLOADING, false)

        val downloadingTier = runCatching {
            OrezModelTier.valueOf(
                prefs.getString(KEY_DOWNLOADING_TIER, selected.name) ?: selected.name
            )
        }.getOrDefault(selected)
        val downloadDescriptor = OrezModelCatalog.descriptor(downloadingTier) ?: selectedDescriptor

        val runtimeFiles = runtimeModelFiles()
        val activeTier = when {
            runtimeFiles.any { it in store.runtimeFiles(selected.name) } -> selected
            runtimeFiles.any { it in store.runtimeFiles(OrezModelTier.LITE.name) } -> OrezModelTier.LITE
            else -> null
        }

        return OrezModelState(
            installed = installedTiers.isNotEmpty() || legacy,
            downloading = downloading,
            bytes = when {
                downloading -> prefs.getLong(KEY_BYTES, 0L)
                selectedFile.exists() -> selectedFile.length()
                else -> 0L
            },
            total = if (downloading) {
                prefs.getLong(KEY_TOTAL, downloadDescriptor.bytes)
            } else {
                selectedDescriptor.bytes
            },
            error = prefs.getString(KEY_ERROR, null) ?: prefs.getString(KEY_VERIFICATION_ERROR, null)
                ?: prefs.getString(KEY_RUNTIME_ERROR, null),
            optimized = OrezModelTier.LITE in installedTiers,
            legacyInstalled = legacy,
            selectedTier = selected,
            selectedInstalled = selected in installedTiers,
            activeTier = activeTier,
            installedTiers = installedTiers,
            verifying = verifying,
            ready = runtimeFiles.any { memoryIssue(it) == null },
            rollbackAvailable = store.canRollback(selected.name)
        )
    }

    private fun readSelectedTier(): OrezModelTier = runCatching {
        OrezModelTier.valueOf(
            prefs.getString(KEY_SELECTED_TIER, OrezModelTier.LITE.name)
                ?: OrezModelTier.LITE.name
        )
    }.getOrDefault(OrezModelTier.LITE).let { tier ->
        if (OrezModelCatalog.descriptor(tier) != null) tier else OrezModelTier.LITE
    }

    companion object {
        const val MODEL_BYTES = 491400032L
        const val MODEL_SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
        const val MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/872f8a96064a1242ac3a3359cad77c3042548405/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
        const val LEGACY_MODEL_BYTES = 650379104L

        const val WORK_NAME = "orez-model"

        private const val PREFS_NAME = "orez_model"
        private const val KEY_SELECTED_TIER = "selected_tier"
        private const val KEY_DOWNLOADING_TIER = "downloading_tier"
        private const val KEY_DOWNLOADING = "downloading"
        private const val KEY_BYTES = "bytes"
        private const val KEY_TOTAL = "total"
        private const val KEY_ERROR = "error"
        private const val KEY_VERIFICATION_ERROR = "verification_error"
        private const val KEY_RUNTIME_ERROR = "runtime_error"
        private const val KEY_UNAVAILABLE_PATH = "unavailable_path"
        private const val KEY_UNAVAILABLE_UNTIL = "unavailable_until"
        private val stores = ConcurrentHashMap<String, OrezModelActivationStore>()
        private val verificationLocks = ConcurrentHashMap<String, Mutex>()
        private val verificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val transferCommands = Channel<() -> Unit>(Channel.UNLIMITED)
        private val transferControlJob = verificationScope.launch {
            for (command in transferCommands) command()
        }
        internal fun forWorker(context: Context): OrezModelManager = OrezModelManager(context, false)
    }
}
