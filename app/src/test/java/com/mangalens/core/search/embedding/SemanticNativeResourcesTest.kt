package com.mangalens.core.search.embedding

import org.junit.Assert.*
import org.junit.Test

/** Control real close ordering/retention without treating fake resources as JNI execution evidence. */
class SemanticNativeResourcesTest {
    @Test fun actualCreationOrderClosesResultBeforeInputTensors() {
        val order = arrayListOf<Int>()
        SemanticNativeResources.closeReverse((1..4).map { id -> AutoCloseable { order += id } }, Any())
        assertEquals(listOf(4, 3, 2, 1), order)
    }
    @Test fun failedResultCloseRetainsAllStillOwnedHandles() {
        val owner = Any(); val order = arrayListOf<Int>()
        val resources = (1..4).map { id -> AutoCloseable { order += id; if (id == 4) error("held close failed") } }
        val failure = assertThrows(SemanticNativeCloseUnproven::class.java) { SemanticNativeResources.closeReverse(resources, owner) }
        assertEquals(listOf(4), order); assertSame(owner, failure.retained[0]); assertEquals(resources, failure.retained.drop(1))
    }
    @Test fun failureAfterSomeRealClosesKeepsOnlyUnprovenResourcesAndSessionOwner() {
        val owner = Any(); val order = arrayListOf<Int>()
        val resources = (1..4).map { id -> AutoCloseable { order += id; if (id == 2) error("unproven tensor") } }
        val failure = assertThrows(SemanticNativeCloseUnproven::class.java) { SemanticNativeResources.closeReverse(resources, owner) }
        assertEquals(listOf(4, 3, 2), order); assertEquals(listOf(owner) + resources.take(2), failure.retained)
    }
    @Test fun closeFailureIsRestartStateAndPreservesItsExactCause() {
        val cause = IllegalStateException("actual release failed")
        val failure = assertThrows(SemanticNativeCloseUnproven::class.java) { SemanticNativeResources.closeReverse(listOf(AutoCloseable { throw cause }), Any()) }
        assertSame(cause, failure.cause); assertTrue(failure.message.orEmpty().contains("Restart"))
    }
}
