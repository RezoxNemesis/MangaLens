package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test

class OrezLoadedModelPolicyTest {
    private val lite = OrezModelPin(OrezModelCatalog.lite.id, OrezModelCatalog.lite.sha256, OrezModelCatalog.lite.bytes)
    private val core = OrezModelPin(OrezModelCatalog.core.id, OrezModelCatalog.core.sha256, OrezModelCatalog.core.bytes)
    private val paths = listOf("/models/lite" to lite, "/models/core" to core)

    @Test fun installedAndSelectedModelsCannotMakeIdleRuntimeAppearLoaded() {
        val idle = OrezLoadedModelPolicy.observe(null, paths)
        assertEquals(OrezLoadedModelKind.IDLE, idle.kind); assertNull(idle.pin)
    }
    @Test fun sharedPeerCoreLeaseIsReportedEvenWhenCallerSelectsLite() {
        assertEquals(core, OrezLoadedModelPolicy.observe("/models/core", paths).pin)
    }
    @Test fun runtimePathOutsideVerifiedPoolIsExplicitlyUnmapped() {
        val unknown = OrezLoadedModelPolicy.observe("/models/private-unknown", paths)
        assertEquals(OrezLoadedModelKind.UNMAPPED, unknown.kind); assertNull(unknown.pin)
    }
    @Test fun invalidHashCannotTurnPhysicalPathIntoVerifiedIdentity() {
        assertEquals(OrezLoadedModelKind.UNMAPPED,
            OrezLoadedModelPolicy.observe("/models/core", listOf("/models/core" to core.copy(sha256 = "bad"))).kind)
    }
    @Test fun conflictingPinsForOnePathFailClosedInsteadOfChoosingInstalledOrder() {
        assertEquals(OrezLoadedModelKind.UNMAPPED,
            OrezLoadedModelPolicy.observe("/models/core", listOf("/models/core" to core, "/models/core" to lite)).kind)
    }
    @Test fun actualUnloadOrTrimRetiresPreviouslyMappedLoadedObservation() {
        val loaded = OrezLoadedModelPolicy.observe("/models/core", paths)
        assertFalse(OrezLoadedModelPolicy.stillCurrent(loaded, null))
        assertEquals(OrezLoadedModelKind.IDLE, OrezLoadedModelPolicy.observe(null, paths).kind)
    }
    @Test fun modelSwitchRetiresOldObservationBeforeUiPublication() {
        val before = OrezLoadedModelPolicy.observe("/models/core", paths)
        assertFalse(OrezLoadedModelPolicy.stillCurrent(before, "/models/lite"))
        assertEquals(lite, OrezLoadedModelPolicy.observe("/models/lite", paths).pin)
    }
    @Test fun peerOwnerContinuingSamePhysicalLeaseKeepsObservationValid() {
        assertTrue(OrezLoadedModelPolicy.stillCurrent(OrezLoadedModelPolicy.observe("/models/core", paths), "/models/core"))
    }
}
