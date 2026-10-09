package com.mangalens

import android.os.SystemClock
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/** Keeps the existing action deadline and injects one real gesture on the refreshed control. */
internal fun clickSettledUi(
    device: UiDevice,
    selector: BySelector,
    timeoutMs: Long,
    ready: () -> Boolean = { true },
    beforeClick: (UiActionBounds) -> Unit = {}
): UiObject2 {
    require(timeoutMs > 0)
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    val admission = SettledUiTarget()
    while (SystemClock.uptimeMillis() < deadline) {
        val candidate = try {
            val label = device.findObject(selector)
            val target = label?.let(::clickableAncestor)
            val rect = target?.visibleBounds
            val bounds = rect?.let { UiActionBounds(it.left, it.top, it.right, it.bottom) }
            val expected = label?.isEnabled == true && target?.isEnabled == true && ready()
            Triple(target, bounds, expected)
        } catch (_: StaleObjectException) {
            Triple(null, null, false)
        }
        val now = SystemClock.uptimeMillis()
        if (now < deadline && admission.observe(candidate.second, candidate.third, now)) {
            val target = requireNotNull(candidate.first)
            beforeClick(requireNotNull(candidate.second))
            target.click() // Actual UiAutomator touch; no semantics-action or callback shortcut.
            return target
        }
        SystemClock.sleep(minOf(50L, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
    }
    throw AssertionError("Expected a settled, enabled UI control $selector within $timeoutMs ms")
}

private fun clickableAncestor(label: UiObject2): UiObject2? {
    var current: UiObject2? = label
    repeat(8) {
        val node = current ?: return null
        if (node.isClickable && node.isEnabled && !node.visibleBounds.isEmpty) return node
        current = node.parent
    }
    return null
}
