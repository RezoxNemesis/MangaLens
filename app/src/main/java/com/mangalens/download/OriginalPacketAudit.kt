package com.mangalens.download

import java.security.MessageDigest
import java.io.File

internal data class EncodedTrackAudit(
    val samples: Long,
    val encodedBytes: Long,
    val lastSampleEndUs: Long,
    val payloadFingerprint: String,
    val firstSampleUs: Long,
    val maximumSampleDurationUs: Long = 0L,
    val timingReceipt: File? = null
) {
    fun hasSamePayload(other: EncodedTrackAudit): Boolean = samples == other.samples &&
        encodedBytes == other.encodedBytes && payloadFingerprint == other.payloadFingerprint
}

/** Streaming packet hashes; never keeps encoded bytes, descriptions, cookies or URLs. */
internal class OriginalPacketAudit(indices: Set<Int>, private val ignoredIndices: Set<Int> = emptySet(),
                                  timingReceipts: Map<Int, File> = emptyMap()) : AutoCloseable {
    private class Track(val timingReceipt: File?) {
        var samples = 0L
        var bytes = 0L
        var firstUs = Long.MAX_VALUE
        var endUs = Long.MIN_VALUE
        var maximumDurationUs = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        var timing: OriginalPacketTiming.Writer? = null
    }
    private val tracks = indices.associateWith { Track(timingReceipts[it]) }
    private var finished: Map<Int, EncodedTrackAudit>? = null

    fun add(line: String) {
        check(finished == null)
        if (line.isBlank()) return
        require(line.length <= 4096) { "Original packet metadata exceeded its safe limit." }
        val fields = line.split('|').mapNotNull { item ->
            val at = item.indexOf('='); if (at < 1) null else item.substring(0, at) to item.substring(at + 1)
        }.toMap()
        val index = fields["stream_index"]?.toIntOrNull()
        if (index in ignoredIndices) return
        val track = tracks[index] ?: error("Original packet belongs to an unexpected track.")
        val pts = secondsUs(fields["pts_time"]) ?: error("Original packet has no verifiable timestamp.")
        val duration = secondsUs(fields["duration_time"])?.coerceAtLeast(0L) ?: 0L
        val size = fields["size"]?.toLongOrNull()?.takeIf { it in 1L..256L * 1024L * 1024L }
            ?: error("Original packet has no valid payload size.")
        val hash = fields["data_hash"]?.takeIf { it.matches(Regex("SHA256:[0-9a-fA-F]{64}")) }
            ?: error("Original packet has no verifiable payload hash.")
        check(track.samples < 50_000_000L && track.bytes <= Long.MAX_VALUE - size)
        track.samples++
        track.bytes += size
        track.firstUs = minOf(track.firstUs, pts)
        track.endUs = maxOf(track.endUs, Math.addExact(pts, duration))
        track.maximumDurationUs = maxOf(track.maximumDurationUs, duration)
        track.timingReceipt?.let { file ->
            val writer = track.timing ?: OriginalPacketTiming.Writer(file).also { track.timing = it }
            writer.add(pts, duration)
        }
        track.digest.update("$size:${hash.lowercase()}\n".toByteArray(Charsets.US_ASCII))
    }

    fun finish(): Map<Int, EncodedTrackAudit> = finished ?: tracks.mapValues { (_, track) ->
        check(track.samples > 0L) { "Original media contains an empty track." }
        track.timing?.close(); track.timing = null
        EncodedTrackAudit(track.samples, track.bytes, track.endUs,
            track.digest.digest().joinToString("") { "%02x".format(it) }, track.firstUs,
            track.maximumDurationUs, track.timingReceipt)
    }.also { finished = it }

    override fun close() {
        var failure: Exception? = null
        tracks.values.forEach { track ->
            try { track.timing?.close() }
            catch (problem: Exception) { if (failure == null) failure = problem else failure!!.addSuppressed(problem) }
            finally { track.timing = null }
        }
        failure?.let { throw it }
    }

    private fun secondsUs(value: String?): Long? = value?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > Long.MIN_VALUE / 1_000_000.0 && it < Long.MAX_VALUE / 1_000_000.0 }
        ?.let { (it * 1_000_000.0).toLong() }
}
