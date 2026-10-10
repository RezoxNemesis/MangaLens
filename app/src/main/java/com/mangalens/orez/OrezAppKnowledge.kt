package com.mangalens.orez

import java.text.Normalizer
import java.util.Locale

/** Developer-authored help, not user/web/model data or a measurement of current app state. */
internal object OrezAppKnowledge {
    const val VERSION = 1
    data class Entry(val id: String, val title: String, val terms: Set<String>, val answer: String)
    data class Match(val entry: Entry, val score: Int)

    private val helpPrefix = Regex("^(?:how|why|what|where|can|does|do|is|help|explain)\\b")
    private val tokens = Regex("[\\p{L}\\p{N}]{2,40}")
    private val ignored = setOf("how", "why", "what", "where", "can", "does", "do", "is", "help", "explain", "mangalens", "orez", "the", "this", "with", "for", "my", "to", "in", "of", "and", "please")
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /** Called only on the current explicitly submitted user question, before tool/model selection. */
    fun isHelpRequest(value: String): Boolean {
        if (value.length !in 1..256 || value.any { it.isISOControl() } ||
            value.any { it in charArrayOf('"', '“', '”', '`', '<', '>') } || value.contains("://")) return false
        val normalizedValue = normalized(value.trim())
        if (normalizedValue.any { it.isISOControl() || it in charArrayOf('"', '“', '”', '`', '<', '>') } || normalizedValue.contains("://")) return false
        val clean = normalizedValue.removePrefix("please ")
        return helpPrefix.containsMatchIn(clean) && Regex("\\b(?:mangalens|orez)\\b").containsMatchIn(clean)
    }

    fun retrieve(question: String, limit: Int = 2): List<Match> {
        require(limit in 1..2)
        if (!isHelpRequest(question)) return emptyList()
        val query = tokens.findAll(normalized(question)).map { it.value }.filter { it !in ignored }.toSet()
        if (query.isEmpty()) return emptyList()
        return entries.map { entry -> Match(entry, query.count { it in entry.terms }) }
            .filter { it.score > 0 }.sortedWith(compareByDescending<Match> { it.score }.thenBy { it.entry.id }).take(limit)
    }

    fun answer(question: String): String? {
        if (!isHelpRequest(question)) return null
        val matches = retrieve(question)
        if (matches.isEmpty()) return "MangaLens help: ask about video downloads, subtitles, Reader OCR, Library export, local models, browser profiles or recovery. These help notes explain the app; they do not inspect your current files or run an action."
        return matches.joinToString("\n\n") { "${it.entry.title}: ${it.entry.answer}" } +
            "\n\nApp help notes. No action, file inspection or fresh model/provider check ran for this reply."
    }

    private val entries = listOf(
        Entry("architecture", "How MangaLens works", setOf("architecture", "subsystem", "subsystems", "work", "works", "native", "app"),
            "Reader and Library manage saved pages, Watch plays original media, Web owns browser navigation, and Orez requests validated app functions. Opening a screen is a handoff; a saved file or generated subtitle needs its native completion receipt."),
        Entry("tools", "Orez app tools", setOf("tool", "tools", "schema", "schemas", "permission", "permissions", "command", "commands", "action", "actions"),
            "Orez accepts explicit user requests. App functions validate typed chapter, media, URL and language arguments against a trusted registry. Downloads and translations use durable task records. Webpage text and model replies cannot grant permission or choose arbitrary shell commands or file paths."),
        Entry("media", "Video quality and downloads", setOf("video", "videos", "youtube", "instagram", "download", "downloads", "quality", "720p", "1080p", "audio", "resolution", "stream", "streams"),
            "Paste a public video URL into Watch or Downloads and choose an offered quality. Higher qualities can have separate video and audio streams; both must finish and pass the original-media checks before publication. Site availability, expiring links and the actual offered formats can limit a request. A quality label alone does not prove the downloaded file."),
        Entry("ads", "Ad protection", setOf("ad", "ads", "advertisement", "advertisements", "block", "blocker", "protection", "tracker", "trackers", "promo", "promos"),
            "Settings → Protection Center enables request, popup and known ad protection and shows local blocked-request activity. Reader can hide recognized promotional pages while keeping a reveal option and the original pages. Provider layouts change, and an advert already embedded into source video or artwork cannot always be removed safely."),
        Entry("ocr", "Reader OCR and lettering", setOf("ocr", "reader", "bubble", "bubbles", "lettering", "translation", "translate", "japanese", "korean", "chinese", "hindi", "hinglish", "text", "overlap"),
            "Reader tools offer original/translated comparison and bubble inspection. Inspect a difficult region, retry its OCR, then explicitly save a correction or glossary term. The original remains available. Recognizers support several scripts; stylized, vertical and crowded lettering can still need a correction. Hindi and Hinglish are separate output choices."),
        Entry("library", "Library and export", setOf("library", "saved", "chapter", "chapters", "bookmark", "bookmarks", "collection", "collections", "cbz", "export", "offline"),
            "Library keeps saved chapters, reading positions, bookmarks, notes and collections. Open a saved chapter for offline reading. Its CBZ export writes original pages to the destination you choose; it is not a full backup of personal corrections, model data or translation task records."),
        Entry("search", "Finding saved text", setOf("search", "semantic", "embedding", "embeddings", "dialogue", "memory", "find", "glossary"),
            "Library search can inspect local metadata and verified saved text. Semantic search needs its separately installed, verified embedding model and has bounded English-first input and result coverage. Search results must still pass the current saved-source checks when opened; an index or cache is not permission to read a missing or changed chapter."),
        Entry("speech", "Subtitles and voice", setOf("subtitle", "subtitles", "caption", "captions", "speech", "voice", "microphone", "whisper", "asr", "srt", "vtt"),
            "Use provider captions when an accessible captured inventory is available, or install the speech model for local audio recognition. Subtitle jobs keep source, language and model settings with their result. Orez voice input requires an explicit microphone tap and does not send the recognized draft automatically. Offline speech output depends on an installed offline Android voice."),
        Entry("models", "Local Orez models", setOf("model", "models", "lite", "core", "max", "install", "installed", "inference", "license", "licenses", "resource", "resources", "training", "lab"),
            "Settings → Orez Models shows the current installed and verified model state and offers a health recheck. Fast and Balanced resource modes use compatible verified models. Max requires qualified compatible evidence and is not offered by the current catalog. A downloaded weight file or imported training report alone does not prove Android inference quality."),
        Entry("recovery", "Recovering interrupted work", setOf("error", "errors", "failed", "failure", "stuck", "pause", "resume", "retry", "cancel", "recovery", "recover", "interrupted", "restart"),
            "Inspect the task's displayed reason before retrying. Use Pause/Resume for a resumable job, and choose an available quality if a provider has withdrawn a format. Recheck model health for a model issue. Failed download cleanup applies to the exact settled task; saved chapters, finished media and installed models are retained by image-cache cleanup."),
        Entry("privacy", "Browser profiles and privacy", setOf("browser", "profile", "profiles", "private", "work", "cookie", "cookies", "history", "privacy", "storage"),
            "Normal uses the existing browser storage. Work and named profiles require real profile support from the installed WebView; unavailable isolation is shown explicitly. Private browsing uses a session-only journal and retires its owned profile after the browser has closed. Native media and Reader handoffs currently require Normal, so nondefault cookies are not silently copied into those workflows.")
    )
}
