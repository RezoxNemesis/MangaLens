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
            if (energy / (end - offset) > 0.00001) active++
            frames++
            offset = end
        }
        return active >= 3 && active.toFloat() / frames >= .05f
    }

    fun normalized(text: String): String =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun clean(text: String): String =
        text.replace(Regex("[\\t\\r ]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()

    private fun overlaps(a: SpeechCue, b: SpeechCue): Boolean =
        b.startMs < a.endMs + 650 && b.endMs > a.startMs - 150

    private fun isNearDuplicate(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 5 || b.length < 5) return false
        val short = if (a.length <= b.length) a else b
        val long = if (a.length > b.length) a else b
        if (short.length >= 10 && long.contains(short) && short.length >= long.length * .45f) return true

        val at = a.split(' ').filter { it.isNotBlank() }.toSet()
        val bt = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (at.isEmpty() || bt.isEmpty()) return false
        val intersection = at.intersect(bt).size
        val union = at.union(bt).size
        val containment = intersection.toFloat() / minOf(at.size, bt.size)
        val jaccard = intersection.toFloat() / union
        return containment >= .82f || jaccard >= .72f
    }

    private fun shouldMerge(previous: SpeechCue, incoming: SpeechCue): Boolean {
        val gap = incoming.startMs - previous.endMs
        if (gap !in 0L..240L) return false
        val previousText = previous.text.trim()
        val incomingText = incoming.text.trim()
        if (previousText.isBlank() || incomingText.isBlank()) return false
        if (previousText.last() in ".!?…。！？") return false
        if (previous.startMs + 7_000L < incoming.endMs) return false
        if (previousText.length + 1 + incomingText.length > 88) return false
        return previousText.length <= 48 || incomingText.firstOrNull()?.isLowerCase() == true
    }

    fun append(existing: List<SpeechCue>, incoming: List<SpeechCue>): List<SpeechCue> {
        val out = existing.toMutableList()
        for (raw in incoming) {
            val text = clean(raw.text)
            if (raw.endMs <= raw.startMs || text.isBlank()) continue
            val key = normalized(text)
            if (key.isBlank()) continue

            val cue = raw.copy(text = text)
            val duplicate = out.takeLast(10).any { old ->
                overlaps(old, cue) && isNearDuplicate(normalized(old.text), key)
            }
            if (duplicate) continue

            val previous = out.lastOrNull()
            if (previous != null && shouldMerge(previous, cue)) {
                out[out.lastIndex] = SpeechCue(
                    startMs = previous.startMs,
                    endMs = maxOf(previous.endMs, cue.endMs),
                    text = clean(previous.text + " " + cue.text)
                )
                continue
            }

            val nonOverlapping = if (
                previous != null &&
                cue.startMs < previous.endMs &&
                cue.endMs > previous.endMs + 250
            ) {
                cue.copy(startMs = previous.endMs)
            } else cue

            if (nonOverlapping.endMs - nonOverlapping.startMs >= 250) {
                out += nonOverlapping
            }
        }
        return out.takeLast(5000)
    }
}
