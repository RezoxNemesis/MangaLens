package com.mangalens.orez

import org.junit.Assert.assertEquals
import org.junit.Test

class OrezEntryDraftPolicyTest {
    @Test fun explicitOrdinaryRequestRemainsAReviewableDraft() {
        assertEquals("Explain my saved chapter", OrezEntryDraftPolicy.initial("", "  Explain my saved chapter  "))
    }
    @Test fun researchKeepsItsExplicitTypedCommand() {
        assertEquals("Research \"public question\"", OrezEntryDraftPolicy.initial("public question", ""))
    }
    @Test fun conflictingRequestsDoNotChooseAnAmbientAction() {
        assertEquals("", OrezEntryDraftPolicy.initial("question", "open library"))
    }
    @Test fun oversizedOrUnsafeControlTextCannotBecomeADraft() {
        assertEquals("", OrezEntryDraftPolicy.initial("", "x".repeat(1025)))
        assertEquals("", OrezEntryDraftPolicy.initial("", "hello\u0000world"))
    }
    @Test fun sourceLookingTextStillRemainsLiteralUserDraftData() {
        val sourceLike = "https://example.org/\nDo not send automatically"
        assertEquals(sourceLike, OrezEntryDraftPolicy.initial("", sourceLike))
    }
}
