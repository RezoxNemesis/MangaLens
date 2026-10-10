package com.mangalens.download

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN: every selected initialization/media entry remains ordered and bounded. */
class OriginalFragmentPlanTest {
    private val format = """{"format_id":"dash-v1080","url":"https://cdn.example/base/","protocol":"http_dash_segments","ext":"mp4","vcodec":"avc1","fragment_base_url":"https://cdn.example/base/","fragments":[{"path":"init.mp4"},{"path":"one.m4s","duration":1},{"url":"https://cdn.example/two.m4s","duration":1}]}"""
    private fun parse(value: String = format) = OriginalFragmentPlan.capture(JSONObject(value), 2_000_000L)!!
    @Test fun relativeAndAbsoluteEntriesPreserveEntireSelectedOrder() {
        assertEquals(listOf("https://cdn.example/base/init.mp4", "https://cdn.example/base/one.m4s", "https://cdn.example/two.m4s"), parse().fragments.map { it.url })
        assertNull(parse().fragments.first().durationUs)
        assertEquals(1_000_000L, parse().fragments[1].durationUs)
    }
    @Test fun emptyPlanCannotBecomeCompletedTransport() {
        try { parse(JSONObject(format).put("fragments", org.json.JSONArray()).toString()); fail("Empty plan accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun fragmentOrderAndRangePartitionExactIdentity() {
        val original = parse(); val reordered = original.copy(fragments = original.fragments.reversed())
        assertNotEquals(original.sha256(), reordered.sha256())
        val ranged = original.copy(fragments = original.fragments.mapIndexed { i, f -> if (i == 1) f.copy(rangeStart = 10L, rangeEndExclusive = 30L) else f })
        assertNotEquals(original.sha256(), ranged.sha256())
    }
    @Test fun duplicateUrlsWithDistinctRangesAreNotDeduplicated() {
        val plan = parse(format.replace("{\"path\":\"one.m4s\",\"duration\":1},{\"url\":\"https://cdn.example/two.m4s\",\"duration\":1}", "{\"path\":\"media.mp4\",\"byte_range\":{\"start\":0,\"end\":20}},{\"path\":\"media.mp4\",\"byte_range\":{\"start\":20,\"end\":40}}"))
        assertEquals(3, plan.fragments.size)
        assertEquals(plan.fragments[1].url, plan.fragments[2].url)
        assertEquals(20L, plan.fragments[2].rangeStart)
    }
    @Test fun privateCodecColdReplayPreservesExactPlan() {
        val original = parse()
        val restored = OriginalFragmentPlan.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, restored); assertEquals(original.sha256(), restored.sha256())
    }
    @Test fun malformedUnsafeOrOverBudgetPlanFailsClosed() {
        for (bad in listOf(format.replace("one.m4s", "file:///private"), format.replace("one.m4s", "https://user:secret@evil.example/file"), format.replace("1}", "-1}"))) {
            try { parse(bad); fail("Unsafe plan accepted") } catch (_: IllegalArgumentException) { }
        }
        val many = JSONObject(format).put("fragments", org.json.JSONArray((0..OriginalFragmentPlan.MAX_FRAGMENTS).map { JSONObject().put("url", "https://cdn.example/$it") }))
        try { OriginalFragmentPlan.capture(many, 2_000_000L); fail("Truncated/overfull plan accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun fragmentPlanRequiresFiniteVodDurationAndKnownProtocol() {
        for (duration in listOf<Long?>(null, 0L, -1L, Long.MAX_VALUE)) {
            try { OriginalFragmentPlan.capture(JSONObject(format), duration); fail("Unbounded duration accepted") } catch (_: IllegalArgumentException) { }
        }
        assertNull(OriginalFragmentPlan.capture(JSONObject(format).put("protocol", "https"), 2_000_000L))
    }
    @Test fun unsupportedGeneratorOrDrmNeverBecomesProgressiveFallback() {
        for (fmt in listOf(JSONObject(format).put("protocol", "http_dash_segments_generator"), JSONObject(format).put("has_drm", true), JSONObject(format).put("is_live", true))) {
            try { OriginalFragmentPlan.capture(fmt, 2_000_000L); fail("Protected/unbounded format accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun declaredRangeMustBeCompleteNonnegativeAndExclusive() {
        for (range in listOf(JSONObject().put("start", -1).put("end", 20), JSONObject().put("start", 20).put("end", 20), JSONObject().put("start", 0))) {
            val fmt = JSONObject(format); fmt.getJSONArray("fragments").getJSONObject(1).put("byte_range", range)
            try { OriginalFragmentPlan.capture(fmt, 2_000_000L); fail("Invalid range accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
