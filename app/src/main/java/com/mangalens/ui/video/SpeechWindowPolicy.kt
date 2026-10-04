package com.mangalens.ui.video

/** Cheap speech-activity gate before Whisper; Whisper's own no-speech confidence is checked too. */
internal object SpeechWindowPolicy {
    fun hasActivity(samples: FloatArray): Boolean {
        if (samples.size < 800) return false
        var active = 0
        var frames = 0
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 320, samples.size)
            var energy = 0.0
            for (i in offset until end) energy += samples[i] * samples[i]
            if (energy / (end - offset) > 0.00003) active++
            frames++
            offset = end
        }
        return active >= 3 && active.toFloat() / frames >= .08f
    }
    fun normalized(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun append(existing: List<SpeechCue>, incoming: List<SpeechCue>): List<SpeechCue> {
        val out = existing.toMutableList()
        for (cue in incoming) {
            if (cue.endMs <= cue.startMs || cue.text.isBlank()) continue
            val key = normalized(cue.text)
            if (key.isBlank()) continue
            val duplicate = out.takeLast(8).any { old ->
                cue.startMs < old.endMs + 500 && cue.endMs > old.startMs && normalized(old.text) == key
            }
            if (!duplicate) out += cue
        }
        return out.takeLast(5000)
    }
}
