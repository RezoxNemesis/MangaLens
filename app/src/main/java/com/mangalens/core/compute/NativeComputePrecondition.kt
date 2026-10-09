package com.mangalens.core.compute

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Captured feature checks, never authority supplied by the admission scheduler. */
internal class NativeComputePrecondition(val validate: suspend (waited: Boolean) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeComputePrecondition>
}

internal suspend fun checkNativeComputePrecondition(waited: Boolean) {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    caller[NativeComputePrecondition]?.validate?.invoke(waited)
}
