package com.mangalens.download

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Authored UNRUN: fixed synthetic facts, no network/native/provider execution. */
class OriginalHlsVodPlaylistTest {
    private val source = CapturedHlsTrackSource("https://fixture.invalid/v/index.m3u8?synthetic=anchor", "hls-v1080", "video/mp4", "m3u8_native")
    private val playlist = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:2
        #EXT-X-MEDIA-SEQUENCE:11
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:2,
        one.m4s
        #EXTINF:2,
        two.m4s
        #EXT-X-ENDLIST
    """.trimIndent()
    private fun parse(text: String = playlist, bytes: ByteArray = text.toByteArray(), checkpoint: () -> Unit = {}) =
        OriginalHlsVodPlaylist.capture(source, "https://cdn.fixture.invalid/path/index.m3u8?synthetic=final",
            "application/vnd.apple.mpegurl", bytes, 4_000_000, checkpoint)
    private fun rejected(text: String) = assertTrue(runCatching { parse(text) }.isFailure)

    @Test fun actualFinalBaseResolvesEntireOrderedMapAndMediaWithoutQueryPromotion() {
        val plan = parse()
        assertEquals(listOf("https://cdn.fixture.invalid/path/init.mp4", "https://cdn.fixture.invalid/path/one.m4s", "https://cdn.fixture.invalid/path/two.m4s"), plan.fragments.map { it.url })
        assertEquals(source.sourceUrl, plan.sourceUrl)
        assertEquals(OriginalFragmentPlan.HLS_VERSION, plan.version)
        assertEquals(11L, plan.hls!!.mediaSequence)
        assertEquals(4_000_000L, plan.hls!!.observedDurationUs)
        assertNull(plan.fragments.first().durationUs)
    }
    @Test fun privateReceiptRoundTripPreservesPlaylistAndPlanIdentity() {
        val original = parse(); val replay = OriginalFragmentPlan.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, replay); assertEquals(original.sha256(), replay.sha256())
    }
    @Test fun actualPlaylistBodyProtocolAndMediaOrderPartitionPrivateIdentity() {
        val original = parse()
        assertNotEquals(original.sha256(), parse(playlist + "\n#synthetic-comment").sha256())
        assertNotEquals(original.sha256(), original.copy(hls = original.hls!!.copy(protocol = "m3u8")).sha256())
        assertNotEquals(original.sha256(), original.copy(fragments = listOf(original.fragments[0], original.fragments[2], original.fragments[1])).sha256())
    }
    @Test fun legacyDashV1HashHasNoAppendedHlsMarker() {
        val plan = OriginalFragmentPlan("https://fixture.invalid/dash", "dash-v1080", "video/mp4", 4_000_000,
            listOf(OriginalMediaFragment("https://fixture.invalid/init"), OriginalMediaFragment("https://fixture.invalid/one", durationUs = 4_000_000)))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(s: String) { val b = s.toByteArray(); out.writeInt(b.size); out.write(b) }
            fun optional(n: Long?) { out.writeBoolean(n != null); if (n != null) out.writeLong(n) }
            text("original-dash-fragment-sequence-v1"); text(plan.sourceUrl); text(plan.formatId); text(plan.mediaMime)
            out.writeLong(4_000_000); out.writeInt(2)
            plan.fragments.forEach { f -> text(f.url); optional(f.rangeStart); optional(f.rangeEndExclusive); optional(f.expectedBytes); optional(f.durationUs) }
        }
        val oldHash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(oldHash, plan.sha256()); assertFalse(plan.toJson().has("hls"))
    }
    @Test fun unknownVersionOrMissingReceiptCannotMasqueradeAsEitherTransport() {
        val plan = parse()
        assertTrue(runCatching { plan.copy(hls = null).validate() }.isFailure)
        assertTrue(runCatching { plan.copy(version = OriginalFragmentPlan.VERSION).validate() }.isFailure)
        assertTrue(runCatching { plan.copy(version = "future-plan").validate() }.isFailure)
    }
    @Test fun mapAndMediaOffsetsRemainSeparateAndImplicitMediaRequiresSamePredecessor() {
        val ranged = playlist.replace("URI=\"init.mp4\"", "URI=\"shared.mp4\",BYTERANGE=\"10@0\"")
            .replace("one.m4s", "#EXT-X-BYTERANGE:20@100\nshared.mp4")
            .replace("two.m4s", "#EXT-X-BYTERANGE:30\nshared.mp4")
        val plan = parse(ranged)
        assertEquals(listOf(0L, 100L, 120L), plan.fragments.map { it.rangeStart })
        assertEquals(listOf(10L, 120L, 150L), plan.fragments.map { it.rangeEndExclusive })
        rejected(ranged.replace("20@100", "20"))
        rejected(ranged.replace("#EXT-X-BYTERANGE:30\nshared.mp4", "#EXT-X-BYTERANGE:30\nother.mp4"))
    }
    @Test fun byteRangeOverflowZeroLengthAndImplicitMapAreRejected() {
        for (range in listOf("0@0", "10", "2@9223372036854775807", "18446744073709551616@0"))
            rejected(playlist.replace("URI=\"init.mp4\"", "URI=\"init.mp4\",BYTERANGE=\"$range\""))
    }
    @Test fun wrappedVersionLongCannotBecomeSupportedVersionSix() {
        for (version in listOf("4294967302", "-1", "5", "13", "18446744073709551616"))
            rejected(playlist.replace("VERSION:7", "VERSION:$version"))
    }
    @Test fun liveMissingMapMasterEncryptedDiscontinuousAndLowLatencyAreRejected() {
        rejected(playlist.replace("#EXT-X-ENDLIST", ""))
        rejected(playlist.replace("#EXT-X-MAP:URI=\"init.mp4\"\n", ""))
        for (tag in listOf("#EXT-X-KEY:METHOD=NONE", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"", "#EXT-X-DISCONTINUITY", "#EXT-X-GAP", "#EXT-X-PART:DURATION=1,URI=\"part\"", "#EXT-X-STREAM-INF:BANDWIDTH=1", "#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES"))
            rejected(playlist.replace("#EXTINF:2,", "$tag\n#EXTINF:2,"))
    }
    @Test fun duplicateMapConflictingMetadataUnknownMapAttributesAndUnquotedMapAreRejected() {
        rejected(playlist.replace("#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-MAP:URI=\"other.mp4\""))
        rejected(playlist.replace("#EXT-X-VERSION:7", "#EXT-X-VERSION:7\n#EXT-X-VERSION:8"))
        rejected(playlist.replace("URI=\"init.mp4\"", "URI=\"init.mp4\",UNKNOWN=1"))
        rejected(playlist.replace("URI=\"init.mp4\"", "URI=init.mp4"))
    }
    @Test fun malformedUtf8NulBodyLinesAndLineLengthAreBoundedBeforePublication() {
        assertTrue(runCatching { parse(bytes = byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
        rejected(playlist + "\n#\u0000")
        rejected(playlist + "\n#" + "x".repeat(OriginalHlsVodPolicy.MAX_LINE_CHARS))
        rejected(playlist + "\n".repeat(OriginalHlsVodPolicy.MAX_PLAYLIST_LINES))
        assertTrue(runCatching { parse(bytes = ByteArray(OriginalHlsVodPolicy.MAX_PLAYLIST_BYTES + 1)) }.isFailure)
    }
    @Test fun overlongSequenceDurationMissingUriAndMetadataAfterEndAreRejected() {
        rejected(playlist.replace("SEQUENCE:11", "SEQUENCE:9223372036854775808"))
        rejected(playlist.replace("#EXTINF:2,", "#EXTINF:21601,"))
        rejected(playlist.replace("two.m4s\n", ""))
        rejected(playlist + "\n#EXT-X-MAP:URI=\"late.mp4\"")
        rejected(playlist.replace("TARGETDURATION:2", "TARGETDURATION:1"))
    }
    @Test fun resolvedLongBaseCannotGrowPastPrivatePlanCapDespiteSmallRawBody() {
        val longBase = "https://cdn.fixture.invalid/" + "x".repeat(7_900) + "/index.m3u8"
        val many = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:1\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
            (0..99).joinToString("\n") { "#EXTINF:1,\ns$it.m4s" } + "\n#EXT-X-ENDLIST"
        assertTrue(many.toByteArray().size < OriginalHlsVodPolicy.MAX_PLAYLIST_BYTES)
        assertTrue(runCatching { OriginalHlsVodPlaylist.capture(source, longBase, "application/vnd.apple.mpegurl", many.toByteArray(), 100_000_000) }.isFailure)
    }
    @Test fun privateLiteralCredentialFragmentSchemeDowngradeAndPortAreRejected() {
        for (url in listOf("http://cdn.fixture.invalid/one", "https://127.0.0.1/one", "https://[::1]/one", "https://10.0.0.1/one", "https://user:synthetic@cdn.fixture.invalid/one", "file:///private/one", "https://cdn.fixture.invalid:8443/one", "https://cdn.fixture.invalid/one#secret"))
            rejected(playlist.replace("one.m4s", url))
    }
    @Test fun cancelDuringParsePreventsDeliveryWithoutChangingCapturedFacts() {
        var checks = 0
        val failure = runCatching { parse(checkpoint = { if (++checks == 5) throw java.util.concurrent.CancellationException("synthetic") }) }.exceptionOrNull()
        assertTrue(failure is java.util.concurrent.CancellationException)
    }
    @Test fun capturedFragmentListIsReadOnlyAndDuplicateRangesAreNotDeduplicated() {
        val plan = parse(playlist.replace("one.m4s", "#EXT-X-BYTERANGE:10@0\nshared.mp4").replace("two.m4s", "#EXT-X-BYTERANGE:10@10\nshared.mp4"))
        assertEquals(3, plan.fragments.size); assertEquals(plan.fragments[1].url, plan.fragments[2].url)
        assertTrue(runCatching { (plan.fragments as MutableList).clear() }.isFailure)
    }
}
