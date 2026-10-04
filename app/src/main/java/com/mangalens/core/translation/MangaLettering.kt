package com.mangalens.core.translation

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Local lettering reconstruction.
 *
 * The source pixels are copied into a small patch, the detected source lettering is removed,
 * and translated text is drawn back into that same patch. Uniform speech/caption backgrounds
 * are erased as one coherent region so the reader never gets the striped grey rectangles that
 * appear when each OCR line is painted with a slightly different sampled colour.
 */
object MangaLettering {
    data class Style(
        val family: String = "sans-serif",
        val face: Int = Typeface.NORMAL,
        val color: Int = Color.BLACK,
        val size: Float = 24f
    )
    data class Patch(val bounds: Rect, val background: Bitmap, val style: Style)

    private data class BorderSample(val color: Int, val spread: Int)

    fun prepare(
        image: Bitmap,
        bounds: RectF,
        lines: List<RectF>,
        source: String,
        preserveStyle: Boolean = true
    ): Patch {
        val validLines = lines.filter { it.width() > 0f && it.height() > 0f }
        val typicalLineHeight = validLines.map { it.height() }.sorted()
            .let { if (it.isEmpty()) bounds.height().coerceAtLeast(12f) else it[it.size / 2] }
        // OCR boxes are intentionally tight. Extra room removes italic overhang, antialiasing
        // and cursive ascenders/descenders that otherwise remain visible behind the translation.
        val erasePadding = max(4, (typicalLineHeight * .24f).toInt())
        val rect = Rect(
            bounds.left.toInt() - erasePadding,
            bounds.top.toInt() - erasePadding,
            bounds.right.toInt() + erasePadding + 1,
            bounds.bottom.toInt() + erasePadding + 1
        )
        rect.intersect(0, 0, image.width, image.height)
        require(rect.width() > 0 && rect.height() > 0)

        val patch = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        Canvas(patch).drawBitmap(image, rect, Rect(0, 0, rect.width(), rect.height()), null)

        val first = validLines.firstOrNull() ?: bounds
        val firstBackground = borderSample(image, first, erasePadding)
        val ink = inkColor(image, first, firstBackground.color)
        val style = if (preserveStyle) {
            inferStyle(
                image,
                first,
                source.lineSequence().firstOrNull().orEmpty(),
                ink,
                firstBackground.color
            )
        } else {
            Style(color = ink, size = first.height().coerceAtLeast(12f))
        }

        val canvas = Canvas(patch)
        val wholeBackground = borderSample(image, bounds, erasePadding)
        if (wholeBackground.spread <= 44) {
            // Speech balloons and caption cards are usually locally flat. Erasing the complete
            // OCR block gives one clean paper surface and removes source glyphs between line boxes.
            val area = expanded(bounds, erasePadding, image.width, image.height)
            canvas.drawRect(
                (area.left - rect.left).toFloat(),
                (area.top - rect.top).toFloat(),
                (area.right - rect.left).toFloat(),
                (area.bottom - rect.top).toFloat(),
                Paint().apply { color = wholeBackground.color }
            )
        } else {
            // Text over artwork is riskier. Keep the operation local to each OCR line rather than
            // flattening the entire block.
            (validLines.ifEmpty { listOf(bounds) }).forEach { line ->
                val area = expanded(line, erasePadding, image.width, image.height)
                val local = borderSample(image, line, erasePadding)
                canvas.drawRect(
                    (area.left - rect.left).toFloat(),
                    (area.top - rect.top).toFloat(),
                    (area.right - rect.left).toFloat(),
                    (area.bottom - rect.top).toFloat(),
                    Paint().apply { color = local.color }
                )
            }
        }
        return Patch(rect, patch, style)
    }

    private fun expanded(box: RectF, pad: Int, width: Int, height: Int): Rect =
        Rect(
            (box.left.toInt() - pad).coerceIn(0, width - 1),
            (box.top.toInt() - pad).coerceIn(0, height - 1),
            (box.right.toInt() + pad + 1).coerceIn(1, width),
            (box.bottom.toInt() + pad + 1).coerceIn(1, height)
        )

