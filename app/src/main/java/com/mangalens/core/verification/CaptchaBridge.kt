package com.mangalens.core.verification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VerificationState {
    IDLE,
    VERIFICATION_REQUIRED,
    VERIFYING,
    VERIFIED,
    FAILED
}

data class VerificationRequest(
    val url: String,
    val domain: String,
    val reason: String
)

data class VerificationUiState(
    val state: VerificationState = VerificationState.IDLE,
    val request: VerificationRequest? = null,
    val cookie: String? = null,
    val userAgent: String? = null,
    val errorMessage: String? = null
)

/**
 * Process-safe bridge between acquisition workers and the Compose verification UI.
 *
 * Workers request verification; the UI completes it after the user solves the challenge.
 */
class CaptchaBridge {

    private val mutableState = MutableStateFlow(VerificationUiState())
    val state: StateFlow<VerificationUiState> = mutableState.asStateFlow()

    @Synchronized
    fun requestVerification(url: String, domain: String, reason: String) {
        mutableState.value = VerificationUiState(
            state = VerificationState.VERIFICATION_REQUIRED,
            request = VerificationRequest(
                url = url,
                domain = domain,
                reason = reason
            )
        )
    }

    @Synchronized
    fun beginVerification() {
        val current = mutableState.value
        if (current.request == null) return

        mutableState.value = current.copy(
            state = VerificationState.VERIFYING,
            errorMessage = null
        )
    }

    @Synchronized
    fun completeVerification(cookie: String, userAgent: String) {
        val current = mutableState.value
        if (current.request == null || cookie.isBlank()) {
            failVerification("Verification completed without a usable session cookie.")
            return
        }

        mutableState.value = current.copy(
            state = VerificationState.VERIFIED,
            cookie = cookie,
            userAgent = userAgent,
            errorMessage = null
        )
    }

    @Synchronized
    fun failVerification(message: String) {
        val current = mutableState.value
        mutableState.value = current.copy(
            state = VerificationState.FAILED,
            errorMessage = message
        )
    }

    @Synchronized
    fun reset() {
        mutableState.value = VerificationUiState()
    }
}
