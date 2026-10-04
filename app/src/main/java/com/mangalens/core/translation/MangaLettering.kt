package com.mangalens.core.translation

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.max

/** Local lettering reconstruction. Background samples come from outside the source glyphs. */
object MangaLettering {
    data class Style(val family: String = "sans-serif", val face: Int = Typeface.NORMAL,
                     val color: Int = Color.BLACK, val size: Float = 24f)
    data class Patch(val bounds: Rect, val background: Bitmap, val style: Style)

    fun prepare(image: Bitmap, bounds: RectF, lines: List<RectF>, source: String, preserveStyle: Boolean = true): Patch {
        val padding = max(2, (lines.firstOrNull()?.height()?.times(.10f) ?: 2f).toInt())
        val rect = Rect(bounds.left.toInt() - padding, bounds.top.toInt() - padding,
            bounds.right.toInt() + padding + 1, bounds.bottom.toInt() + padding + 1)
        rect.intersect(0, 0, image.width, image.height)
        require(rect.width() > 0 && rect.height() > 0)
        val patch = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        Canvas(patch).drawBitmap(image, rect, Rect(0, 0, rect.width(), rect.height()), null)
        val first = lines.firstOrNull() ?: bounds
        val background = borderColor(image, first, padding)
        val ink = inkColor(image, first, background)
        val style = if (preserveStyle) inferStyle(image, first, source.lineSequence().firstOrNull().orEmpty(), ink, background) else Style(color = ink, size = first.height())
        val canvas = Canvas(patch)
        // Erase the entire detected line including antialiasing; retain the space between lines.
        (lines.ifEmpty { listOf(bounds) }).forEach { line ->
            val area = Rect((line.left.toInt() - padding).coerceAtLeast(rect.left),
                (line.top.toInt() - padding).coerceAtLeast(rect.top),
                (line.right.toInt() + padding + 1).coerceAtMost(rect.right),
                (line.bottom.toInt() + padding + 1).coerceAtMost(rect.bottom))
            val fill = borderColor(image, line, padding)
            canvas.drawRect((area.left - rect.left).toFloat(), (area.top - rect.top).toFloat(),
                (area.right - rect.left).toFloat(), (area.bottom - rect.top).toFloat(), Paint().apply { color = fill })
        }
        return Patch(rect, patch, style)
    }

    private fun borderColor(image: Bitmap, box: RectF, pad: Int): Int {
        val colors = mutableListOf<Int>()
        val l = (box.left.toInt() - pad).coerceIn(0, image.width - 1)
        val r = (box.right.toInt() + pad).coerceIn(l, image.width - 1)
        val t = (box.top.toInt() - pad).coerceIn(0, image.height - 1)
        val b = (box.bottom.toInt() + pad).coerceIn(t, image.height - 1)
        for (x in l..r step max(1, (r - l) / 80)) { colors += image.getPixel(x, t); colors += image.getPixel(x, b) }
        for (y in t..b step max(1, (b - t) / 30)) { colors += image.getPixel(l, y); colors += image.getPixel(r, y) }
        fun median(channel: (Int) -> Int) = colors.map(channel).sorted()[colors.size / 2]
        return Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
    }

    private fun distance(a: Int, b: Int) = abs(Color.red(a) - Color.red(b)) +
        abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun inkColor(image: Bitmap, box: RectF, background: Int): Int {
        val colors = mutableListOf<Int>()
        for (y in box.top.toInt().coerceAtLeast(0) until box.bottom.toInt().coerceAtMost(image.height) step 2)
            for (x in box.left.toInt().coerceAtLeast(0) until box.right.toInt().coerceAtMost(image.width) step 2) {
                val color = image.getPixel(x, y)
                if (distance(color, background) > 180) colors += color
            }
        if (colors.isEmpty()) return if (Color.red(background) + Color.green(background) + Color.blue(background) > 384) Color.BLACK else Color.WHITE
        val strongest = colors.sortedByDescending { distance(it, background) }.take(max(1, colors.size / 3))
        return Color.rgb(strongest.map(Color::red).average().toInt(), strongest.map(Color::green).average().toInt(), strongest.map(Color::blue).average().toInt())
    }

    /** Compare normalized source glyph silhouettes against available Android font families/styles. */
    private fun inferStyle(image: Bitmap, box: RectF, source: String, ink: Int, background: Int): Style {
        if (source.isBlank()) return Style(color = ink, size = box.height())
        val w = 160; val h = 48
        val actual = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(actual).drawBitmap(image, Rect(box.left.toInt().coerceAtLeast(0), box.top.toInt().coerceAtLeast(0),
            box.right.toInt().coerceAtMost(image.width), box.bottom.toInt().coerceAtMost(image.height)), Rect(0, 0, w, h), null)
        var best = Style(color = ink, size = box.height() * .9f)
        var bestScore = Float.MAX_VALUE
        val trial = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            for (family in listOf("sans-serif", "sans-serif-condensed", "serif", "cursive")) {
                for (face in listOf(Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC)) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 40f; typeface = Typeface.create(family, face) }
                    val glyph = Rect(); paint.getTextBounds(source, 0, source.length, glyph)
                    if (glyph.width() <= 0 || glyph.height() <= 0) continue
                    val c = Canvas(trial); c.drawColor(background)
                    c.save(); c.scale(w.toFloat() / glyph.width(), h.toFloat() / glyph.height())
                    c.drawText(source, -glyph.left.toFloat(), -glyph.top.toFloat(), paint); c.restore()
                    var mismatch = 0; var union = 0
                    for (y in 0 until h) for (x in 0 until w) {
                        val a = distance(actual.getPixel(x, y), background) > 100
                        val b = distance(trial.getPixel(x, y), background) > 100
                        if (a || b) union++
                        if (a != b) mismatch++
                    }
                    val score = mismatch.toFloat() / max(1, union)
                    if (score < bestScore) { bestScore = score; best = Style(family, face, ink, box.height() * 40f / glyph.height()) }
                }
            }
        } finally { actual.recycle(); trial.recycle() }
        return best
    }

    fun layout(text: String, style: Style, width: Int, height: Int, textScale: Float = 1f): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = style.color; typeface = Typeface.create(style.family, style.face) }
        fun build(size: Float): StaticLayout {
            paint.textSize = size
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).build()
        }
        var lo = 1f; var hi = max(1f, style.size * textScale)
        repeat(14) {
            val mid = (lo + hi) / 2f
            val candidate = build(mid)
            val fits = candidate.height <= height && (0 until candidate.lineCount).all { candidate.getLineWidth(it) <= width + .5f }
            if (fits) lo = mid else hi = mid
        }
        return build(lo)
    }

    fun draw(canvas: Canvas, patch: Patch, text: String, textScale: Float = 1f) {
        canvas.drawBitmap(patch.background, patch.bounds.left.toFloat(), patch.bounds.top.toFloat(), null)
        val layout = layout(text, patch.style, patch.bounds.width(), patch.bounds.height(), textScale)
        canvas.save()
        canvas.clipRect(patch.bounds)
        canvas.translate(patch.bounds.left.toFloat(), patch.bounds.top + (patch.bounds.height() - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }
}
