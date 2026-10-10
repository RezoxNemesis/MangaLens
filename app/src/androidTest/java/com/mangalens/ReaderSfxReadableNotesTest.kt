package com.mangalens

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.mangalens.core.translation.memory.MemorySfxPresentation
import com.mangalens.ui.reader.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Authored UNRUN. Real fixed Compose UI; test-owned note metadata is not native/crop authority. */
@RunWith(AndroidJUnit4::class)
class ReaderSfxReadableNotesTest {
    @Test fun fixedReadingDialogKeepsItsTextBoundsAcrossCropSplitAndGuidedRasterTransforms() =
        coreScreenSmoke("reader-sfx-readable-notes") {
            val app = instrumentation.targetContext
            val transform = mutableIntStateOf(0)
            val registry = com.mangalens.ui.reader.ReaderSfxNoteRegistry()
            registry.register(source { true })
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { a -> a.setContent { MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            // Real raster peer transforms/clipping. The fixed host is deliberately outside all three.
                            val plane = when (transform.intValue) {
                                0 -> Modifier.graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
                                1 -> Modifier.drawWithContent { clipRect(right = size.width * .2f) { this@drawWithContent.drawContent() } }
                                else -> Modifier.graphicsLayer(scaleX = 4f, scaleY = 4f, translationX = -600f, translationY = -500f)
                            }
                            Box(Modifier.fillMaxSize().clipToBounds()) { Box(Modifier.fillMaxSize().then(plane).background(Color.DarkGray)) }
                            com.mangalens.ui.reader.ReaderSfxNotesHost(registry, setOf(37), true,
                                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp))
                        }
                    } } }
                    var initial: android.graphics.Rect? = null
                    for (mode in 0..2) {
                        scenario.onActivity { transform.intValue = mode }
                        clickSettledUi(device, By.desc("Open SFX reading notes").pkg(app.packageName), 8000)
                        assertTrue(device.wait(Until.hasObject(By.text("Page 37 · SFX #7").pkg(app.packageName)), 8000))
                        val selector = By.text("Translation note: धमाका! यह आपका सहेजा हुआ अनुवाद है।").pkg(app.packageName)
                        assertTrue("Fixed reading note missing for raster transform $mode", device.wait(Until.hasObject(selector), 8000))
                        val bounds = device.findObject(selector).visibleBounds
                        assertTrue("Fixed note clipped for transform $mode", bounds.width() > 0 && bounds.height() > 0)
                        if (initial == null) initial = android.graphics.Rect(bounds) else assertEquals("Raster transform changed fixed reading text bounds", initial, bounds)
                        clickSettledUi(device, By.text("Close").pkg(app.packageName), 8000)
                    }
                }
            } finally { registry.close() }
        }

    @Test fun retiredCurrentNoteMetadataRemovesTheOpenDialogAndItsButton() = coreScreenSmoke("reader-sfx-retired-note") {
        val app = instrumentation.targetContext
        val current = mutableStateOf(true)
        val registry = com.mangalens.ui.reader.ReaderSfxNoteRegistry()
        registry.register(source { current.value })
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a -> a.setContent { MaterialTheme {
                    com.mangalens.ui.reader.ReaderSfxNotesHost(registry, setOf(37), true, Modifier.statusBarsPadding())
                } } }
                clickSettledUi(device, By.desc("Open SFX reading notes").pkg(app.packageName), 8000)
                assertTrue(device.wait(Until.hasObject(By.text("SFX reading notes").pkg(app.packageName)), 8000))
                scenario.onActivity { current.value = false }
                assertTrue(device.wait(Until.gone(By.desc("Open SFX reading notes").pkg(app.packageName)), 8000))
                assertTrue(device.wait(Until.gone(By.text("SFX reading notes").pkg(app.packageName)), 8000))
            }
        } finally { registry.close() }
    }

    @Test fun invisiblePageOrLockedReaderDoesNotExposeAReadingNoteControl() = coreScreenSmoke("reader-sfx-note-visibility") {
        val app = instrumentation.targetContext
        val visible = mutableStateOf(setOf(91))
        val enabled = mutableStateOf(true)
        val registry = com.mangalens.ui.reader.ReaderSfxNoteRegistry()
        registry.register(source { true })
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a -> a.setContent { MaterialTheme {
                    com.mangalens.ui.reader.ReaderSfxNotesHost(registry, visible.value, enabled.value, Modifier.statusBarsPadding())
                } } }
                assertTrue(device.wait(Until.gone(By.desc("Open SFX reading notes").pkg(app.packageName)), 8000))
                scenario.onActivity { visible.value = setOf(37) }
                assertTrue(device.wait(Until.hasObject(By.desc("Open SFX reading notes").pkg(app.packageName)), 8000))
                clickSettledUi(device, By.desc("Open SFX reading notes").pkg(app.packageName), 8000)
                scenario.onActivity { enabled.value = false }
                assertTrue(device.wait(Until.gone(By.desc("Open SFX reading notes").pkg(app.packageName)), 8000))
                assertTrue(device.wait(Until.gone(By.text("SFX reading notes").pkg(app.packageName)), 8000))
            }
        } finally { registry.close() }
    }

    private fun source(current: () -> Boolean) = object : com.mangalens.ui.reader.ReaderSfxNoteSource {
        override fun noteGroup(pageIndex: Int): com.mangalens.ui.reader.ReaderSfxNoteGroup? =
            if (pageIndex != 37 || !current()) null else com.mangalens.ui.reader.ReaderSfxNoteGroup(37,
                listOf(com.mangalens.ui.reader.ReaderSfxNoteRow(6, MemorySfxPresentation.ALONGSIDE,
                    "धमाका! यह आपका सहेजा हुआ अनुवाद है।", null, com.mangalens.ui.reader.ReaderSfxOriginalReadiness.UNAVAILABLE)))
    }
}
