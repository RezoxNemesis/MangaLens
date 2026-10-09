package com.mangalens.ui.video

import com.mangalens.download.ProviderCaptionFormat
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONObject

internal data class ProviderCaptionCue(val startMs: Long, val endMs: Long, val text: String)
internal data class ProviderCaptionDocument(val cues: List<ProviderCaptionCue>, val payloadSha256: String, val cuesSha256: String)

/** Provider times are preserved. No synthetic audio, ASR timestamps, or guessed event ends. */
internal object ProviderCaptionParser {
    private const val MAX_DURATION = 6L * 60 * 60 * 1000

    fun parse(bytes: ByteArray, format: ProviderCaptionFormat, durationMs: Long? = null): ProviderCaptionDocument {
        require(bytes.size in 1..ImportedCaptionFile.MAX_BYTES) { "Provider captions exceed the supported size." }
        require(durationMs == null || durationMs in 1..MAX_DURATION) { "Invalid provider video duration." }
        val cues = if (format == ProviderCaptionFormat.JSON3) json3(bytes) else
            ImportedCaptionFile.read(ByteArrayInputStream(bytes)).cues.map {
                ProviderCaptionCue(it.startMs, it.endMs, ImportedCaptionFile.visibleText(it.text).trim())
            }
        require(cues.size in 1..ImportedCaptionFile.MAX_CUES) { "No bounded provider dialogue was found." }
        var previous = -1L
        for (cue in cues) {
            require(cue.startMs >= 0 && cue.startMs >= previous && cue.endMs > cue.startMs && cue.endMs <= MAX_DURATION &&
                (durationMs == null || cue.endMs <= durationMs + 1500) && cue.text.length in 1..4000 &&
                cue.text.isNotBlank() && cue.text.none { it == '\u0000' || it == '\r' }) { "Invalid provider caption timing or dialogue." }
            previous = cue.startMs
        }
        return ProviderCaptionDocument(cues.toList(), hash(bytes), cueHash(cues))
    }

    internal fun cueHash(cues: List<ProviderCaptionCue>): String = hash(cues.joinToString("|") { cue ->
        val fields = listOf(cue.startMs.toString(), cue.endMs.toString(), cue.text)
        fields.joinToString("") { "${it.length}:$it" }
    }.toByteArray(Charsets.UTF_8))

    private fun json3(bytes: ByteArray): List<ProviderCaptionCue> {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Provider captions contain invalid UTF-8.", failure)
        }
        val root = try { JSONObject(text) } catch (failure: org.json.JSONException) {
            throw IllegalArgumentException("Provider captions have no valid timed events.", failure)
        }
        val events = root.optJSONArray("events") ?: throw IllegalArgumentException("Provider captions have no timed events.")
        require(events.length() <= 60_000) { "Provider caption event limit reached." }
        val result = ArrayList<ProviderCaptionCue>()
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: throw IllegalArgumentException("Invalid provider caption event.")
            val segments = event.optJSONArray("segs") ?: continue // Non-dialogue window metadata.
            require(segments.length() in 1..512 && !event.optBoolean("aAppend", false) && event.optInt("aAppend", 0) == 0) {
                "Incremental provider display events need a complete timed caption format."
            }
            val start = integer(event, "tStartMs")
            val duration = integer(event, "dDurationMs")
            require(start >= 0 && duration > 0 && start <= MAX_DURATION - duration) { "Invalid provider event range." }
            val dialogue = StringBuilder()
            for (n in 0 until segments.length()) {
                val segment = segments.optJSONObject(n) ?: throw IllegalArgumentException("Invalid provider word event.")
                val word = segment.opt("utf8") as? String ?: throw IllegalArgumentException("Missing provider word text.")
                if (segment.has("tOffsetMs")) require(integer(segment, "tOffsetMs") in 0 until duration) { "Invalid provider word offset." }
                require(dialogue.length + word.length <= 4000) { "Provider dialogue is too long." }
                dialogue.append(word)
            }
            val value = dialogue.toString().trim()
            if (value.isBlank()) continue
            require(result.size < ImportedCaptionFile.MAX_CUES) { "Provider caption cue limit reached." }
            result += ProviderCaptionCue(start, start + duration, value)
        }
        return result
    }

    private fun integer(json: JSONObject, key: String): Long {
        val value = json.opt(key)
        require(value is Byte || value is Short || value is Int || value is Long) { "Provider timestamps must be whole milliseconds." }
        return (value as Number).toLong()
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
