package com.mangalens.orez

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrezModelCatalogTest {
    @Test
    fun coreModelIsExternalAndLargerThanLite() {
        assertTrue(OrezModelCatalog.core.bytes > OrezModelCatalog.lite.bytes)
        assertTrue(OrezModelCatalog.core.bytes > 1_000_000_000L)
        assertEquals(64, OrezModelCatalog.core.sha256.length)
        assertTrue(OrezModelCatalog.core.url.startsWith("https://"))
        assertNotNull(OrezModelCatalog.descriptor(OrezModelTier.CORE))
    }

    @Test
    fun sixGbDevicesRecommendCoreWhileSmallerDevicesStayLite() {
        assertEquals(
            OrezModelTier.CORE,
            OrezModelCatalog.recommended(6L * 1024L * 1024L * 1024L)
        )
        assertEquals(
            OrezModelTier.LITE,
            OrezModelCatalog.recommended(4L * 1024L * 1024L * 1024L)
        )
    }

    @Test
    fun maxTierIsReservedUntilAProductionModelPassesEvaluation() {
        assertEquals(null, OrezModelCatalog.descriptor(OrezModelTier.MAX))
    }
}
