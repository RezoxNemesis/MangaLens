package com.mangalens.download

import java.io.File
import java.nio.file.Files

internal object FailedDownloadPartialPolicy {
    const val PENDING = "Partial cleanup requested"
    const val REMOVED = "Partial files removed"

    fun capture(item: DownloadEntity): DownloadEntity {
        check(item.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && !item.isAdaptive && item.destination == null &&
            (item.state == DownloadState.FAILED || item.state == DownloadState.CANCELLED && item.stage == PENDING)) {
            "Only a failed, unpublished file transfer can have its partial files removed."
        }
        return item
    }
}

/** A bounded exact-name plan, validated in full before any bytes are deleted. */
internal object FailedDownloadPartialFiles {
    private val attempt = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
    private val tail = "(?:mp4|webm|mkv|(?:source-video\\.(?:video|audio)|source-audio\\.audio|result\\.(?:video|audio))\\.timing)"

    fun plan(root: File, id: String): List<File> {
        check(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid transfer identity." }
        check(!Files.isSymbolicLink(root.toPath())) { "Download storage changed; partial files were retained." }
        val canonical = root.canonicalFile
        check(canonical.parentFile == root.parentFile?.canonicalFile) { "Download storage changed; partial files were retained." }
        if (!root.exists()) return emptyList()
        check(root.isDirectory) { "Download storage is unavailable." }
        val children = root.listFiles() ?: error("Download storage could not be inspected.")
        check(children.size <= 2_048) { "Download storage is too large to inspect safely; files were retained." }
        val exact = setOf("$id.part", "$id.validator", "$id.validator.new", "$id.audio.part", "$id.audio.validator", "$id.audio.validator.new", "$id.muxed.mp4")
        val remux = Regex(Regex.escape(id) + "\\.muxed-" + attempt + "\\." + tail)
        return children.filter { it.name in exact || remux.matches(it.name) }.onEach { file ->
            check(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.canonicalFile.parentFile == canonical) {
                "A partial file changed ownership; no files were removed."
            }
        }.sortedBy { it.name }
    }
}
