package com.mangalens.orez

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Pure validation of a bounded bundled notice. Call after asset acquisition on IO, not on Main. */
internal object OrezModelLicenseText {
    const val MAX_BYTES = 64 * 1024
    fun verified(bytes: ByteArray, expectedSha256: String): String? {
        if (bytes.size !in 1..MAX_BYTES || !expectedSha256.matches(Regex("[a-f0-9]{64}")) ||
            OrezEvaluationCanonical.sha256(bytes) != expectedSha256) return null
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { null }
    }
}
