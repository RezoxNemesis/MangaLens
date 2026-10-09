package com.mangalens.core.compute

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ResourceGovernorTest {
    @Test fun criticalMemoryDuringSuspendingProofBlocksTheFinalEffectAndRetainsCleanup() = runBlocking {
        val policy = governor()
        val admission = NativeComputeAdmission(governor = policy)
        val proofStarted = CompletableDeferred<Unit>()
        val releaseProof = CompletableDeferred<Unit>()
        var effect = false
        val waiting = async {
            runCatching {
                withContext(NativeComputePrecondition { proofStarted.complete(Unit); releaseProof.await() }) {
                    val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { true }!!
                    try {
                        checkNativeComputePrecondition(lease.waited)
                        policy.requireNativeEntry(ResourceWorkKind.BACKGROUND)
                        effect = true
                    } finally { lease.close() }
                }
            }
        }
        proofStarted.await()
        policy.update(ResourceSignals(memory = MemoryPressure.CRITICAL))
        releaseProof.complete(Unit)
        val outcome = waiting.await()
        assertTrue("A confirmed critical signal after proof must block a new effect", outcome.exceptionOrNull() is ResourcePausedException)
        assertFalse(effect)
        val cleanup = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND, ResourceWorkKind.CLEANUP) { true }!!
        try { policy.requireNativeEntry(ResourceWorkKind.CLEANUP) } finally { cleanup.close() }
        policy.update(ResourceSignals(memory = MemoryPressure.NORMAL))
        now = 100
        val fresh = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { true }!!
        try { policy.requireNativeEntry(ResourceWorkKind.BACKGROUND); effect = true } finally { fresh.close() }
        assertTrue("Recovered foreground and background work must remain usable", effect)
    }

    @Test fun finalEntryDoesNotAddANewDutyCycleWhileHoldingAnAdmittedLease() {
        val policy = governor()
        policy.update(ResourceSignals(memory = MemoryPressure.LOW))
        assertEquals(20L, policy.decision(ResourceWorkKind.BACKGROUND, 0).delayMs)
        now = 20
        assertEquals(0L, policy.decision(ResourceWorkKind.BACKGROUND, 0).delayMs)
        policy.requireNativeEntry(ResourceWorkKind.BACKGROUND)
        policy.update(ResourceSignals(thermal = ThermalPressure.CRITICAL))
        policy.requireNativeEntry(ResourceWorkKind.CLEANUP)
    }

    @Test fun displayedResourceStatusMatchesTheActualAdmissionPressureAndDoesNotInventThermalReports() {
        val policy = governor()
        policy.update(ResourceSignals(memory = MemoryPressure.CRITICAL, batteryPercent = 8, charging = false))
        val critical = policy.snapshot()
        assertEquals(policy.decision(ResourceWorkKind.BACKGROUND).pressure, critical.pressure)
        assertEquals(8, critical.signals.batteryPercent)
        assertNull(critical.signals.thermal)
        assertTrue(critical.reason.orEmpty().contains("memory"))
        policy.update(ResourceSignals(memory = MemoryPressure.NORMAL, charging = true))
        now = 99
        assertEquals(ResourcePressure.CRITICAL, policy.snapshot().pressure)
        now = 100
        assertEquals(ResourcePressure.NORMAL, policy.snapshot().pressure)
        assertNull(policy.snapshot().reason)
    }

    private var now = 0L
    private fun governor() = ResourceGovernor(clockMs = { now }, recoveryMs = 100, backgroundRestMs = 20)

    @Test fun criticalMemoryStopsNewComputationButAlwaysAllowsCleanup() {
        val policy = governor()
        policy.update(ResourceSignals(memory = MemoryPressure.CRITICAL))
        assertEquals(ResourcePressure.CRITICAL, policy.decision(ResourceWorkKind.LIVE).pressure)
        assertEquals(ResourcePressure.NORMAL, policy.decision(ResourceWorkKind.CLEANUP).pressure)
        assertTrue(policy.decision(ResourceWorkKind.BACKGROUND).reason.orEmpty().contains("memory"))
    }

    @Test fun lowMemoryActuallyDefersBackgroundWithoutDelayingInteractiveOrLive() {
        val policy = governor()
        policy.update(ResourceSignals(memory = MemoryPressure.LOW))
        assertEquals(20L, policy.decision(ResourceWorkKind.BACKGROUND, 0).delayMs)
        assertEquals(0L, policy.decision(ResourceWorkKind.INTERACTIVE).delayMs)
        assertEquals(0L, policy.decision(ResourceWorkKind.LIVE).delayMs)
        now = 20
        assertEquals(0L, policy.decision(ResourceWorkKind.BACKGROUND, 0).delayMs)
        policy.completed(ResourceWorkKind.BACKGROUND)
        now = 25
        assertEquals(15L, policy.decision(ResourceWorkKind.BACKGROUND, 0).delayMs)
    }

    @Test fun unknownThermalCannotClearAnObservedCriticalCondition() {
        val policy = governor()
        policy.update(ResourceSignals(thermal = ThermalPressure.CRITICAL))
        assertEquals(ResourcePressure.CRITICAL, policy.decision(ResourceWorkKind.INTERACTIVE).pressure)
        now = 1_000
        policy.update(ResourceSignals(memory = MemoryPressure.NORMAL, charging = true))
        assertEquals(ResourcePressure.CRITICAL, policy.decision(ResourceWorkKind.INTERACTIVE).pressure)
    }

    @Test fun thermalRecoveryRequiresStableKnownRecoveryAndEscalatesImmediately() {
        val policy = governor()
        policy.update(ResourceSignals(thermal = ThermalPressure.CRITICAL))
        assertEquals(ResourcePressure.CRITICAL, policy.decision(ResourceWorkKind.LIVE).pressure)
        policy.update(ResourceSignals(thermal = ThermalPressure.NONE))
        now = 99
        assertEquals(ResourcePressure.CRITICAL, policy.decision(ResourceWorkKind.LIVE).pressure)
        now = 100
        assertEquals(ResourcePressure.NORMAL, policy.decision(ResourceWorkKind.LIVE).pressure)
        policy.update(ResourceSignals(thermal = ThermalPressure.EMERGENCY))
        assertEquals(ResourcePressure.CRITICAL, policy.decision(ResourceWorkKind.LIVE).pressure)
    }

    @Test fun chargingAndBatteryRecoveryUseHysteresisWithoutBlockingForegroundProgress() {
        val policy = governor()
        policy.update(ResourceSignals(batteryPercent = 8, charging = false))
        assertEquals(ResourcePressure.ELEVATED, policy.decision(ResourceWorkKind.BACKGROUND, 0).pressure)
        assertEquals(0L, policy.decision(ResourceWorkKind.LIVE).delayMs)
        policy.update(ResourceSignals(charging = true))
        now = 99
        assertEquals(ResourcePressure.ELEVATED, policy.decision(ResourceWorkKind.BACKGROUND, 0).pressure)
        now = 100
        assertEquals(ResourcePressure.NORMAL, policy.decision(ResourceWorkKind.BACKGROUND, 0).pressure)
    }

    @Test fun anUnknownInitialDeviceDoesNotPretendToHaveCriticalSignals() {
        val policy = governor()
        assertEquals(ResourcePressure.NORMAL, policy.decision(ResourceWorkKind.BACKGROUND).pressure)
        policy.update(ResourceSignals())
        assertEquals(ResourcePressure.NORMAL, policy.decision(ResourceWorkKind.LIVE).pressure)
    }

    @Test fun briefMemoryRecoveryCannotFlapTheGateAndBatteryCannotOverrideMemory() {
        val policy = governor()
        policy.update(ResourceSignals(memory = MemoryPressure.CRITICAL))
        policy.update(ResourceSignals(memory = MemoryPressure.NORMAL, batteryPercent = 100, charging = true))
        now = 99
        assertEquals(ResourcePressure.CRITICAL, policy.decision(ResourceWorkKind.LIVE).pressure)
        policy.update(ResourceSignals(memory = MemoryPressure.CRITICAL))
        policy.update(ResourceSignals(memory = MemoryPressure.NORMAL))
        now = 100
        assertEquals(ResourcePressure.CRITICAL, policy.decision(ResourceWorkKind.LIVE).pressure)
        now = 199
        assertEquals(ResourcePressure.NORMAL, policy.decision(ResourceWorkKind.LIVE).pressure)
    }

    @Test fun severeThermalAndPowerSavingLimitBackgroundButAllowForeground() {
        val policy = governor()
        policy.update(ResourceSignals(thermal = ThermalPressure.SEVERE, powerSave = true))
        assertEquals(ResourcePressure.ELEVATED, policy.decision(ResourceWorkKind.BACKGROUND, 0).pressure)
        assertEquals(20L, policy.decision(ResourceWorkKind.BACKGROUND, 0).delayMs)
        assertEquals(0L, policy.decision(ResourceWorkKind.LIVE).delayMs)
        assertEquals(0L, policy.decision(ResourceWorkKind.INTERACTIVE).delayMs)
    }
}