    private fun borderSample(image: Bitmap, box: RectF, pad: Int): BorderSample {
        val colors = mutableListOf<Int>()
        val l = (box.left.toInt() - pad).coerceIn(0, image.width - 1)
        val r = (box.right.toInt() + pad).coerceIn(l, image.width - 1)
        val t = (box.top.toInt() - pad).coerceIn(0, image.height - 1)
        val b = (box.bottom.toInt() + pad).coerceIn(t, image.height - 1)
        val stepX = max(1, (r - l) / 96)
        val stepY = max(1, (b - t) / 48)
        for (x in l..r step stepX) {
            colors += image.getPixel(x, t)
            if (b != t) colors += image.getPixel(x, b)
        }
        for (y in t..b step stepY) {
            colors += image.getPixel(l, y)
            if (r != l) colors += image.getPixel(r, y)
        }
        if (colors.isEmpty()) return BorderSample(Color.WHITE, 0)

        fun median(channel: (Int) -> Int): Int {
            val values = colors.map(channel).sorted()
            return values[values.size / 2]
        }
        val color = Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
        val distances = colors.map { distance(it, color) }.sorted()
        // 75th percentile ignores a few source-glyph pixels touching a tight OCR border.
        val spread = distances[((distances.lastIndex * 3) / 4).coerceAtLeast(0)]
        return BorderSample(color, spread)
    }

    private fun distance(a: Int, b: Int): Int =
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
                if (distance(color, background) > 165) colors += color
            }
        }
        if (colors.isEmpty()) {
            return if (Color.red(background) + Color.green(background) + Color.blue(background) > 384) {
                Color.BLACK
            } else {
                Color.WHITE
            }
        }
        val strongest = colors.sortedByDescending { distance(it, background) }
            .take(max(1, colors.size / 3))
        return Color.rgb(
            strongest.map(Color::red).average().toInt(),
            strongest.map(Color::green).average().toInt(),
            strongest.map(Color::blue).average().toInt()
        )
    }

    /** Compare normalized source glyph silhouettes against available Android font families/styles. */
    private fun inferStyle(
        image: Bitmap,
        box: RectF,
        source: String,
        ink: Int,
        background: Int
    ): Style {
        if (source.isBlank()) return Style(color = ink, size = box.height().coerceAtLeast(12f))
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
        var best = Style(color = ink, size = box.height() * .9f)
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
                    val aspectPenalty = abs(
                        kotlin.math.ln(
                            (glyph.width().toFloat() / glyph.height()) /
                                (box.width() / box.height()).coerceAtLeast(.01f)
                        )
                    )
                    val score = mismatch.toFloat() / max(1, union) + aspectPenalty * .5f
                    if (score < bestScore) {
                        bestScore = score
                        val inferred = box.height() * 40f / glyph.height()
                        best = Style(
                            family,
                            face,
                            ink,
                            inferred.coerceIn(box.height() * .70f, box.height() * 1.25f)
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
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.color
            typeface = Typeface.create(style.family, style.face)
        }
        fun build(size: Float): StaticLayout {
            paint.textSize = size
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(true)
                .build()
        }

        var lo = 1f
        // Prevent one uncertain style estimate from producing giant translated lettering.
        var hi = min(max(1f, style.size * textScale), max(1f, height * .92f))
        repeat(14) {
            val mid = (lo + hi) / 2f
            val candidate = build(mid)
            val fits = candidate.height <= height &&
                (0 until candidate.lineCount).all { candidate.getLineWidth(it) <= width + .5f }
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
        canvas.save()
        canvas.clipRect(patch.bounds)
        canvas.translate(
            patch.bounds.left.toFloat(),
            patch.bounds.top + (patch.bounds.height() - layout.height) / 2f
        )
        layout.draw(canvas)
        canvas.restore()
    }
}
