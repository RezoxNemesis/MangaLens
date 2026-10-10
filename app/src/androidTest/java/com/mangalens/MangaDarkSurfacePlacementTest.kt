package com.mangalens

import android.graphics.*
import android.text.Layout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mangalens.core.translation.MangaLettering
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual pixels/layout, not screenshots used as original-source ground truth. Authored UNRUN. */
@RunWith(AndroidJUnit4::class)
class MangaDarkSurfacePlacementTest {
    @Test fun v2DoesNotUseNearbySimilarDarkArtworkAsWritableBalloonSpace() {
        val image = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val panel = Color.rgb(32, 30, 48)
        val balloon = Color.rgb(8, 12, 28)
        val surface = Rect(100, 50, 300, 250)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f; typeface = Typeface.DEFAULT_BOLD }
        val text = "NO CLUE"
        val glyph = Rect(); paint.getTextBounds(text, 0, text.length, glyph)
        val x = 200f - glyph.width() / 2f; val baseline = 150f
        val canvas = Canvas(image); canvas.drawColor(panel)
        canvas.drawRect(surface, Paint().apply { color = balloon })
        canvas.drawText(text, x, baseline, paint)
        val source = RectF(glyph).apply { offset(x, baseline) }
        var old: MangaLettering.Patch? = null; var fresh: MangaLettering.Patch? = null
        try {
            old = MangaLettering.prepare(image, source, listOf(source), text, reconstructionVersion = 1)
            fresh = MangaLettering.prepare(image, source, listOf(source), text, reconstructionVersion = 2)
            assertTrue("Historical tolerance fixture must exercise the similar-dark boundary", old.bounds.left < surface.left || old.bounds.right > surface.right)
            assertTrue("Fresh writable rectangle crossed the observed dark surface", surface.contains(fresh.bounds))
            val output = image.copy(Bitmap.Config.ARGB_8888, true)
            try {
                MangaLettering.draw(Canvas(output), fresh, "कोई अन्य सुराग नहीं है।")
                for (y in 0 until output.height) for (px in 0 until output.width) if (!surface.contains(px, y))
                    assertEquals("Artwork outside the observed surface changed at $px,$y", image.getPixel(px, y), output.getPixel(px, y))
                assertEquals(panel, image.getPixel(30, 100))
            } finally { output.recycle() }
        } finally { old?.background?.recycle(); fresh?.background?.recycle(); image.recycle() }
    }
    @Test fun devanagariAndRomanHindiAreFullyLaidOutAndClippedToTheirCapturedRectangle() {
        val bounds = Rect(40, 40, 240, 180)
        val style = MangaLettering.Style(color = Color.WHITE, size = 48f, alignment = Layout.Alignment.ALIGN_CENTER)
        for (text in listOf("लेकिन एक बार फिर, मुझे केवल कुछ निशान ही मिले।", "Lekin ek baar phir, mujhe keval kuch nishaan hi mile.")) {
            val image = Bitmap.createBitmap(280, 220, Bitmap.Config.ARGB_8888)
            val original = Color.rgb(15, 17, 29); image.eraseColor(original)
            try {
                val layout = MangaLettering.layout(text, style, bounds.width(), bounds.height(), 1.5f)
                assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
                assertTrue(layout.height <= bounds.height())
                val canvas = Canvas(image); MangaLettering.drawText(canvas, bounds, style, text, 1.5f, layout)
                var ink = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    if (!bounds.contains(x, y)) assertEquals(original, image.getPixel(x, y))
                    else if (image.getPixel(x, y) != original) ink++
                }
                assertTrue("Translation rendered no readable ink", ink > 0)
            } finally { image.recycle() }
        }
    }
}
