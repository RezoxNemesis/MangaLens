package com.mangalens

import org.json.JSONObject

/** A fixture heading counts only when the exact document and complete visible heading are ready. */
internal fun renderedWebFixtureHeading(snapshot: JSONObject, expectedUrl: String, expectedHeading: String): Boolean {
    if (snapshot.optString("url") != expectedUrl || snapshot.optString("readyState") != "complete" ||
        snapshot.optString("heading") != expectedHeading) return false
    val style = snapshot.optJSONObject("headingStyle") ?: return false
    if (style.optString("display").isBlank() || style.optString("display") == "none" || style.optString("visibility") != "visible" ||
        style.optString("opacity").toDoubleOrNull()?.let { it.isFinite() && it > 0 } != true) return false
    val bounds = snapshot.optJSONObject("headingBounds") ?: return false
    val viewport = snapshot.optJSONObject("viewport") ?: return false
    val left = bounds.optDouble("left"); val top = bounds.optDouble("top")
    val width = bounds.optDouble("width"); val height = bounds.optDouble("height")
    val viewportWidth = viewport.optDouble("width"); val viewportHeight = viewport.optDouble("height")
    return listOf(left, top, width, height, viewportWidth, viewportHeight).all(Double::isFinite) &&
        left >= 0 && top >= 0 && width > 0 && height > 0 &&
        left + width <= viewportWidth && top + height <= viewportHeight
}
