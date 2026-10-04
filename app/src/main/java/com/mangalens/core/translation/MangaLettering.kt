package com.mangalens.core.translation

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Local manga lettering reconstruction.
 *
 * The renderer deliberately keeps work bounded and on-device:
 * 1. infer the nearby paper / balloon surface,
 * 2. expand the writable area only while that surface remains visually compatible,
 * 3. remove glyph-shaped pixels instead of painting a rectangular slab, and
 * 4. fit the translated lettering back into the recovered surface.
 */
object MangaLettering {
    data class Style(
        val family: String = "sans-serif",
        val face: Int = Typeface.NORMAL,
        val color: Int = Color.BLACK,
        val size: Float = 24f,
        val alignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER
    )

    data class Patch(val bounds: Rect, val background: Bitmap, val style: Style)

    fun prepare(
        image: Bitmap,
        bounds: RectF,
        lines: List<RectF>,
        source: String,
        preserveStyle: Boolean = true
    ): Patch {
        val sourceLines = lines.ifEmpty { listOf(bounds) }
        val lineHeight = sourceLines.map { it.height() }.filter { it > 0f }.average()
            .takeIf { it.isFinite() }?.toFloat() ?: bounds.height().coerceAtLeast(8f)
        val padding = max(2, (lineHeight * .14f).toInt())
        val reference = borderColor(image, bounds, padding)
        val rect = expandWritableSurface(image, bounds, reference, padding, lineHeight)

        require(rect.width() > 0 && rect.height() > 0)
        val patch = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        Canvas(patch).drawBitmap(image, rect, Rect(0, 0, rect.width(), rect.height()), null)

        val first = sourceLines.firstOrNull() ?: bounds
        val background = borderColor(image, first, padding)
        val ink = inkColor(image, first, background)
        val alignment = inferAlignment(sourceLines, bounds)
        val style = if (preserveStyle) {
            inferStyle(
                image,
                first,
                source.lineSequence().firstOrNull().orEmpty(),
                ink,
                background,
                alignment
            )
        } else {
            Style(color = ink, size = first.height().coerceAtLeast(12f), alignment = alignment)
        }

        eraseSourceGlyphs(
            source = image,
            destination = patch,
            destinationBounds = rect,
            lines = sourceLines,
            padding = padding
        )
        return Patch(rect, patch, style)
    }

    /**
     * Give translated text the balloon/card's real breathing room instead of forcing it
     * into the tight OCR glyph box. Expansion stops when the surrounding surface changes,
     * so panel artwork is not swallowed by a giant replacement rectangle.
     */
    private fun expandWritableSurface(
        image: Bitmap,
        bounds: RectF,
        reference: Int,
        padding: Int,
        lineHeight: Float
    ): Rect {
        var left = (bounds.left.toInt() - padding).coerceAtLeast(0)
        var top = (bounds.top.toInt() - padding).coerceAtLeast(0)
        var right = (bounds.right.toInt() + padding + 1).coerceAtMost(image.width)
        var bottom = (bounds.bottom.toInt() + padding + 1).coerceAtMost(image.height)

        val maxHorizontal = min(
            (bounds.width() * .65f).toInt().coerceAtLeast(padding * 2),
            (image.width * .20f).toInt().coerceAtLeast(padding * 2)
        )
        val maxVertical = min(
            max((bounds.height() * 1.10f).toInt(), (lineHeight * 1.35f).toInt()),
            (image.height * .16f).toInt().coerceAtLeast(padding * 2)
        )
        val step = max(2, min(8, (lineHeight * .16f).toInt().coerceAtLeast(2)))

        val minLeft = (left - maxHorizontal).coerceAtLeast(0)
        val maxRight = (right + maxHorizontal).coerceAtMost(image.width)
        val minTop = (top - maxVertical).coerceAtLeast(0)
        val maxBottom = (bottom + maxVertical).coerceAtMost(image.height)

        var changed = true
        while (changed) {
            changed = false
            if (left > minLeft) {
                val candidate = max(minLeft, left - step)
                if (stripMatches(image, candidate, top, left, bottom, reference)) {
                    left = candidate
                    changed = true
                }
            }
            if (right < maxRight) {
                val candidate = min(maxRight, right + step)
                if (stripMatches(image, right, top, candidate, bottom, reference)) {
                    right = candidate
                    changed = true
                }
            }
            if (top > minTop) {
                val candidate = max(minTop, top - step)
                if (stripMatches(image, left, candidate, right, top, reference)) {
                    top = candidate
                    changed = true
                }
            }
            if (bottom < maxBottom) {
                val candidate = min(maxBottom, bottom + step)
                if (stripMatches(image, left, bottom, right, candidate, reference)) {
                    bottom = candidate
                    changed = true
                }
            }
        }
        return Rect(left, top, right, bottom)
    }

