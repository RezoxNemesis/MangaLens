package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test

/** Scalar acceptance controls, no service construction or real native/model invocation. */
class SavedBubbleOrezGatewayTest {
    private val prompt = "Scoped saved excerpt fixture"
    private val pin = OrezModelPin("fixture-model", "a".repeat(64), 1234)
    private fun completion() = OrezGenerationCompletion(SavedBubbleOrezProfile.REVISION,
        OrezLocalizationProfile.hash(SavedBubbleOrezProfile.formattedPrompt(prompt)), "EOG", 12, 14,
        SavedBubbleOrezProfile.MAX_TOKENS, 0, 1, 2, 3)
    private fun answer() = OrezModelAnswer("  Fixture explanation  ", pin, completion())

    @Test fun exactModelAndCompletedExplanationReceiptCanSupplyTheLocalCallback() {
        assertEquals("Fixture explanation", SavedBubbleOrezGateway.qualifiedText(prompt, pin, answer()))
    }
    @Test fun generalChatAndKnownV2LocalizationResultsCannotBecomeBubbleExplanations() {
        assertNull(SavedBubbleOrezGateway.qualifiedText(prompt, pin, OrezModelAnswer("Chat fixture", pin)))
        val localization = completion().copy(profileRevision = OrezLocalizationV2.REVISION,
            formattedInputSha256 = OrezLocalizationProfile.hash(OrezLocalizationV2.formattedPrompt(prompt)),
            tokenLimit = OrezLocalizationV2.MAX_TOKENS)
        assertTrue(localization.completedLocalization(prompt))
        assertNull(SavedBubbleOrezGateway.qualifiedText(prompt, pin, answer().copy(completion = localization)))
    }
    @Test fun differentExactModelIdentityCannotBorrowAnExplanationReceipt() {
        for (model in listOf(pin.copy(modelId = "replacement"), pin.copy(sha256 = "b".repeat(64)), pin.copy(bytes = 1235)))
            assertNull(SavedBubbleOrezGateway.qualifiedText(prompt, pin, answer().copy(model = model)))
    }
    @Test fun differentPromptOrIncompleteNativeTerminationCannotSupplyText() {
        assertNull(SavedBubbleOrezGateway.qualifiedText("Changed question", pin, answer()))
        for (termination in listOf("TOKEN_LIMIT", "CANCELLED", "UNAVAILABLE", "DECODE_FAILURE"))
            assertNull(SavedBubbleOrezGateway.qualifiedText(prompt, pin, answer().copy(completion = completion().copy(termination = termination))))
    }
    @Test fun missingOrBlankResultCannotBePresentedAsGeneratedExplanation() {
        assertNull(SavedBubbleOrezGateway.qualifiedText(prompt, pin, null))
        assertNull(SavedBubbleOrezGateway.qualifiedText(prompt, pin, answer().copy(text = " ")))
    }
}
