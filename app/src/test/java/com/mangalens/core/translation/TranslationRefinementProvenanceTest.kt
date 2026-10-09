package com.mangalens.core.translation

import com.mangalens.orez.OrezModelPin
import org.junit.Assert.*
import org.junit.Test

class TranslationRefinementProvenanceTest {
    private val source = "Wait here, Mina."
    private val draft = "Mina, yahan ruko."
    private val target = "hi-latn"
    private val request = TranslationRefinementRequest(true, TranslationStyleProfile.custom("Keep names; use relaxed Hindi."),
        OrezModelPin("captured-lite", "a".repeat(64), 1234))
    private fun generated(): TranslationRefinementResult {
        val output = "Mina, yahin ruko."
        val prompt = "STYLE PROFILE ID: custom\n" + buildTranslationRefinementPrompt(source, draft, target, request.style, "", emptyMap())
        return TranslationRefinementResult(output, TranslationRefinementReceipt(request.pinnedModel!!,
            TranslationRefinementPolicy.hash(prompt), TranslationRefinementPolicy.hash(output)), TranslationRefinementStatus.GENERATED)
    }
    private fun matches(result:TranslationRefinementResult=generated(), source:String=this.source,
        draft:String=this.draft, target:String=this.target, request:TranslationRefinementRequest=this.request,
        context:String="", glossary:Map<String,String> = emptyMap()) =
        TranslationRefinementPolicy.matches(result,source,draft,target,request,context,glossary)

    @Test fun exactCapturedGenerationReceiptMatches() { assertTrue(matches()) }
    @Test fun modelAndActualOutputCannotBorrowAnotherReceipt() {
        val generated=generated()
        assertFalse(matches(generated.copy(text="An unrelated English sentence.")))
        assertFalse(matches(request=request.copy(pinnedModel=request.pinnedModel!!.copy(sha256="b".repeat(64)))))
    }
    @Test fun sourceDraftTargetCustomInstructionFlagsContextAndGlossaryRemainBound() {
        assertFalse(matches(source="Another source."))
        assertFalse(matches(draft="Another draft."))
        assertFalse(matches(target="hi"))
        assertFalse(matches(request=request.copy(style=TranslationStyleProfile.custom("Use formal Hindi."))))
        assertFalse(matches(request=request.copy(style=request.style.copy(id="formal"))))
        assertFalse(matches(request=request.copy(style=request.style.copy(name="Formal"))))
        assertFalse(matches(request=request.copy(style=request.style.copy(preserveNames=false))))
        assertFalse(matches(request=request.copy(style=request.style.copy(preserveHonorifics=false))))
        assertFalse(matches(request=request.copy(style=request.style.copy(naturalDialogue=false))))
        assertFalse(matches(context="Mina is the captain."))
        assertFalse(matches(glossary=mapOf("Mina" to "Captain Mina")))
    }
    @Test fun disabledUnavailableTimedOutAndMissingPinsNeverClaimRefinement() {
        assertFalse(matches(request=request.copy(enabled=false)))
        assertFalse(matches(request=request.copy(pinnedModel=null)))
        for (status in TranslationRefinementStatus.entries.filterNot { it==TranslationRefinementStatus.GENERATED })
            assertFalse(matches(generated().copy(status=status)))
        assertFalse(matches(generated().copy(receipt=null)))
    }
    @Test fun oversizeAndBlankOutputsCannotBeReceipted() {
        for (text in listOf("", " ", "a".repeat(6001))) {
            val result=generated().copy(text=text,receipt=generated().receipt!!.copy(outputSha256=TranslationRefinementPolicy.hash(text)))
            assertFalse(matches(result))
        }
    }
    @Test fun promptLimitNeverReceiptsAnInputTheNativeBoundaryWouldTruncate() {
        assertFalse(matches(source="a".repeat(4001)))
        assertFalse(matches(context="a".repeat(4501)))
        assertFalse(TranslationRefinementPolicy.validInputs("a".repeat(4000),"b".repeat(4000),target,
            request.style,"c".repeat(4500),emptyMap()))
    }
}
