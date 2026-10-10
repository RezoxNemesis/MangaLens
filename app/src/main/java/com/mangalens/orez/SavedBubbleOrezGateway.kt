package com.mangalens.orez

import com.mangalens.core.compute.NativeComputePrecondition

/** The dialog's callback has one exact local route, never the general chat/planner fallback. */
internal class SavedBubbleOrezGateway(private val service: OrezLocalModelService) {
    suspend fun explain(prompt: String, pin: OrezModelPin, capturedOwner: NativeComputePrecondition): String? =
        qualifiedText(prompt, pin, service.explainSavedBubbleWithReceipt(prompt, pin, capturedOwner))

    companion object {
        /** Synthetic receipt tests cover this gate; only the service supplies actual native completion. */
        internal fun qualifiedText(prompt: String, pin: OrezModelPin, answer: OrezModelAnswer?): String? =
            answer?.takeIf { it.model == pin && SavedBubbleOrezProfile.completed(prompt, it.completion) }
                ?.text?.trim()?.takeIf { it.isNotBlank() }
    }
}
