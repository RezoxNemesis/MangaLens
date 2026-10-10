package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test

/** Metadata compatibility controls; no model, native runtime or empirical evaluation runs here. */
class OrezModelGovernanceTest {
    private fun pin(d: OrezModelDescriptor) = OrezModelPin(d.id, d.sha256, d.bytes)

    @Test fun exactLiteCoreAndHistoricalPinsHaveIndependentPublisherRecords() {
        for (d in listOf(OrezModelCatalog.lite, OrezModelCatalog.core, OrezModelCatalog.legacy)) {
            val record = requireNotNull(OrezModelGovernance.find(pin(d)))
            assertEquals(pin(d), record.pin)
            assertEquals("Apache-2.0", record.licenseId)
            assertTrue(d.url.contains("/${record.publisherRevision}/${record.publisherWeightFileName}?"))
            assertEquals(OrezMinimumApp.NotDeclared, record.packMinimumApp)
        }
    }

    @Test fun changedIdShaOrBytesNeverBorrowPublisherOrEvaluationMetadata() {
        val p = pin(OrezModelCatalog.lite)
        for (changed in listOf(p.copy(modelId = p.modelId + "-other"), p.copy(sha256 = "0".repeat(64)), p.copy(bytes = p.bytes + 1))) {
            assertNull(OrezModelGovernance.find(changed))
            assertEquals("Model evaluation pending", OrezModelGovernance.display(changed).evaluation)
        }
    }

    @Test fun localWeightNamesRemainSeparateFromPublisherWeightNames() {
        val lite = requireNotNull(OrezModelGovernance.find(pin(OrezModelCatalog.lite)))
        val legacy = requireNotNull(OrezModelGovernance.find(pin(OrezModelCatalog.legacy)))
        assertNotEquals(OrezModelCatalog.lite.fileName, lite.publisherWeightFileName)
        assertNotEquals(OrezModelCatalog.legacy.fileName, legacy.publisherWeightFileName)
        assertEquals("qwen2.5-0.5b-instruct-q4_k_m.gguf", lite.publisherWeightFileName)
        assertEquals("qwen2.5-0.5b-instruct-q6_k.gguf", legacy.publisherWeightFileName)
    }

    @Test fun governanceDoesNotOfferMaxOrChangeCatalogOrPinIdentity() {
        assertEquals(listOf(OrezModelTier.LITE, OrezModelTier.CORE), OrezModelCatalog.availableDescriptors.map { it.tier })
        assertNull(OrezModelCatalog.descriptor(OrezModelTier.MAX))
        assertEquals(1, OrezModelCatalog.MANIFEST_VERSION)
        val p = pin(OrezModelCatalog.lite)
        assertEquals(p, OrezModelPin(p.modelId, p.sha256, p.bytes))
        assertEquals("Resource profile: estimate only", OrezModelGovernance.display(p).resources)
    }

    @Test fun metadataDisplayDoesNotClaimMeasuredQualityOrMinimumAppHistory() {
        val p = pin(OrezModelCatalog.core)
        val display = OrezModelGovernance.display(p)
        assertTrue(display.publisher.contains("a615a8136231"))
        assertTrue(display.license.contains("Apache-2.0"))
        assertTrue(display.compatibility.contains("not declared"))
        assertEquals("Model evaluation pending", display.evaluation)
        assertEquals("Resource profile: estimate only", display.resources)
    }
    @Test fun archivedPublisherBodiesMatchTheirOwnCollectedReceipts() {
        for ((descriptor, name) in listOf(OrezModelCatalog.lite to "lite", OrezModelCatalog.core to "core", OrezModelCatalog.legacy to "legacy")) {
            val record = requireNotNull(OrezModelGovernance.find(pin(descriptor)))
            val bytes = requireNotNull(javaClass.getResourceAsStream("/orez-governance/$name-publisher.json")).use { it.readBytes() }
            assertEquals(record.publisherMetadataSha256, OrezEvaluationCanonical.sha256(bytes))
            assertEquals("orez/model-governance/$name-publisher.json", record.publisherMetadataAsset)
        }
    }
}
