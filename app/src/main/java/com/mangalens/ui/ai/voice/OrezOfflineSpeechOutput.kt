package com.mangalens.ui.ai.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

internal data class OrezSpeechOutputState(val token: Long = 0, val ready: Boolean = false,
    val voices: List<OfflineVoiceChoice> = emptyList(), val selected: String? = null,
    val provider: String = "", val speaking: Boolean = false,
    val message: String = "Speech output is off. Only installed Android voices marked offline are offered.")

/** Android provider owns synthesis. Explicit opt-in only; Binder calls/cleanup never run on Main. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class OrezOfflineSpeechOutput private constructor(private val app: Context) {
    private sealed interface Command {
        data class Speak(val text: String) : Command
        data class Select(val name: String) : Command
        data object Stop : Command
    }
    private class Session(val owner: OwnedVoiceSlot.Owner) {
        val commands = Channel<Command>(4)
        val utterance = AtomicReference<String?>(null)
        var job: Job? = null
    }
    private val capacity = OwnedVoiceSlot()
    private val guard = Any()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(OrezSpeechOutputState())
    val state: StateFlow<OrezSpeechOutputState> = mutable
    private var session: Session? = null
    private var retainedEngine: TextToSpeech? = null

    /** Does not speak. Initializes only after the user opts into Android offline speech output. */
    fun enable(): Long? {
        val owner = capacity.acquire() ?: return null
        val current = Session(owner)
        synchronized(guard) {
            session = current
            mutable.value = OrezSpeechOutputState(owner.token, message = "Checking installed Android offline voices…")
            current.job = applicationScope.launch(start = CoroutineStart.ATOMIC) { run(current) }
        }
        return owner.token
    }
    fun speak(token: Long, text: String) {
        val bounded = OfflineOrezVoicePolicy.spokenText(text, TextToSpeech.getMaxSpeechInputLength())
        if (bounded == null) {
            synchronized(guard) { session?.takeIf { it.owner.token == token } }?.let { owned ->
                update(owned) { it.copy(message = "Choose a nonempty reply of at most 4,000 characters to speak.") }
            }
        } else submit(token, Command.Speak(bounded))
    }
    fun select(token: Long, name: String) = submit(token, Command.Select(name))
    fun stop(token: Long) = submit(token, Command.Stop)
    private fun submit(token: Long, command: Command) {
        val current = synchronized(guard) { session?.takeIf { it.owner.token == token && capacity.isCurrent(it.owner) } } ?: return
        if (current.commands.trySend(command).isFailure)
            update(current) { it.copy(message = "Speech controls are busy. Try again after the current provider call returns.") }
    }
    fun disable(token: Long) {
        val current = synchronized(guard) {
            val owned = session?.takeIf { it.owner.token == token } ?: return
            capacity.retire(token) // Detach before cancel; only memory operations under this lock.
            owned.utterance.set(null)
            if (mutable.value.token == token) mutable.value = OrezSpeechOutputState(token,
                message = "Stopping and releasing the Android speech provider…")
            owned
        }
        current.commands.close()
        current.job?.cancel()
    }
    private fun current(session: Session) = capacity.isCurrent(session.owner)
    private fun update(session: Session, change: (OrezSpeechOutputState) -> OrezSpeechOutputState) {
        synchronized(guard) {
            if (capacity.isCurrent(session.owner) && mutable.value.token == session.owner.token)
                mutable.value = change(mutable.value)
        }
    }
    private suspend fun run(session: Session) {
        var engine: TextToSpeech? = null
        var providerReleased = false
        try {
            if (!current(session)) return
            val initialized = CompletableDeferred<Int>()
            engine = TextToSpeech(app) { status -> initialized.complete(status) }
            val speech = requireNotNull(engine)
            check(withTimeout(10_000) { initialized.await() } == TextToSpeech.SUCCESS) { "Android speech provider is unavailable." }
            if (!current(session)) return
            val choices = OfflineOrezVoicePolicy.offlineVoices(speech.voices.orEmpty().map {
                OfflineVoiceChoice(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired)
            }).filter { speech.isLanguageAvailable(Locale.forLanguageTag(it.localeTag)) >= TextToSpeech.LANG_AVAILABLE }
            check(choices.isNotEmpty()) { "No installed Android voice is marked offline. Install one in Android speech settings." }
            val preferred = app.getSharedPreferences("orez_offline_voice", Context.MODE_PRIVATE).getString("output_voice", null)
            var selected = choices.firstOrNull { it.name == preferred }
                ?: choices.firstOrNull { Locale.forLanguageTag(it.localeTag).language == Locale.getDefault().language }
                ?: choices.first()
            fun applyVoice(name: String): Boolean {
                val voice = speech.voices.orEmpty().firstOrNull { it.name == name && !it.isNetworkConnectionRequired } ?: return false
                return speech.isLanguageAvailable(voice.locale) >= TextToSpeech.LANG_AVAILABLE && speech.setVoice(voice) == TextToSpeech.SUCCESS
            }
            check(applyVoice(selected.name)) { "The selected offline voice locale is unavailable." }
            val defaultProvider = speech.defaultEngine.orEmpty() // Provider/settings read stays outside the short memory lock.
            update(session) { OrezSpeechOutputState(session.owner.token, true, choices, selected.name,
                defaultProvider, message = "Android offline voice • ${selected.localeTag} • tap Speak on an Orez reply") }
            speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { }
                override fun onDone(id: String?) { ended(id, null) }
                @Deprecated("Android callback compatibility")
                override fun onError(id: String?) { ended(id, "Android speech synthesis failed.") }
                override fun onError(id: String?, errorCode: Int) { ended(id, "Android speech synthesis failed (code $errorCode).") }
                private fun ended(id: String?, failure: String?) {
                    if (id != null && session.utterance.compareAndSet(id, null))
                        update(session) { value -> value.copy(speaking = false,
                            message = failure ?: "Speech finished • ${value.voices.firstOrNull { it.name == value.selected }?.localeTag.orEmpty()}") }
                }
            })
            var sequence = 0L
            for (command in session.commands) {
                if (!current(session)) break
                when (command) {
                    is Command.Select -> {
                        val requested = choices.firstOrNull { it.name == command.name }
                        if (requested == null || !applyVoice(requested.name)) {
                            update(session) { it.copy(message = "That installed offline voice is unavailable.") }
                        } else {
                            check(speech.stop() == TextToSpeech.SUCCESS) { "Android speech provider could not acknowledge Stop before changing voice." }
                            session.utterance.set(null)
                            selected = requested
                            app.getSharedPreferences("orez_offline_voice", Context.MODE_PRIVATE).edit().putString("output_voice", selected.name).apply()
                            update(session) { it.copy(selected = selected.name, speaking = false,
                                message = "Android offline voice • ${selected.localeTag}") }
                        }
                    }
                    Command.Stop -> {
                        session.utterance.set(null)
                        check(speech.stop() == TextToSpeech.SUCCESS) { "Android speech provider could not acknowledge Stop." }
                        update(session) { it.copy(speaking = false, message = "Speech stopped") }
                    }
                    is Command.Speak -> {
                        val text = OfflineOrezVoicePolicy.spokenText(command.text, TextToSpeech.getMaxSpeechInputLength())
                        if (text == null) {
                            update(session) { it.copy(message = "Choose a nonempty reply of at most 4,000 characters to speak.") }
                            continue
                        }
                        check(applyVoice(selected.name)) { "The selected voice is no longer available offline." }
                        if (!current(session)) break
                        val utterance = "orez-offline-${session.owner.token}-${++sequence}"
                        session.utterance.set(utterance)
                        check(speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance) == TextToSpeech.SUCCESS) {
                            "Android speech provider rejected the reply."
                        }
                        update(session) { it.copy(speaking = true, message = "Speaking • ${selected.localeTag}") }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            update(session) { OrezSpeechOutputState(session.owner.token,
                message = "Android speech provider initialization exceeded 10 seconds. No speech was started.") }
        } catch (cancelled: CancellationException) { }
        catch (failure: Exception) {
            update(session) { OrezSpeechOutputState(session.owner.token,
                message = failure.message?.take(240) ?: "Offline Android speech output is unavailable.") }
        } finally {
            session.utterance.set(null)
            session.commands.close()
            withContext(NonCancellable + Dispatchers.IO) {
                providerReleased = OwnedSpeechOutputClose({
                    val owned = engine
                    if (owned != null) check(owned.stop() == TextToSpeech.SUCCESS) { "Android speech Stop was not acknowledged." }
                }, { engine?.shutdown(); Unit }).close()
                synchronized(guard) {
                    if (!providerReleased) retainedEngine = engine
                    if (this@OrezOfflineSpeechOutput.session === session) this@OrezOfflineSpeechOutput.session = null
                    if (mutable.value.token == session.owner.token) mutable.value = mutable.value.copy(
                        ready = false, speaking = false, voices = emptyList(),
                        message = if (!providerReleased) "Android speech provider cleanup did not return safely. Further output is unavailable in this process."
                        else if (session.owner.retired) "Speech output is off" else mutable.value.message)
                    capacity.releaseAfterCleanup(session.owner, providerReleased)
                }
            }
        }
    }
    companion object {
        @Volatile private var instance: OrezOfflineSpeechOutput? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: OrezOfflineSpeechOutput(context.applicationContext).also { instance = it }
        }
    }
}
