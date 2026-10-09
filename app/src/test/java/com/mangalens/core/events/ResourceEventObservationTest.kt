package com.mangalens.core.events

import org.junit.Assert.*
import org.junit.Test

class ResourceEventObservationTest {
    @Test fun aVerifiedReactivationNotifiesAnExistingExactModelObserverAgain() {
        val pin = "d".repeat(64)
        AppEvents.bus.subscribe(ModelEventFilter(ModelEventIdentity.sha256(pin))).use { subscription ->
            AppEvents.modelReady(pin)
            val first = subscription.poll()
            assertNotNull(first)
            AppEvents.modelReady(pin)
            val second = subscription.poll()
            assertNotNull("A later verified activation is a distinct observation", second)
            assertNotEquals(first, second)
        }
    }

    @Test fun manyDistinctPressureObservationsStayInsideTheEightHintQueue() {
        AppEvents.bus.subscribe(MemoryEventFilter).use { subscription ->
            repeat(100) { AppEvents.memoryPressure(MemoryPressureLevel.CRITICAL) }
            val observed = generateSequence { subscription.poll() }.toList()
            assertEquals(8, observed.size)
            assertEquals(8, observed.distinct().size)
        }
    }
}
