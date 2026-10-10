package com.mangalens.ui.ai.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.ui.video.VideoSpeechEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class OrezVoicePhase { IDLE, VERIFYING, LOADING, RECORDING, TRANSCRIBING, STOPPING, READY, MODEL_REQUIRED, ERROR, RETAINED }
internal data class OrezVoiceState(val token: Long = 0, val phase: OrezVoicePhase = OrezVoicePhase.IDLE,
    val message: String = "Offline microphone input • tap to record up to 15 seconds", val capturedMs: Long = 0,
    val transcript: String? = null)

/** Process-owned one-producer runtime. All Contexts are application-owned; no UI callbacks are retained. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class OrezOfflineVoiceRuntime private constructor(private val app: Context) {
    private val slot = OwnedVoiceSlot()
    private val guard = Any()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningToken = 0L
    private var producer: Job? = null
    private var retainedResources: RetainedResources? = null
    private val mutable = MutableStateFlow(OrezVoiceState())
    val state: StateFlow<OrezVoiceState> = mutable
    private data class RetainedResources(val engine: VideoSpeechEngine?, val model: PinnedVoiceModel,
        val record: OwnedVoiceAudio?)

    /** Caller grants microphone permission explicitly before invoking; producer checks it again. */
    fun start(): Long? {
        val owner = slot.acquire() ?: return null
        synchronized(guard) {
            runningToken = owner.token
            mutable.value = OrezVoiceState(owner.token, OrezVoicePhase.VERIFYING, "Checking installed Tiny speech model…")
            producer = applicationScope.launch(start = CoroutineStart.ATOMIC) { produce(owner) }
        }
        return owner.token
    }
    fun stop(token: Long) { slot.requestStop(token) }
    fun cancel(token: Long) {
        val work = synchronized(guard) {
            slot.retire(token) // Detach before retirement/cancellation; this lock performs memory operations only.
            val owned = if (runningToken == token) producer else null
            if (mutable.value.token == token && mutable.value.phase != OrezVoicePhase.RETAINED) mutable.value = mutable.value.copy(
                phase = if (owned?.isCompleted == false) OrezVoicePhase.STOPPING else OrezVoicePhase.IDLE,
                message = if (owned?.isCompleted == false) "Stopping • microphone/native resources are still owned until they return"
                    else "Offline microphone input • canceled", transcript = null)
            owned
        }
        work?.cancel()
    }
    private fun requireCurrent(owner: OwnedVoiceSlot.Owner) {
        if (!slot.isCurrent(owner)) throw CancellationException("Microphone request was retired.")
    }
    private fun publish(owner: OwnedVoiceSlot.Owner, phase: OrezVoicePhase, message: String, capturedMs: Long = mutable.value.capturedMs) {
        synchronized(guard) {
            if (slot.isCurrent(owner)) mutable.value = OrezVoiceState(owner.token, phase, message, capturedMs)
        }
    }
    private fun phaseDeadline(owner: OwnedVoiceSlot.Owner, message: String): Job = applicationScope.launch {
        delay(OfflineOrezVoicePolicy.NATIVE_PHASE_MS)
        val work = synchronized(guard) {
            if (!slot.isCurrent(owner)) null else {
                slot.retire(owner.token)
                mutable.value = OrezVoiceState(owner.token, OrezVoicePhase.ERROR, message)
                producer.takeIf { runningToken == owner.token }
            }
        }
        work?.cancel()
    }

    @Suppress("MissingPermission")
    private suspend fun produce(owner: OwnedVoiceSlot.Owner) {
        val model = PinnedVoiceModel() // Assign before open: failed validation cannot abandon an opened FD.
        val pcm = BoundedVoicePcm()
        val engineJob = SupervisorJob()
        val engineScope = CoroutineScope(engineJob + Dispatchers.Default)
        var engine: VideoSpeechEngine? = null
        var record: OwnedVoiceAudio? = null
        var audioReleased = true
        var engineClosed = true
        var timer: Job? = null
        var finalState: OrezVoiceState? = null
        try {
            requireCurrent(owner)
            check(ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission was not granted. No audio was captured."
            }
            timer = phaseDeadline(owner, "Speech model preparation exceeded 20 seconds. Cleanup is still owned; try later.")
            model.open(app)
            model.verify { slot.isCurrent(owner) }
            requireCurrent(owner)
            engine = VideoSpeechEngine(app, engineScope, model.loadFile)
            engineClosed = false
            val capturedEngine = requireNotNull(engine)
            val precondition = NativeComputePrecondition {
                requireCurrent(owner)
                model.verify { slot.isCurrent(owner) }
                requireCurrent(owner)
            }
            publish(owner, OrezVoicePhase.LOADING, "Loading verified Tiny speech model offline…")
            withContext(precondition) { capturedEngine.loadInstalled() }
            requireCurrent(owner)
            check(capturedEngine.state.value.ready) { "The verified speech model could not be loaded." }
            timer.cancel(); timer = null
            check(ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission was revoked. No audio was captured."
            }
            val minimum = AudioRecord.getMinBufferSize(OfflineOrezVoicePolicy.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "This device does not support mono 16 kHz microphone input." }
            val nativeRecord = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(OfflineOrezVoicePolicy.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 4_096)).build()
            record = OwnedVoiceAudio(object : VoiceAudioPort {
                override val initialized get() = nativeRecord.state == AudioRecord.STATE_INITIALIZED
                override fun start() {
                    nativeRecord.startRecording()
                    check(nativeRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start." }
                }
                override fun read(samples: ShortArray, count: Int) = nativeRecord.read(samples, 0, count, AudioRecord.READ_BLOCKING)
                override fun stop() { nativeRecord.stop() }
                override fun release() { nativeRecord.release() }
            })
            audioReleased = false
            val capturedRecord = requireNotNull(record)
            check(capturedRecord.initialized) { "Microphone initialization failed." }
            requireCurrent(owner)
            capturedRecord.start()
            publish(owner, OrezVoicePhase.RECORDING, "Recording offline • Stop to transcribe • maximum 15 seconds", 0)
            timer = applicationScope.launch {
                delay(OfflineOrezVoicePolicy.MAX_CAPTURE_MS)
                slot.requestStop(owner.token) // Memory only: stop/release run after actual read returns.
                publish(owner, OrezVoicePhase.STOPPING, "15-second limit reached • waiting for the current microphone read to return")
            }
            val input = ShortArray(1_024)
            try {
                while (!owner.stopRequested && !pcm.full) {
                    requireCurrent(owner)
                    val count = capturedRecord.read(input, minOf(input.size, OfflineOrezVoicePolicy.MAX_SAMPLES - pcm.size))
                    requireCurrent(owner)
                    check(count >= 0) { "Microphone capture failed." }
                    if (count > 0) pcm.append(input, count)
                    publish(owner, OrezVoicePhase.RECORDING, "Recording offline • Stop to transcribe • maximum 15 seconds",
                        pcm.size * 1_000L / OfflineOrezVoicePolicy.SAMPLE_RATE)
                    if (count == 0) delay(10)
                }
            } finally { input.fill(0) }
            timer.cancel(); timer = null
            // No concurrent forced stop/release: the only read loop has actually returned.
            audioReleased = capturedRecord.releaseAfterRead()
            check(audioReleased) { "Microphone resources did not prove release. No transcript will be published." }
            record = null
            requireCurrent(owner)
            check(pcm.size > 0) { "No microphone audio was captured." }
            publish(owner, OrezVoicePhase.TRANSCRIBING, "Recognizing original speech offline…")
            timer = phaseDeadline(owner, "Speech recognition exceeded 20 seconds. Native cleanup is still owned; try later.")
            val result = withContext(precondition) {
                capturedEngine.inferOriginalChunk(pcm.snapshot(), 0, sourceLanguage = "auto", threads = 1)
            }
            requireCurrent(owner)
            timer.cancel(); timer = null
            val transcript = OfflineOrezVoicePolicy.transcript(result.map { it.text })
            finalState = OrezVoiceState(owner.token, if (transcript == null) OrezVoicePhase.ERROR else OrezVoicePhase.READY,
                if (transcript == null) "No clear bounded speech was recognized. Type or try again."
                else "Speech placed in your current draft • review it and tap Send", pcm.size * 1_000L / 16_000, transcript)
        } catch (cancelled: CancellationException) {
            if (slot.isCurrent(owner)) slot.retire(owner.token)
        } catch (missing: VoiceModelRequiredException) {
            if (slot.isCurrent(owner)) finalState = OrezVoiceState(owner.token, OrezVoicePhase.MODEL_REQUIRED, missing.message.orEmpty())
        } catch (failure: Exception) {
            if (slot.isCurrent(owner)) finalState = OrezVoiceState(owner.token, OrezVoicePhase.ERROR,
                failure.message?.take(260) ?: "Offline microphone input failed. No message was sent.")
        } finally {
            timer?.cancel()
            withContext(NonCancellable + Dispatchers.IO) {
                // The read/infer/load call has returned before this finally can free its resources.
                if (!audioReleased) {
                    val captured = record
                    if (captured != null) audioReleased = captured.releaseAfterRead()
                }
                try { engine?.close(); engineClosed = true } catch (_: Exception) { engineClosed = false }
                engineJob.cancelAndJoin()
                pcm.clear() // Native inference has settled, including cancellation, before PCM is wiped.
                val modelClosed = if (engineClosed) model.closeAfterEngine() else false
                val proven = audioReleased && engineClosed && modelClosed
                synchronized(guard) {
                    if (!proven) retainedResources = RetainedResources(engine, model, record)
                    if (runningToken == owner.token) producer = null
                    val attached = slot.isCurrent(owner)
                    if (!proven) mutable.value = OrezVoiceState(owner.token, OrezVoicePhase.RETAINED,
                        "A microphone/model resource did not prove release. Further recording is unavailable in this process.")
                    else if (attached && finalState != null) mutable.value = requireNotNull(finalState)
                    else if (mutable.value.token == owner.token && mutable.value.phase == OrezVoicePhase.STOPPING)
                        mutable.value = OrezVoiceState(owner.token, OrezVoicePhase.IDLE, "Offline microphone input • canceled")
                    slot.releaseAfterCleanup(owner, proven)
                }
            }
        }
    }
    companion object {
        @Volatile private var instance: OrezOfflineVoiceRuntime? = null
        fun get(context: Context): OrezOfflineVoiceRuntime = instance ?: synchronized(this) {
            instance ?: OrezOfflineVoiceRuntime(context.applicationContext).also { instance = it }
        }
    }
}
