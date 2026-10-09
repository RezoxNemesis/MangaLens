package com.mangalens

import java.util.Collections
import java.util.IdentityHashMap
import java.util.ArrayDeque

internal data class ProviderFailureFrame(val owner: String, val method: String, val line: Int?)
internal data class ProviderFailureNode(val type: String, val relation: String, val parent: Int?, val frames: List<ProviderFailureFrame>)

/** Static app/framework locations only: no messages, native stdout/stderr or request-derived values. */
internal object ProviderFailureDiagnostics {
    private val className = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
    private val methodName = Regex("[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>")
    private val approved = listOf("com.mangalens.", "android.", "androidx.", "java.", "javax.",
        "kotlin.", "kotlinx.", "com.yausername.", "org.junit.", "dalvik.", "libcore.", "okhttp3.", "okio.")
    private data class Pending(val failure: Throwable, val relation: String, val parent: Int?)

    fun capture(failure: Throwable): List<ProviderFailureNode> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val waiting = ArrayDeque<Pending>().apply { add(Pending(failure, "root", null)) }
        val nodes = ArrayList<ProviderFailureNode>()
        var frameBudget = 96
        while (waiting.isNotEmpty() && nodes.size < 16) {
            val next = waiting.removeLast()
            if (!seen.add(next.failure)) continue
            val frames = next.failure.stackTrace.take(64).mapNotNull { frame ->
                if (!safeClass(frame.className) || frame.methodName.length > 96 ||
                    !methodName.matches(frame.methodName)) null
                else ProviderFailureFrame(frame.className, frame.methodName,
                    frame.lineNumber.takeIf { it in 1..1_000_000 })
            }.take(minOf(8, frameBudget))
            frameBudget -= frames.size
            val index = nodes.size
            val type = next.failure.javaClass.name.takeIf(::safeClass) ?: "other.Throwable"
            nodes += ProviderFailureNode(type, next.relation, next.parent, frames)
            next.failure.suppressed.take(15).asReversed().forEach {
                if (waiting.size < 32) waiting.add(Pending(it, "suppressed", index))
            }
            next.failure.cause?.let { waiting.add(Pending(it, "cause", index)) }
        }
        return nodes
    }

    private fun safeClass(value: String): Boolean = value.length <= 192 && className.matches(value) &&
        approved.any(value::startsWith)
}
