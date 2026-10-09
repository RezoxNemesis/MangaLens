package com.mangalens.ui.video

import java.util.Locale

/** Translation changes text only. Timing and overlap identity come from original speech. */
internal object SubtitleAlignedTrack {
    data class Pair(val original: SpeechCue, val target: String?)
    private data class Seen(val windowIndex: Int, val windowEndMs: Long, val cueIndex: Int)

    fun pairs(windows: List<SubtitleWindow>): List<Pair> {
        val result = ArrayList<Pair>()
        val recent = HashMap<String, MutableList<Seen>>()
        windows.forEach { window ->
            recent.values.forEach { values -> values.removeAll { it.windowEndMs <= window.startMs } }
            recent.entries.removeAll { it.value.isEmpty() }
            val translated = window.translations.associateBy { it.sourceIndex }
            window.sourceCues.forEachIndexed { sourceIndex, original ->
                val text = identity(original.text)
                val target = translated[sourceIndex]?.text?.trim()
                // Only exact original text in two overlapping decode windows is a
                // duplicate. Similar phrases can reverse meaning; adjacent repeats
                // and two occurrences in the same window remain separate cues.
                val duplicate = recent[text]?.lastOrNull { seen ->
                    val previous = result[seen.cueIndex].original
                    seen.windowIndex != window.index && previous.startMs < original.endMs && original.startMs < previous.endMs
                }
                if (duplicate != null) {
                    if (result[duplicate.cueIndex].target == null && target != null)
                        result[duplicate.cueIndex] = result[duplicate.cueIndex].copy(target = target)
                } else {
                    result += Pair(original.copy(text = original.text.trim()), target)
                    recent.getOrPut(text) { ArrayList() } += Seen(window.index, window.endMs, result.lastIndex)
                    require(result.size <= SubtitleFormats.MAX_CUES) { "Subtitle cue limit reached." }
                }
            }
        }
        return result.sortedWith(compareBy<Pair> { it.original.startMs }.thenBy { it.original.endMs })
    }

    fun render(pairs: List<Pair>, mode: SubtitleOutputMode): List<SpeechCue> = pairs.mapNotNull { pair ->
        val target = pair.target ?: return@mapNotNull null
        pair.original.copy(text = if (mode == SubtitleOutputMode.DUAL && pair.original.text != target)
            pair.original.text + "\n" + target else target)
    }

    fun retainTimings(cues: List<SpeechCue>): List<SpeechCue> {
        require(cues.size <= SubtitleFormats.MAX_CUES) { "Subtitle cue limit reached." }
        require(cues.all { it.startMs >= 0 && it.endMs > it.startMs && it.text.isNotBlank() }) { "Invalid generated subtitle timing." }
        return cues.map { it.copy(text = it.text.trim()) }
    }

    private fun identity(text: String): String = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
