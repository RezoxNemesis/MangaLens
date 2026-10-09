package com.mangalens.ui.video

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal data class ImportedCaptionCue(val startMs: Long, val endMs: Long, val text: String)
internal data class ImportedCaptionFile(val cues: List<ImportedCaptionCue>) {
    val mimeType: String get() = "application/x-subrip"
    val srt: String get() = cues.mapIndexed { index, cue ->
        "${index + 1}\n${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}\n${cue.text}\n"
    }.joinToString("\n")

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_CUES = 30_000
        private val timing = Regex("^(\\d{2,4}:)?(\\d{2}):(\\d{2})[,.](\\d{3})$")
        private val timingLine = Regex("^([^ ]+)\\s+-->\\s+([^ ]+)(?:\\s+(.*))?$")
        private val tags = Regex("</?(?:b|i|u|font|c(?:\\.[^<>\\s]+)?|v|lang|ruby|rt)(?:\\s+[^<>]*)?>|<\\d{2}:\\d{2}(?::\\d{2})?\\.\\d{3}>", RegexOption.IGNORE_CASE)
        private val entities = Regex("&(amp|lt|gt|quot|apos|nbsp|#[0-9]{1,10}|#x[0-9a-fA-F]{1,8});")

        fun read(stream: InputStream): ImportedCaptionFile {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(bytes.size() + count <= MAX_BYTES) { "Subtitle file exceeds the 2 MB import limit." }
                bytes.write(buffer, 0, count)
            }
            val raw = bytes.toByteArray()
            val (charset, offset) = when {
                raw.size >= 2 && raw[0] == 0xff.toByte() && raw[1] == 0xfe.toByte() -> Charsets.UTF_16LE to 2
                raw.size >= 2 && raw[0] == 0xfe.toByte() && raw[1] == 0xff.toByte() -> Charsets.UTF_16BE to 2
                else -> Charsets.UTF_8 to 0
            }
            val text = try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, offset, raw.size - offset)).toString()
            } catch (failure: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("Subtitle file must contain valid UTF-8 or BOM-marked UTF-16 text.", failure)
            }
            return parse(text)
        }

        fun parse(text: String): ImportedCaptionFile {
            require(text.length <= MAX_BYTES && '\u0000' !in text) { "Invalid subtitle text." }
            val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
            val blocks = normalized.split(Regex("\n[ \\t]*\n+"))
            val webVtt = normalized.startsWith("WEBVTT") &&
                normalized.getOrNull(6).let { it == null || it.isWhitespace() }
            val cues = ArrayList<ImportedCaptionCue>()
            for ((blockIndex, block) in blocks.withIndex()) {
                val lines = block.lines().map { it.trimEnd() }
                if (lines.all { it.isBlank() }) continue
                val first = lines.first().trim()
                if (webVtt && blockIndex == 0 && first.startsWith("WEBVTT")) {
                    require(lines.drop(1).none { "-->" in it }) { "WEBVTT header must be separated from its cues." }
                    continue
                }
                if (webVtt && (first == "NOTE" || first.startsWith("NOTE ") || first.startsWith("NOTE\t") ||
                    first == "STYLE" || first == "REGION")) continue
                val lineIndex = when {
                    "-->" in first -> 0
                    lines.size >= 2 && "-->" in lines[1] -> 1
                    else -> throw IllegalArgumentException("Subtitle cue ${cues.size + 1} has no valid time range.")
                }
                if (!webVtt && lineIndex == 1) require(first.toIntOrNull()?.let { it > 0 } == true) { "Invalid SRT cue number." }
                val range = requireNotNull(timingLine.matchEntire(lines[lineIndex].trim())) { "Invalid subtitle time range." }
                val start = parseTime(range.groupValues[1])
                val end = parseTime(range.groupValues[2])
                require(end > start) { "Subtitle cue must end after it starts." }
                if (!webVtt) require(range.groupValues[3].isEmpty()) { "SRT time ranges must not contain VTT settings." }
                val payload = lines.drop(lineIndex + 1).joinToString("\n").trim()
                require(payload.isNotBlank() && payload.length <= 16_384 && "-->" !in payload) { "Subtitle cue has invalid or missing dialogue." }
                require(cues.size < MAX_CUES) { "Subtitle cue limit reached." }
                cues += ImportedCaptionCue(start, end, payload)
            }
            require(cues.isNotEmpty()) { "No valid subtitle cues found." }
            return ImportedCaptionFile(cues.toList())
        }

        fun visibleText(value: String): String = entities.replace(tags.replace(value, "")) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> {
                    val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF &&
                        (code >= 32 || code == 9 || code == 10)) String(Character.toChars(code)) else match.value
                }
            }
        }

        private fun parseTime(value: String): Long {
            val match = requireNotNull(timing.matchEntire(value)) { "Invalid subtitle timestamp." }
            val hours = match.groupValues[1].removeSuffix(":").ifEmpty { "0" }.toLong()
            val minutes = match.groupValues[2].toInt()
            val seconds = match.groupValues[3].toInt()
            require(minutes < 60 && seconds < 60) { "Invalid subtitle minutes or seconds." }
            return hours * 3_600_000 + minutes * 60_000 + seconds * 1000 + match.groupValues[4].toInt()
        }

        private fun timestamp(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
            ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }
}
