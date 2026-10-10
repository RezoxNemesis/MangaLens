package com.mangalens.ui.video

import com.mangalens.orez.OrezGenerationCompletion
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubtitleRefinementCompletionCodecTest {
    @Test fun savedTokenLimitEvidenceCannotBeConvertedIntoEndOfGeneration() {
        val native = OrezGenerationCompletion("orez-localization-v2", "a".repeat(64), "TOKEN_LIMIT",
            300, 288, 288, 12, 100, 5000, 6000)
        val saved = native.subtitleCompletion()
        val restored = SubtitleRefinementCompletionCodec.decode(SubtitleRefinementCompletionCodec.encode(saved))
        assertEquals(saved, restored)
        assertEquals(native, restored.generationCompletion())
        assertEquals("TOKEN_LIMIT", restored.termination)
    }

    @Test fun missingCompletionReasonIsRejectedInsteadOfInventingAnEogReceipt() {
        val incomplete = JSONObject().put("profileRevision", "orez-localization-v2")
            .put("formattedInputSha256", "a".repeat(64)).put("promptTokens", 300)
            .put("generatedTokens", 15).put("tokenLimit", 288)
            .put("nativeLockWaitUs", 0).put("setupUs", 1).put("prefillUs", 2).put("decodeUs", 3)
        try {
            SubtitleRefinementCompletionCodec.decode(incomplete)
            fail("Missing completion state was upgraded to EOG")
        } catch (_: org.json.JSONException) { }
    }
}
