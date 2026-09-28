package com.mangalens.core.verification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptchaBridgeTest {

    @Test
    fun requestVerification_entersRequiredState() = runTest {
        val bridge = CaptchaBridge()

        bridge.requestVerification(
            url = "https://example.com/chapter",
            domain = "example.com",
            reason = "HTTP 403"
        )

        assertEquals(VerificationState.VERIFICATION_REQUIRED, bridge.state.value.state)
        assertEquals("example.com", bridge.state.value.request?.domain)
    }

    @Test
    fun completeVerification_requiresCookie() = runTest {
        val bridge = CaptchaBridge()
        bridge.requestVerification(
            url = "https://example.com/chapter",
            domain = "example.com",
            reason = "Challenge"
        )

        bridge.completeVerification(cookie = "", userAgent = "Test UA")

        assertEquals(VerificationState.FAILED, bridge.state.value.state)
        assertNull(bridge.state.value.cookie)
    }

    @Test
    fun completeVerification_publishesSession() = runTest {
        val bridge = CaptchaBridge()
        bridge.requestVerification(
            url = "https://example.com/chapter",
            domain = "example.com",
            reason = "Challenge"
        )

        bridge.beginVerification()
        bridge.completeVerification(
            cookie = "cf_clearance=test",
            userAgent = "Test UA"
        )

        assertEquals(VerificationState.VERIFIED, bridge.state.value.state)
        assertEquals("cf_clearance=test", bridge.state.value.cookie)
        assertEquals("Test UA", bridge.state.value.userAgent)
    }
}
