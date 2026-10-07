package com.mangalens.orez.agent

import org.junit.Assert.*
import org.junit.Test

class OrezModelPlanDecoderTest {
    private val decoder = OrezModelPlanDecoder()
    @Test fun validModelToolUsesRuntimeOwnedPermissions() {
        val plan = decoder.decode("""{"tool":"open_library","arguments":{}}""", "Show saved stories", OrezAgentContext())!!
        assertEquals(OrezToolRisk.READ_ONLY, plan.steps.single().call.risk)
    }
    @Test fun inventedDangerousToolIsRejected() {
        assertNull(decoder.decode("""{"tool":"execute_shell","arguments":{"value":"rm"}}""", "Open library", OrezAgentContext()))
    }
    @Test fun modelCannotInjectItsOwnRiskOrApproval() {
        assertNull(decoder.decode("""{"tool":"open_library","arguments":{},"risk":"READ_ONLY"}""", "Open library", OrezAgentContext()))
    }
    @Test fun emptyChapterAndUntrustedOriginAreRejected() {
        assertNull(decoder.decode("""{"tool":"translate_active_chapter","arguments":{}}""", "Translate", OrezAgentContext()))
        assertNull(decoder.decode("""{"tool":"open_library","arguments":{}}""", "Open", OrezAgentContext(origin = OrezTrustOrigin.WEB_CONTENT)))
    }
    @Test fun questionsDoNotActivateModelToolSelection() {
        assertFalse(OrezModelPlanDecoder.isActionRequest("How do I download a chapter?"))
        assertFalse(OrezModelPlanDecoder.isActionRequest("Explain this panel"))
        assertTrue(OrezModelPlanDecoder.isActionRequest("Please open my saved stories"))
    }
    @Test fun openRequestCannotBeReinterpretedAsDownload() {
        assertNull(decoder.decode("""{"tool":"enqueue_download","arguments":{"value":"https://example.com/v.mp4"}}""",
            "Open this video", OrezAgentContext()))
    }
    @Test fun modelMustRetainTheApprovedSourceUrl() {
        val context = OrezAgentContext(activeUrl = "https://example.com/v.mp4?sig=abc")
        assertNotNull(decoder.decode("""{"tool":"open_video_url","arguments":{"value":"https://example.com/v.mp4?sig=abc"}}""", "Play this video", context))
        assertNull(decoder.decode("""{"tool":"open_video_url","arguments":{"value":"https://other.example.com/v.mp4"}}""", "Play this video", context))
    }
}
