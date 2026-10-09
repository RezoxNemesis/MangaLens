package com.mangalens

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/**
 * Compose can expose an input's description on a layout node beside its actual EditText.
 * Resolve the real editable field inside that description's bounds, then verify the value
 * through a fresh accessibility query before the caller performs its next physical action.
 */
internal fun typeIntoEditableUi(
    device: UiDevice,
    description: String,
    value: String,
    timeoutMs: Long = 12_000
): UiObject2 {
    require(timeoutMs > 0)
    val app = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    val settled = SettledUiTarget()
    var entered = false
    while (SystemClock.uptimeMillis() < deadline) {
        try {
            val field = describedEditableUi(device, description, app)
            val bounds = field?.visibleBounds?.let { UiActionBounds(it.left, it.top, it.right, it.bottom) }
            val now = SystemClock.uptimeMillis()
            if (!entered && now < deadline && settled.observe(bounds, field != null, now)) {
                requireNotNull(field).text = value
                entered = true
            }
            if (entered && field != null && field.text == value && SystemClock.uptimeMillis() < deadline) return field
        } catch (_: StaleObjectException) {
            // Re-fetch the described scope and actual input; never type into a stale wrapper.
        }
        SystemClock.sleep(minOf(40L, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
    }
    throw AssertionError("Actual editable field '$description' did not accept the requested text within $timeoutMs ms")
}

/** Resolves the same real input without changing it, so restoration assertions stay meaningful. */
internal fun readEditableUi(device: UiDevice, description: String, timeoutMs: Long = 12_000): UiObject2 {
    require(timeoutMs > 0)
    val app = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    val settled = SettledUiTarget()
    while (SystemClock.uptimeMillis() < deadline) {
        try {
            val field = describedEditableUi(device, description, app)
            val bounds = field?.visibleBounds?.let { UiActionBounds(it.left, it.top, it.right, it.bottom) }
            val now = SystemClock.uptimeMillis()
            if (now < deadline && settled.observe(bounds, field != null, now)) return requireNotNull(field)
        } catch (_: StaleObjectException) {
            // Re-fetch after a layout replacement; reading never changes the field's contents.
        }
        SystemClock.sleep(minOf(40L, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
    }
    throw AssertionError("Actual editable field '$description' was not uniquely visible within $timeoutMs ms")
}

private fun describedEditableUi(device: UiDevice, description: String, app: String): UiObject2? {
    val described = device.findObjects(By.desc(description).pkg(app)).singleOrNull() ?: return null
    val descriptionBounds = described.visibleBounds
    if (descriptionBounds.isEmpty) return null
    return device.findObjects(By.clazz("android.widget.EditText").pkg(app)).filter { field ->
        val bounds = field.visibleBounds
        field.isEnabled && !bounds.isEmpty &&
            (descriptionBounds.contains(bounds) || bounds.contains(descriptionBounds))
    }.singleOrNull()
}
