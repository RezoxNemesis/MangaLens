package com.mangalens

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RenderedWebFixtureHeadingTest {
    @Test fun recordedVisibleRecoveryDocumentHasTheExactHeadingInsideItsViewport() {
        assertTrue(renderedWebFixtureHeading(recordedSnapshot(), URL, HEADING))
    }

    @Test fun previousDocumentOrErrorHeadingCannotPretendThatRecoveryRendered() {
        assertFalse(renderedWebFixtureHeading(recordedSnapshot().put("url", "http://localhost:36527/first"), URL, HEADING))
        assertFalse(renderedWebFixtureHeading(recordedSnapshot().put("heading", "Webpage not available"), URL, HEADING))
    }

    @Test fun incompleteOrHiddenHeadingCannotSupplyVisualEvidence() {
        assertFalse(renderedWebFixtureHeading(recordedSnapshot().put("readyState", "loading"), URL, HEADING))
        for ((property, value) in listOf("display" to "none", "visibility" to "hidden", "opacity" to "0")) {
            val snapshot = recordedSnapshot()
            snapshot.getJSONObject("headingStyle").put(property, value)
            assertFalse("Hidden $property=$value counted as rendered", renderedWebFixtureHeading(snapshot, URL, HEADING))
        }
    }

    @Test fun clippedOffscreenOrEmptyBoundsDoNotPassTheHeadingAssertion() {
        for ((property, value) in listOf("left" to -1, "top" to 600, "width" to 0, "height" to 900)) {
            val snapshot = recordedSnapshot()
            snapshot.getJSONObject("headingBounds").put(property, value)
            assertFalse("Invalid $property=$value counted as visible", renderedWebFixtureHeading(snapshot, URL, HEADING))
        }
    }

    @Test fun omittedViewportOrBoundsFailSafely() {
        assertFalse(renderedWebFixtureHeading(recordedSnapshot().apply { remove("viewport") }, URL, HEADING))
        val snapshot = recordedSnapshot()
        snapshot.getJSONObject("headingBounds").remove("width")
        assertFalse(renderedWebFixtureHeading(snapshot, URL, HEADING))
    }

    private fun recordedSnapshot() = JSONObject("""
        {"url":"$URL","readyState":"complete","title":"MangaLens fixture recovered",
         "bodyText":"$HEADING","heading":"$HEADING",
         "headingBounds":{"left":24,"top":180.15625,"width":272,"height":171},
         "headingStyle":{"display":"block","visibility":"visible","opacity":"1"},
         "viewport":{"width":320,"height":532}}
    """.trimIndent())

    companion object {
        private const val URL = "http://localhost:36527/recover"
        private const val HEADING = "Fixture connection restored"
    }
}
