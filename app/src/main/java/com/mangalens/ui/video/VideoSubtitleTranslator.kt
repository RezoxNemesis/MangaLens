package com.mangalens.ui.video

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mangalens.core.translation.TranslationService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException

data class SubtitleCue(val index: Int, val timing: String, val text: String)

class VideoSubtitleTranslator(private val context: Context) {
    suspend fun translate(uri: android.net.Uri, targetLanguage: String): android.net.Uri {
        val cues = parse(context.contentResolver.openInputStream(uri)?.use { BufferedReader(InputStreamReader(it)).readText() } ?: error("Unable to read subtitle file"))
        require(cues.isNotEmpty()) { "No subtitle cues found." }
        val service = TranslationService()
        try {
            val out = buildString {
                cues.forEach { cue ->
                    append(cue.index).append("\n")
                    append(cue.timing).append("\n")
                    val translated = service.translate(cue.text, targetLanguage)
                    append(translated).append("\n\n")
                }
            }
            return save(out, targetLanguage)
        } finally {
            service.close()
        }
    }

    private fun parse(raw: String): List<SubtitleCue> {
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n").trim()
        val blocks = normalized.split(Regex("\n{2,}"))
        return blocks.mapNotNullIndexed { idx, block ->
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) null else {
                val timingIndex = lines.indexOfFirst { it.contains("-->") }
                if (timingIndex < 0) null else {
                    val timing = lines[timingIndex]
                    val text = lines.drop(timingIndex + 1).joinToString("\n").trim()
                    if (text.isBlank()) null else SubtitleCue(idx + 1, timing, text)
                }
            }
        }
    }

    private fun save(content: String, targetLanguage: String): android.net.Uri {
        val name = "MangaLens-translated-subtitles-$targetLanguage-${System.currentTimeMillis()}.srt"
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MangaLens")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Unable to create subtitle file")
            try {
                val destination = resolver.openOutputStream(uri, "w")
                    ?: throw IOException("Unable to open subtitle destination for writing.")
                destination.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null); throw t
            }
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = java.io.File(dir, name)
        file.writeText(content, Charsets.UTF_8)
        return android.net.Uri.fromFile(file)
    }
}

private inline fun <T,R> List<T>.mapNotNullIndexed(transform: (Int,T)->R?): List<R> {
    val out = ArrayList<R>(size)
    for (i in indices) transform(i, this[i])?.let(out::add)
    return out
}