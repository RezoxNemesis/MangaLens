package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test

class OrezModelLicenseTextTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/orez-governance/$name")).use { it.readBytes() }
    @Test fun actualPinnedPublisherLicenseIsReadableWithoutModelExecution() {
        val pin = OrezModelCatalog.lite.let { OrezModelPin(it.id, it.sha256, it.bytes) }
        val record = requireNotNull(OrezModelGovernance.find(pin))
        assertTrue(requireNotNull(OrezModelLicenseText.verified(resource("Apache-2.0.txt"), record.licenseSha256)).contains("Apache License"))
    }
    @Test fun actualPinnedNativeLicenseKeepsCopyrightAndPermission() {
        val text = requireNotNull(OrezModelLicenseText.verified(resource("llama.cpp-MIT.txt"), OrezModelGovernance.RUNTIME_LICENSE_SHA256))
        assertTrue(text.contains("Copyright")); assertTrue(text.contains("Permission is hereby granted"))
    }
    @Test fun changedLocalBytesCannotDisplayAsVerifiedPublisherLicense() {
        val raw = resource("Apache-2.0.txt").copyOf(); raw[raw.lastIndex] = (raw.last() + 1).toByte()
        assertNull(OrezModelLicenseText.verified(raw, requireNotNull(OrezModelGovernance.find(OrezModelPin(OrezModelCatalog.lite.id, OrezModelCatalog.lite.sha256, OrezModelCatalog.lite.bytes))).licenseSha256))
    }
    @Test fun emptyOrOversizedLicenseIsRejectedBeforeHashingOrDisplay() {
        assertNull(OrezModelLicenseText.verified(byteArrayOf(), "0".repeat(64)))
        assertNull(OrezModelLicenseText.verified(ByteArray(OrezModelLicenseText.MAX_BYTES + 1), "0".repeat(64)))
    }
    @Test fun malformedUtf8IsRejectedEvenWhenItsSuppliedDigestMatches() {
        val bytes = byteArrayOf(0xc3.toByte(), 0x28)
        assertNull(OrezModelLicenseText.verified(bytes, OrezEvaluationCanonical.sha256(bytes)))
    }
}
