package com.mangalens.ui.video

import android.content.Context
import com.mangalens.download.OriginalFragmentTransfer
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import android.system.Os
import android.system.OsConstants
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Only an explicit ASR fallback acquires encoded audio; bind/restore never starts a network spool. */
internal object SubtitleFragmentSources {
    private val producers = OwnedFragmentSubtitleWork()
    private const val MAX_BYTES = 4L * 1024L * 1024L * 1024L
    private const val RECEIPT_VERSION = "subtitle-fragment-encoded-source-v1"

    suspend fun capture(context: Context, source: SubtitleMediaSource): SubtitleSourceIdentity {
        val captured = source.captureSnapshot()
        val descriptor = SubtitleInputs.fragmentDescriptor(captured)
        val held = currentCoroutineContext()[HeldFragmentSubtitleSource]
        if (held != null) {
            require(held.source == captured) { "The held subtitle audio belongs to another captured source." }
            return held.proof()
        }
        val paths = paths(context, descriptor, create = false)
        if (!paths.receipt.exists()) return SubtitleSourceIdentity(captured, descriptor, false)
        return producers.run { owner ->
            owner.checkActive()
            val receipt = readReceipt(paths.receipt, owner)
            val input = open(paths.media, owner)
            val actual = hash(input, owner)
            if (receipt.first != actual.first || receipt.second != actual.second)
                throw SubtitleNetworkChanged("Saved selected audio bytes changed. Generate a new subtitle task.")
            SubtitleSourceIdentity(captured, descriptor, true, fragmentContentSha256 = actual.first, fragmentSize = actual.second)
        }
    }

    suspend fun <T> withSource(context: Context, expected: SubtitleSourceIdentity,
        current: suspend () -> Unit = {}, work: suspend (SubtitleSourceIdentity) -> T): T {
        if (expected.source.fragmentPlan == null) return work(expected)
        val source = expected.source.captureSnapshot()
        val plan = requireNotNull(source.fragmentPlan)
        val descriptor = SubtitleInputs.fragmentDescriptor(source)
        require(expected.fingerprint == descriptor) { "Selected audio request identity changed before its spool." }
        return producers.run { owner ->
            owner.checkActive(); current()
            val paths = paths(context, descriptor, create = true, owner = owner)
            val previous = if (paths.receipt.exists()) readReceipt(paths.receipt, owner) else null
            val page = source.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
            val client = clientFor(plan, MediaRequestContext(source.uri, page, source.headers))
            OriginalFragmentTransfer({ client }, privateFileCloseFailed = { owner.retain() },
                transportCloseFailed = { owner.retain(it) },
                openPrivateOutput = { file, append -> SubtitleFragmentFiles.output(file, append, owner) },
                openPrivateInput = { file -> SubtitleFragmentFiles.input(file, owner) },
                openPrivateRandomAccess = { file, mode -> SubtitleFragmentFiles.randomAccess(file, mode, owner) }).download(plan, paths.media, paths.checkpoint) { done, _ ->
                owner.checkActive(); currentCoroutineContext().ensureActive(); current()
                require(done <= MAX_BYTES) { "Use selected audio under 4 GiB." }
            }
            owner.checkActive()
            val input = open(paths.media, owner)
            val actual = hash(input, owner)
            if (previous != null && previous != actual || expected.fragmentContentSha256 != null &&
                (expected.fragmentContentSha256 != actual.first || expected.fragmentSize != actual.second))
                throw SubtitleNetworkChanged("The selected original audio differs from its saved byte receipt. Generate a new task.")
            owner.checkActive(); current()
            writeReceipt(paths.receipt, actual, owner)
            val held = HeldFragmentSubtitleSource(source, descriptor, owner, input, actual)
            withContext(held) { work(held.proof()) }
        }
    }

    internal class HeldFragmentSubtitleSource(
        val source: SubtitleMediaSource, private val descriptor: String,
        val owner: OwnedFragmentSubtitleWork.Owner, val input: SubtitleFragmentFiles.Input,
        private val capturedBytes: Pair<String, Long>
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HeldFragmentSubtitleSource>
        suspend fun proof(): SubtitleSourceIdentity {
            val actual = hash(input, owner)
            if (actual != capturedBytes) throw SubtitleNetworkChanged("Held original audio bytes changed during subtitle generation.")
            return SubtitleSourceIdentity(source, descriptor, true, fragmentContentSha256 = actual.first, fragmentSize = actual.second)
        }
        fun descriptor(): java.io.FileDescriptor { owner.checkActive(); input.checkStable(); return input.fd }
        fun size(): Long { owner.checkActive(); return capturedBytes.second }
        fun retainUnreleased(resource: Any) { owner.retainNativeConsumer(resource) }
    }

