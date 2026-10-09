package com.mangalens.engine

import kotlin.math.abs

/** Selects a complete, better reading of one original region, not its easiest word. */
internal fun chooseOcrRetryReadingGroup(original: OcrReading, retries: List<OcrReading>): List<Int> =
    chooseOcrRetryReadingGroup(original, retries, emptyList())

internal fun chooseOcrRetryReadingGroup(original: OcrReading, retries: List<OcrReading>, neighbors: List<OcrReading>): List<Int> {
    val eligible = retries.indices.filter { index ->
        val retry = retries[index]
        retry.plausible && retry.bounds.valid && retry.script == original.script &&
            retry.bounds.intersectionArea(original.bounds) > minOf(retry.bounds.area, original.bounds.area) * .55f &&
            retry.bounds.area <= original.bounds.area * 2.5f && neighbors.none { neighbor ->
                neighbor.bounds.valid && neighbor.bounds.intersectionArea(original.bounds) <= 0f &&
                    retry.bounds.intersectionArea(neighbor.bounds) > minOf(retry.bounds.area, neighbor.bounds.area) * .20f
            }
    }
    val groups = eligible.map { listOf(it) }.toMutableList()
    val remaining = eligible.toMutableSet()
    while (remaining.isNotEmpty()) {
        val component = mutableListOf(remaining.first().also(remaining::remove))
        var added: Boolean
        do {
            val neighbors = remaining.filter { next -> component.any { canJoinRetryParts(retries[it], retries[next]) } }
            added = neighbors.isNotEmpty()
            component += neighbors
            remaining.removeAll(neighbors.toSet())
        } while (added)
        if (component.size > 1) groups += component
    }
    return groups.map { indices -> orderOcrReadings(indices.map(retries::get)).map(indices::get) }
        .filter { indices -> isBetterCompleteRetry(original, composeOcrRetryReading(indices.map(retries::get))) }
        .maxByOrNull { indices -> ocrReadingQuality(composeOcrRetryReading(indices.map(retries::get))) }
        ?: emptyList()
}

internal fun composeOcrRetryReading(parts: List<OcrReading>): OcrReading {
    require(parts.isNotEmpty())
    if (parts.size == 1) return parts.single()
    val ordered = orderOcrReadings(parts).map(parts::get)
    val text = buildString {
        ordered.forEachIndexed { index, part ->
            if (index > 0) append(if (sameRetryLine(ordered[index - 1], part)) " " else "\n")
            append(part.source.trim())
        }
    }
    val weights = parts.map { it.source.count(Char::isLetterOrDigit).coerceAtLeast(1) }
    val total = weights.sum().toFloat()
    return parts.first().copy(
        source = OcrSourceQuality.normalizeLatinSource(text),
        bounds = OcrBox(parts.minOf { it.bounds.left }, parts.minOf { it.bounds.top },
            parts.maxOf { it.bounds.right }, parts.maxOf { it.bounds.bottom }),
        confidence = parts.indices.sumOf { (parts[it].confidence * weights[it]).toDouble() }.toFloat() / total,
        textSize = parts.indices.sumOf { (parts[it].textSize * weights[it]).toDouble() }.toFloat() / total,
        lineBounds = ordered.flatMap { it.lineBounds }, plausible = parts.all { it.plausible }
    )
}

private fun isBetterCompleteRetry(original: OcrReading, retry: OcrReading): Boolean {
    val old = original.bounds
    val fresh = retry.bounds
    if (fresh.width < old.width * .70f || fresh.height < old.height * .70f ||
        fresh.intersectionArea(old) < old.area * .55f || fresh.area > old.area * 2.5f) return false
    if (retry.source.count(Char::isLetterOrDigit) < original.source.count(Char::isLetterOrDigit) * .65f) return false
    if (knownNegations.findAll(retry.source).count() < knownNegations.findAll(original.source).count()) return false
    if (OcrSourceQuality.needsPixelRetry(retry.source)) return false
    if (!retainsStableOcrSource(original.source, retry.source)) return false
    // Merely changing case can disguise the same broken annotation. A replacement
    // for that source must recover additional clause evidence from the crop.
    if (OcrSourceQuality.hasMalformedMixedCase(original.source) &&
        OcrSourceQuality.clauseEvidence(retry.source) <= OcrSourceQuality.clauseEvidence(original.source)) return false
    return ocrReadingQuality(retry) > ocrReadingQuality(original) + .035
}

private val knownNegations = Regex("""\b(?:no|not|never|neither|nor|without)\b|\b[a-z]+n['’]t\b""", RegexOption.IGNORE_CASE)

private fun sameRetryLine(a: OcrReading, b: OcrReading): Boolean =
    abs((a.bounds.top + a.bounds.bottom) - (b.bounds.top + b.bounds.bottom)) * .5f <=
        minOf(a.bounds.height, b.bounds.height) * .40f

private fun canJoinRetryParts(a: OcrReading, b: OcrReading): Boolean {
    if (a.script != b.script || isVerticalOcr(a) || isVerticalOcr(b)) return false
    val maxText = maxOf(a.textSize, b.textSize).coerceAtLeast(8f)
    if (minOf(a.textSize, b.textSize) / maxText < .65f) return false
    if (a.bounds.intersectionArea(b.bounds) > minOf(a.bounds.area, b.bounds.area) * .20f) return false
    if (sameRetryLine(a, b)) {
        val gap = maxOf(a.bounds.left, b.bounds.left) - minOf(a.bounds.right, b.bounds.right)
        return gap in -maxText * .20f..maxText * 1.2f
    }
    val upper = if (a.bounds.top <= b.bounds.top) a else b
    val lower = if (upper === a) b else a
    val gap = lower.bounds.top - upper.bounds.bottom
    val overlap = minOf(a.bounds.right, b.bounds.right) - maxOf(a.bounds.left, b.bounds.left)
    return gap in -maxText * .20f..maxText * .95f &&
        overlap >= minOf(a.bounds.width, b.bounds.width) * .55f
}
