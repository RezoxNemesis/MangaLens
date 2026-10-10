package com.mangalens.ui.settings

import java.io.File
import java.io.IOException

internal enum class ManagedStorageCategory(val label: String) {
    MANGA("Manga"), VIDEO("Video and subtitles"), AI_MODELS("AI models"), CACHE("Cache and temporary work"), OTHER("Other")
}

internal data class ManagedStorageRoot(val directory: File, val category: ManagedStorageCategory)
internal data class ManagedStorageOverview(val bytes: Map<ManagedStorageCategory, Long>) {
    val totalBytes: Long get() = bytes.values.fold(0L) { total, size -> Math.addExact(total, size) }
}

/** Read-only filesystem metadata. No provider access, following symlinks, or deletion occurs here. */
internal object ManagedStorageInventory {
    private const val MAX_ITEMS = 100_000
    private const val MAX_DEPTH = 32

    fun scan(roots: List<ManagedStorageRoot>): ManagedStorageOverview {
        val bytes = ManagedStorageCategory.entries.associateWith { 0L }.toMutableMap()
        val observed = hashSetOf<String>()
        var items = 0
        roots.forEach { root ->
            if (!root.directory.exists()) return@forEach
            val canonicalRoot = root.directory.canonicalFile
            val pending = java.util.ArrayDeque<Pair<File, Int>>()
            pending.add(root.directory to 0)
            while (pending.isNotEmpty()) {
                val (file, depth) = pending.removeFirst()
                if (++items > MAX_ITEMS || depth > MAX_DEPTH) throw IOException("Storage inventory is too large to inspect safely.")
                val relative = root.directory.absoluteFile.toPath().normalize().relativize(file.absoluteFile.toPath().normalize())
                val expected = canonicalRoot.toPath().resolve(relative).normalize()
                val canonical = file.canonicalFile
                if (canonical.toPath() != expected) throw IOException("Storage contains linked paths; their size was not included.")
                if (!canonical.toPath().startsWith(canonicalRoot.toPath())) throw IOException("Storage path escaped its owned directory.")
                if (!observed.add(canonical.path)) continue
                when {
                    file.isDirectory -> {
                        val children = file.listFiles() ?: throw IOException("An app storage directory cannot be inspected.")
                        if (children.size > MAX_ITEMS - items) throw IOException("Storage inventory is too large to inspect safely.")
                        children.forEach { pending.add(it to depth + 1) }
                    }
                    file.isFile -> bytes[root.category] = Math.addExact(bytes.getValue(root.category), file.length().coerceAtLeast(0))
                    !file.exists() -> Unit // A file retired during this read-only snapshot.
                    else -> throw IOException("An app storage item cannot be inspected.")
                }
            }
        }
        return ManagedStorageOverview(bytes)
    }
}

/** A cleanup request delegates to the cache owner; it never walks active workspace directories. */
internal fun clearOwnedImageCache(clearDisk: () -> Unit, clearMemory: () -> Unit): String {
    clearDisk()
    clearMemory()
    return "Image cache cleared. Saved media, models and active work are retained."
}

internal object AndroidManagedStorage {
    fun scan(context: android.content.Context): ManagedStorageOverview {
        val app = context.applicationContext
        val roots = mutableListOf<ManagedStorageRoot>()
        val manga = setOf("chapters", "chapter_library", "chapter_translations", "reader_memory")
        val video = setOf("downloads", "media_download_cache", "subtitle_jobs", "subtitle-export-requests", "captions", "video_history")
        val models = setOf("orez_models", "speech", "orez_packs", "semantic_models")
        app.filesDir.listFiles()?.forEach { file ->
            if (file.canonicalFile != File(app.filesDir.canonicalFile, file.name))
                throw IOException("App storage contains linked paths; their size was not included.")
            val category = when (file.name) {
                in manga -> ManagedStorageCategory.MANGA
                in video -> ManagedStorageCategory.VIDEO
                in models -> ManagedStorageCategory.AI_MODELS
                else -> ManagedStorageCategory.OTHER
            }
            roots += ManagedStorageRoot(file, category)
        } ?: throw IOException("App storage could not be inspected.")
        roots += ManagedStorageRoot(app.cacheDir, ManagedStorageCategory.CACHE)
        roots += ManagedStorageRoot(app.noBackupFilesDir, ManagedStorageCategory.OTHER)
        roots += ManagedStorageRoot(File(app.applicationInfo.dataDir, "databases"), ManagedStorageCategory.OTHER)
        roots += ManagedStorageRoot(File(app.applicationInfo.dataDir, "shared_prefs"), ManagedStorageCategory.OTHER)
        app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.let {
            roots += ManagedStorageRoot(it, ManagedStorageCategory.VIDEO)
        }
        return ManagedStorageInventory.scan(roots)
    }
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearImageCache(context: android.content.Context): String {
        val loader = coil.Coil.imageLoader(context.applicationContext)
        return clearOwnedImageCache({ loader.diskCache?.clear() }, { loader.memoryCache?.clear() })
    }
}