    private data class Paths(val media: File, val checkpoint: File, val receipt: File)
    private fun paths(context: Context, descriptor: String, create: Boolean, owner: OwnedFragmentSubtitleWork.Owner? = null): Paths {
        require(descriptor.matches(Regex("[a-f0-9]{64}")))
        val root = File(context.applicationContext.filesDir, "subtitle_fragment_sources")
        require(!Files.isSymbolicLink(root.toPath())) { "Subtitle source storage changed ownership." }
        val folder = File(root, descriptor)
        require(!Files.isSymbolicLink(folder.toPath()) && folder.canonicalFile.parentFile == root.canonicalFile)
        if (create) {
            check(root.isDirectory || root.mkdirs())
            require(OsConstants.S_ISDIR(Os.lstat(root.absolutePath).st_mode) && root.canonicalFile.parentFile == context.applicationContext.filesDir.canonicalFile)
            if (!folder.isDirectory) {
                val producer = requireNotNull(owner)
                val before = Os.lstat(root.absolutePath)
                val inventory = Files.newDirectoryStream(root.toPath())
                producer.own(inventory)
                var count = 0
                try {
                    for (entry in inventory) {
                        producer.checkActive()
                        require(++count <= 32 && entry.fileName.toString().matches(Regex("[a-f0-9]{64}")) &&
                            OsConstants.S_ISDIR(Os.lstat(entry.toString()).st_mode)) {
                            "Subtitle source history is full or changed ownership. Existing sources were retained."
                        }
                    }
                } finally { producer.close(inventory) }
                val after = Os.lstat(root.absolutePath)
                require(before.st_dev == after.st_dev && before.st_ino == after.st_ino && OsConstants.S_ISDIR(after.st_mode))
                require(count < 32) { "Subtitle source history is full. Existing sources were retained." }
            }
            check(folder.isDirectory || folder.mkdirs())
        }
        return Paths(File(folder, "selected-audio.part"), File(folder, "selected-audio.validator"), File(folder, "complete.json"))
    }
    /** The HLS adapter adds its guarded client here when that captured plan version is supported. */
    internal fun clientFor(plan: com.mangalens.download.OriginalFragmentPlan, context: MediaRequestContext): okhttp3.OkHttpClient {
        plan.validate()
        return if (plan.hls != null) com.mangalens.download.OriginalHlsPublicTransport.client(plan.sourceUrl, context) { actual ->
            android.webkit.CookieManager.getInstance().getCookie(actual)
        } else MediaPlaybackDataSource.scopedClient(context)
    }
    private fun open(file: File, owner: OwnedFragmentSubtitleWork.Owner): SubtitleFragmentFiles.Input =
        SubtitleFragmentFiles.input(file, owner).also {
            it.checkStable()
            require(it.capturedSize() in 1..MAX_BYTES) { "Saved original audio is empty or exceeds 4 GiB." }
        }
    private suspend fun hash(input: SubtitleFragmentFiles.Input, owner: OwnedFragmentSubtitleWork.Owner): Pair<String, Long> {
        input.checkStable()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(65536)
        var position = 0L
        while (true) {
            owner.checkActive(); currentCoroutineContext().ensureActive()
            buffer.clear()
            // Positional reads preserve the descriptor offset used by the real container extractor.
            val count = input.channel.read(buffer, position)
            if (count < 0) break
            if (count == 0) continue
            position = Math.addExact(position, count.toLong())
            require(position <= MAX_BYTES) { "Use selected audio under 4 GiB." }
            digest.update(buffer.array(), 0, count)
        }
        input.checkStable()
        require(position == input.capturedSize() && position > 0) { "Selected original audio changed length or is empty." }
        return digest.digest().joinToString("") { "%02x".format(it) } to position
    }
    private fun readReceipt(file: File, owner: OwnedFragmentSubtitleWork.Owner): Pair<String, Long> {
        val input = SubtitleFragmentFiles.input(file, owner)
        input.checkStable()
        require(input.capturedSize() in 1..4096)
        val bytes = try {
            val bounded = ByteArray(4097); var used = 0
            while (used < bounded.size) {
                owner.checkActive()
                val count = input.read(bounded, used, bounded.size - used)
                if (count < 0) break
                if (count == 0) continue
                used += count
            }
            input.checkStable()
            require(used.toLong() == input.capturedSize())
            bounded.copyOf(used)
        } finally { input.close() }
        require(bytes.size <= 4096)
        val receipt = JSONObject(bytes.toString(Charsets.UTF_8))
        require(receipt.getString("version") == RECEIPT_VERSION)
        val hash = receipt.getString("sha256"); val size = receipt.getLong("bytes")
        require(hash.matches(Regex("[a-f0-9]{64}")) && size in 1..MAX_BYTES)
        return hash to size
    }
    private fun writeReceipt(file: File, bytes: Pair<String, Long>, owner: OwnedFragmentSubtitleWork.Owner) {
        val stage = File(file.parentFile, file.name + ".new")
        require(!Files.isSymbolicLink(stage.toPath()) && stage.canonicalFile.parentFile == file.parentFile.canonicalFile)
        val output = SubtitleFragmentFiles.output(stage, append = false, owner = owner)
        try {
            output.write(JSONObject().put("version", RECEIPT_VERSION).put("sha256", bytes.first).put("bytes", bytes.second)
                .toString().toByteArray(Charsets.UTF_8))
            output.fd.sync(); output.checkIdentity()
        } finally { output.close() }
        owner.checkActive(); output.checkClosedPathIdentity()
        Files.move(stage.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