    private fun stripMatches(
        image: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        reference: Int
    ): Boolean {
        if (right <= left || bottom <= top) return false
        val stepX = max(1, (right - left) / 24)
        val stepY = max(1, (bottom - top) / 24)
        var samples = 0
        var compatible = 0
        var distanceSum = 0L
        for (y in top until bottom step stepY) {
            for (x in left until right step stepX) {
                val d = distance(image.getPixel(x, y), reference)
                samples++
                distanceSum += d
                if (d <= 96) compatible++
            }
        }
        if (samples == 0) return false
        return compatible >= samples * .66f && distanceSum.toFloat() / samples <= 78f
    }

    /**
     * Build a glyph mask from local background contrast, dilate it to catch antialiasing,
     * then reconstruct only those pixels from the nearest clean horizontal/vertical surface.
     * This is intentionally more conservative than filling whole OCR rectangles.
     */
    private fun eraseSourceGlyphs(
        source: Bitmap,
        destination: Bitmap,
        destinationBounds: Rect,
        lines: List<RectF>,
        padding: Int
    ) {
        val width = destination.width
        val height = destination.height
        val original = IntArray(width * height)
        destination.getPixels(original, 0, width, 0, 0, width, height)
        val masked = BooleanArray(original.size)

        lines.forEach { line ->
            val localBackground = borderColor(source, line, padding)
            val localInk = inkColor(source, line, localBackground)
            val contrast = distance(localInk, localBackground)
            val threshold = (contrast * .30f).toInt().coerceIn(42, 132)
            val expand = max(1, (line.height() * .09f).toInt())
            val l = (line.left.toInt() - expand).coerceIn(destinationBounds.left, destinationBounds.right - 1)
            val t = (line.top.toInt() - expand).coerceIn(destinationBounds.top, destinationBounds.bottom - 1)
            val r = (line.right.toInt() + expand + 1).coerceIn(l + 1, destinationBounds.right)
            val b = (line.bottom.toInt() + expand + 1).coerceIn(t + 1, destinationBounds.bottom)

            for (gy in t until b) {
                val py = gy - destinationBounds.top
                for (gx in l until r) {
                    val px = gx - destinationBounds.left
                    val pixel = source.getPixel(gx, gy)
                    if (distance(pixel, localBackground) >= threshold) {
                        masked[py * width + px] = true
                    }
                }
            }
        }

        val dilation = max(1, min(3, padding / 2))
        repeat(dilation) {
            val grown = masked.clone()
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val index = y * width + x
                    if (!masked[index]) continue
                    grown[index - 1] = true
                    grown[index + 1] = true
                    grown[index - width] = true
                    grown[index + width] = true
                }
            }
            for (i in masked.indices) masked[i] = grown[i]
        }

        val result = original.clone()
        val fallback = borderColor(
            source,
            RectF(
                destinationBounds.left.toFloat(),
                destinationBounds.top.toFloat(),
                destinationBounds.right.toFloat(),
                destinationBounds.bottom.toFloat()
            ),
            max(1, padding)
        )
        val search = max(10, min(48, padding * 8))

        fun cleanIndex(x: Int, y: Int): Int? {
            if (x !in 0 until width || y !in 0 until height) return null
            val index = y * width + x
            return index.takeUnless { masked[it] }
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!masked[index]) continue

                var left: Int? = null
                var right: Int? = null
                var up: Int? = null
                var down: Int? = null
                for (d in 1..search) {
                    if (left == null) left = cleanIndex(x - d, y)
                    if (right == null) right = cleanIndex(x + d, y)
                    if (up == null) up = cleanIndex(x, y - d)
                    if (down == null) down = cleanIndex(x, y + d)
                    if (left != null && right != null && up != null && down != null) break
                }

                val horizontal = if (left != null && right != null) {
                    mix(original[left], original[right], .5f)
                } else left?.let { original[it] } ?: right?.let { original[it] }
                val vertical = if (up != null && down != null) {
                    mix(original[up], original[down], .5f)
                } else up?.let { original[it] } ?: down?.let { original[it] }

                result[index] = when {
                    horizontal != null && vertical != null ->
                        if (distance(horizontal, vertical) <= 90) mix(horizontal, vertical, .5f)
                        else if (distance(horizontal, fallback) <= distance(vertical, fallback)) horizontal else vertical
                    horizontal != null -> horizontal
                    vertical != null -> vertical
                    else -> fallback
                }
            }
        }

        // Soften only reconstructed pixels. Clean art and panel borders stay byte-for-byte untouched.
        val smoothed = result.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (!masked[index]) continue
                val neighbours = intArrayOf(
                    result[index], result[index - 1], result[index + 1],
                    result[index - width], result[index + width]
                )
                smoothed[index] = Color.rgb(
                    neighbours.sumOf { Color.red(it) } / neighbours.size,
                    neighbours.sumOf { Color.green(it) } / neighbours.size,
                    neighbours.sumOf { Color.blue(it) } / neighbours.size
                )
            }
        }
        destination.setPixels(smoothed, 0, width, 0, 0, width, height)
    }

    private fun mix(a: Int, b: Int, fraction: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * fraction).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * fraction).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * fraction).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * fraction).toInt()
    )

    private fun borderColor(image: Bitmap, box: RectF, pad: Int): Int {
        val colors = mutableListOf<Int>()
        val l = (box.left.toInt() - pad).coerceIn(0, image.width - 1)
        val r = (box.right.toInt() + pad).coerceIn(l, image.width - 1)
        val t = (box.top.toInt() - pad).coerceIn(0, image.height - 1)
        val b = (box.bottom.toInt() + pad).coerceIn(t, image.height - 1)
        for (x in l..r step max(1, (r - l) / 80)) {
            colors += image.getPixel(x, t)
            colors += image.getPixel(x, b)
        }
        for (y in t..b step max(1, (b - t) / 30)) {
            colors += image.getPixel(l, y)
            colors += image.getPixel(r, y)
        }
        if (colors.isEmpty()) return image.getPixel(l, t)
        fun median(channel: (Int) -> Int) = colors.map(channel).sorted()[colors.size / 2]
        return Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
    }

    private fun distance(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))

    private fun inkColor(image: Bitmap, box: RectF, background: Int): Int {
        val colors = mutableListOf<Int>()
        val top = box.top.toInt().coerceAtLeast(0)
        val bottom = box.bottom.toInt().coerceAtMost(image.height)
        val left = box.left.toInt().coerceAtLeast(0)
        val right = box.right.toInt().coerceAtMost(image.width)
        for (y in top until bottom step 2) {
            for (x in left until right step 2) {
                val color = image.getPixel(x, y)
                if (distance(color, background) > 150) colors += color
            }
        }
        if (colors.isEmpty()) {
            return if (Color.red(background) + Color.green(background) + Color.blue(background) > 384) Color.BLACK else Color.WHITE
        }
        val strongest = colors.sortedByDescending { distance(it, background) }.take(max(1, colors.size / 3))
        return Color.rgb(
            strongest.map(Color::red).average().toInt(),
            strongest.map(Color::green).average().toInt(),
            strongest.map(Color::blue).average().toInt()
        )
    }

    private fun inferAlignment(lines: List<RectF>, bounds: RectF): Layout.Alignment {
        if (lines.size <= 1 || bounds.width() <= 0f) return Layout.Alignment.ALIGN_CENTER
        val centreError = lines.map { abs(it.centerX() - bounds.centerX()) }.average().toFloat() / bounds.width()
        if (centreError <= .08f) return Layout.Alignment.ALIGN_CENTER

        val leftGap = lines.map { it.left - bounds.left }.average().toFloat()
        val rightGap = lines.map { bounds.right - it.right }.average().toFloat()
        return when {
            leftGap + bounds.width() * .06f < rightGap -> Layout.Alignment.ALIGN_NORMAL
            rightGap + bounds.width() * .06f < leftGap -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
    }

    /** Compare normalized source glyph silhouettes against available Android font families/styles. */
    private fun inferStyle(
        image: Bitmap,
        box: RectF,
        source: String,
        ink: Int,
        background: Int,
        alignment: Layout.Alignment
    ): Style {
        if (source.isBlank()) return Style(color = ink, size = box.height(), alignment = alignment)
        val w = 160
        val h = 48
        val actual = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(actual).drawBitmap(
            image,
            Rect(
                box.left.toInt().coerceAtLeast(0),
                box.top.toInt().coerceAtLeast(0),
                box.right.toInt().coerceAtMost(image.width),
                box.bottom.toInt().coerceAtMost(image.height)
            ),
            Rect(0, 0, w, h),
            null
        )
        var best = Style(color = ink, size = box.height() * .9f, alignment = alignment)
        var bestScore = Float.MAX_VALUE
        val trial = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            for (family in listOf("sans-serif", "sans-serif-condensed", "serif", "cursive")) {
                for (face in listOf(Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC)) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ink
                        textSize = 40f
                        typeface = Typeface.create(family, face)
                    }
                    val glyph = Rect()
                    paint.getTextBounds(source, 0, source.length, glyph)
                    if (glyph.width() <= 0 || glyph.height() <= 0) continue
                    val c = Canvas(trial)
                    c.drawColor(background)
                    c.save()
                    c.scale(w.toFloat() / glyph.width(), h.toFloat() / glyph.height())
                    c.drawText(source, -glyph.left.toFloat(), -glyph.top.toFloat(), paint)
                    c.restore()
                    var mismatch = 0
                    var union = 0
                    for (y in 0 until h) {
                        for (x in 0 until w) {
                            val a = distance(actual.getPixel(x, y), background) > 100
                            val b = distance(trial.getPixel(x, y), background) > 100
                            if (a || b) union++
                            if (a != b) mismatch++
                        }
                    }
                    val sourceAspect = (box.width() / box.height()).coerceAtLeast(.05f)
                    val glyphAspect = (glyph.width().toFloat() / glyph.height()).coerceAtLeast(.05f)
                    val aspectPenalty = abs(kotlin.math.ln(glyphAspect / sourceAspect))
                    val score = mismatch.toFloat() / max(1, union) + aspectPenalty * .5f
                    if (score < bestScore) {
                        bestScore = score
                        best = Style(
                            family,
                            face,
                            ink,
                            (box.height() * 40f / glyph.height()).coerceAtLeast(8f),
                            alignment
                        )
                    }
                }
            }
        } finally {
            actual.recycle()
            trial.recycle()
        }
        return best
    }

    fun layout(
        text: String,
        style: Style,
        width: Int,
        height: Int,
        textScale: Float = 1f
    ): StaticLayout {
        val contentWidth = max(1, (width * .95f).toInt())
        val contentHeight = max(1, (height * .95f).toInt())
        val family = when {
            text.any { it in '\u0900'..'\u097f' } && style.family == "cursive" -> "sans-serif"
            else -> style.family
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.color
            typeface = Typeface.create(family, style.face)
        }

        fun build(size: Float): StaticLayout {
            paint.textSize = size
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(style.alignment)
                .setIncludePad(true)
                .setLineSpacing(0f, 1.02f)
                .build()
        }

        var lo = 1f
        var hi = max(1f, style.size * textScale)
        repeat(15) {
            val mid = (lo + hi) / 2f
            val candidate = build(mid)
            val fits = candidate.height <= contentHeight &&
                (0 until candidate.lineCount).all { candidate.getLineWidth(it) <= contentWidth + .5f }
            if (fits) lo = mid else hi = mid
        }
        return build(lo)
    }

    fun draw(
        canvas: Canvas,
        patch: Patch,
        text: String,
        textScale: Float = 1f,
        cachedLayout: StaticLayout? = null
    ) {
        canvas.drawBitmap(patch.background, patch.bounds.left.toFloat(), patch.bounds.top.toFloat(), null)
        val layout = cachedLayout ?: layout(
            text,
            patch.style,
            patch.bounds.width(),
            patch.bounds.height(),
            textScale
        )
        val x = patch.bounds.left + (patch.bounds.width() - layout.width) / 2f
        val y = patch.bounds.top + (patch.bounds.height() - layout.height) / 2f
        canvas.save()
        canvas.clipRect(patch.bounds)
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }
}
