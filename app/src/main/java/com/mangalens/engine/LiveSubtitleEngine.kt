package com.mangalens.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LiveSubtitleCue(
    val text: String,
    val translatedText: String,
    val startMs: Long,
    val endMs: Long,
    val sourceLanguage: String
)

@UnstableApi
class LiveSubtitleEngine(
    private val context: Context,
    private val translate: suspend (String, String) -> String
) {
    private val _cues = MutableStateFlow<List<LiveSubtitleCue>>(emptyList())
    val cues: StateFlow<List<LiveSubtitleCue>> = _cues

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognizer: SpeechRecognizer? = null
    private var restartJob: Job? = null
    private var target = "hi"
    private var player: ExoPlayer? = null
    private var listening = false
    private var speechStartMs = 0L
    private var lastFinalText = ""

    fun attach(exo: ExoPlayer, targetLanguage: String = "hi") {
        player = exo
        target = targetLanguage
    }

    fun start() {
        if (listening || !SpeechRecognizer.isRecognitionAvailable(context)) return
        listening = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
        }
        startRecognition()
    }

    fun stop() {
        listening = false
        restartJob?.cancel()
        restartJob = null
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    fun clear() {
        _cues.value = emptyList()
        lastFinalText = ""
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun startRecognition() {
        if (!listening) return
        speechStartMs = player?.currentPosition ?: 0L
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure { scheduleRestart(700L) }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!listening) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            if (listening) startRecognition()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            speechStartMs = player?.currentPosition ?: speechStartMs
        }

        override fun onBeginningOfSpeech() {
            speechStartMs = player?.currentPosition ?: speechStartMs
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            // Partial hypotheses are intentionally not committed to the cue list. They are noisy
            // and change rapidly; the UI can keep showing the previous stable cue until final text.
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (text.isBlank()) {
                scheduleRestart(180L)
                return
            }

            val normalized = text.lowercase().replace(Regex("\\s+"), " ").trim()
            if (normalized == lastFinalText) {
                scheduleRestart(180L)
                return
            }
            lastFinalText = normalized

            val start = speechStartMs
            val end = (player?.currentPosition ?: start + 2500L).coerceAtLeast(start + 350L)
            scope.launch {
                try {
                    val translated = translate(text, target).ifBlank { text }
                    val next = LiveSubtitleCue(
                        text = text,
                        translatedText = translated,
                        startMs = start,
                        endMs = end,
                        sourceLanguage = "auto"
                    )
                    _cues.value = (_cues.value + next).takeLast(120)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    _cues.value = (_cues.value + LiveSubtitleCue(
                        text = text,
                        translatedText = text,
                        startMs = start,
                        endMs = end,
                        sourceLanguage = "auto"
                    )).takeLast(120)
                } finally {
                    scheduleRestart(160L)
                }
            }
        }

        override fun onError(error: Int) {
            if (!listening) return
            val delayMs = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 900L
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 2500L
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 1200L
                else -> 450L
            }
            scheduleRestart(delayMs)
        }
    }
}
