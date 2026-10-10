package com.mangalens.core.translation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** One pass is shared by every file, including a file that fails after reading bytes. */
internal class NativeIndexPassBudget(val maximum: Long = 32L * 1024 * 1024) {
    var used: Long = 0
        private set
    val remaining: Long get() = maximum - used
    init { require(maximum in 1..32L * 1024 * 1024) }
    fun consume(count: Int) { require(count >= 0 && count.toLong() <= remaining); used += count }
}

internal class NativeIndexHeaderLimitException : IllegalStateException("Image dimensions exceed bounded header inspection.")

/** No descriptor survives a pass. Incomplete digest state stays in memory and is never trusted after restart. */
internal class NativeIndexIncrementalHasher(private val spec: NativeIndexFileSpec,
    private val openHandle: (java.io.File) -> NativeIndexReadHandle = ::AndroidNativeIndexReadHandle) {
    private val stamp = nativeIndexStamp(spec.file).also { require(it.size in 1..spec.maximumBytes) }
    private val digest = MessageDigest.getInstance("SHA-256")
    private val prefix = ByteArrayOutputStream()
    private var descriptorIdentity: NativeIndexDescriptorIdentity? = null
    private var offset = 0L
    private var verifiedSha256: String? = null
    private var finished: NativeIndexVerifiedFile? = null
    val readBytes: Long get() = offset

    suspend fun pass(budget: NativeIndexPassBudget,
        dimensions: (ByteArray) -> Pair<Int, Int>?): NativeIndexVerifiedFile? {
        currentCoroutineContext().ensureActive()
        finished?.let { require(nativeIndexFileCurrent(it.stamp)); return it }
        require(nativeIndexFileCurrent(stamp)) { "Saved file changed during indexing." }
        openHandle(spec.file).use { handle ->
            require(handle.identity.size == stamp.size && handle.matchesPath() && nativeIndexFileCurrent(stamp))
            descriptorIdentity?.let { require(it == handle.identity) } ?: run { descriptorIdentity = handle.identity }
            handle.seek(offset)
            val bytes = ByteArray(64 * 1024)
            var noProgress = 0
            while (offset < stamp.size && budget.remaining > 0) {
                currentCoroutineContext().ensureActive()
                val count = handle.read(bytes, minOf(bytes.size.toLong(), budget.remaining, stamp.size - offset).toInt())
                require(count >= 0) { "Saved file became shorter during indexing." }
                if (count == 0) { check(++noProgress <= 3) { "Saved file read made no progress." }; continue }
                noProgress = 0; budget.consume(count)
                digest.update(bytes, 0, count)
                if (prefix.size() < 64 * 1024) prefix.write(bytes, 0, minOf(count, 64 * 1024 - prefix.size()))
                offset += count
            }
            require(handle.matchesPath() && nativeIndexFileCurrent(stamp)) { "Saved file changed during indexing." }
        }
        if (offset != stamp.size) return null
        val actual = verifiedSha256 ?: digest.digest().joinToString("") { "%02x".format(it) }.also { verifiedSha256 = it }
        require(actual == spec.expectedSha256) { "Saved file hash changed. Validate it in Reader." }
        val checkedDimensions = if (spec.needsDimensions) dimensions(prefix.toByteArray()) else null
        if (spec.needsDimensions && (checkedDimensions == null || checkedDimensions.first <= 0 || checkedDimensions.second <= 0)) throw NativeIndexHeaderLimitException()
        val checked = NativeIndexVerifiedFile(spec, stamp, actual, checkedDimensions).also { finished = it }
        currentCoroutineContext().ensureActive()
        return checked
    }
}
