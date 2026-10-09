package com.mangalens

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mangalens.ui.components.BrandHeader
import com.mangalens.ui.theme.MangaLensTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production header at the 320dp Home viewport minus its 32dp margins.
 * These methods must run on a device before claiming visual/control acceptance.
 */
@RunWith(AndroidJUnit4::class)
class ResponsiveBrandHeaderTest {
    @Test fun narrowHomeKeepsFullTitleAndEveryOriginalActionReachable() = verify(1f)
    @Test fun largeAccessibilityFontKeepsFullTitleAndEveryOriginalActionReachable() = verify(2f)

    private fun verify(fontScale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val expectedSingleLineHeight = AtomicInteger()
        val actions = List(3) { AtomicInteger() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    MangaLensTheme {
                        val measure = rememberTextMeasurer()
                        expectedSingleLineHeight.set(measure.measure("MangaLens",
                            MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                            softWrap = false, maxLines = 1).size.height)
                        Box(Modifier.width(288.dp)) {
                            BrandHeader("MangaLens", "READ · WATCH · BROWSE") {
                                IconButton({ actions[0].incrementAndGet() }) { Icon(Icons.Outlined.Link, "Open link") }
                                IconButton({ actions[1].incrementAndGet() }) { Icon(Icons.Outlined.Tune, "Customize Home") }
                                IconButton({ actions[2].incrementAndGet() }) { Icon(Icons.Outlined.Settings, "Settings and protection") }
                            }
                        }
                    }
                }
            } }
            val title = device.wait(Until.findObject(By.text("MangaLens")), 10_000)
                ?: error("Full MangaLens title was not visible")
            val logo = device.wait(Until.findObject(By.desc("MangaLens logo")), 10_000)
                ?: error("Original logo was not visible")
            assertTrue("Brand wrapped onto a second line", title.visibleBounds.height() <= expectedSingleLineHeight.get() + 2)
            assertTrue("Brand title was clipped", title.visibleBounds.width() > 0)
            listOf("Open link", "Customize Home", "Settings and protection").forEachIndexed { index, label ->
                val action = device.wait(Until.findObject(By.desc(label)), 10_000) ?: error("Missing action: $label")
                assertTrue("Actions were squeezed into the brand row", action.visibleBounds.top >= maxOf(logo.visibleBounds.bottom, title.visibleBounds.bottom))
                action.click()
                instrumentation.waitForIdleSync()
                assertEquals("Action lost its callback: $label", 1, actions[index].get())
            }
        }
    }
}
