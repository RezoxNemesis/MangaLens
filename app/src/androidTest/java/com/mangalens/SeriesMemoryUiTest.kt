package com.mangalens

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.memory.SeriesMemoryStore
import com.mangalens.ui.library.SeriesMemoryScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** An explicit scoped private journal; tests never clear or relink the user's library. */
@RunWith(AndroidJUnit4::class)
class SeriesMemoryUiTest {
    @Test fun explicitPageZeroOrderAndGlossaryCrudSurviveColdUiRecreationThenUnlink() = coreScreenSmoke("series-memory-ui") {
        val root = File(context.cacheDir, "series-memory-ui-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val chapter = SavedChapter("series-ui-${UUID.randomUUID()}", "Explicit chapter fixture", "local:series-ui", emptyList())
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            fun attach() {
                val scopedStore = SeriesMemoryStore(root)
                activeScenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    SeriesMemoryScreen(chapter, "hi", onBack = {}, suppliedStore = scopedStore)
                } } }
            }
            fun click(description: String) { scrollTo(By.desc(description).pkg(context.packageName)); tapSettled(By.desc(description).pkg(context.packageName)) }
            fun type(description: String, value: String) { scrollTo(By.desc(description).pkg(context.packageName)); typeIntoEditableUi(device, description, value) }
            fun memory() = SeriesMemoryStore(root)
            attach()
            type("New memory series name", "Explicit fixture series")
            click("Create memory series")
            node(By.desc("Series memory status: Series created. Link a chapter explicitly below.").pkg(context.packageName))
            assertNull("Creating a profile cannot silently associate an equal-title chapter", runBlocking { memory().inspectChapter(chapter.id).association })
            val series = runBlocking { memory().listSeries().single() }
            type("Memory chapter order", "0")
            click("Link chapter to memory series")
            waitFor("Physical Link must persist explicit series and zero order") { runBlocking {
                memory().inspectChapter(chapter.id).association?.let { it.seriesId == series.id && it.ordinal == 0 } == true
            } }
            click("Add glossary term")
            type("Glossary source spelling", "Jin")
            type("Glossary preferred spelling", "जिन")
            type("Glossary aliases", "Jin Woo, Sung Jin")
            click("Save glossary term")
            waitFor("Physical Save must write exactly one personal glossary term") { runBlocking { memory().profile(series.id)?.glossary?.size == 1 } }
            val term = runBlocking { memory().profile(series.id)!!.glossary.single() }
            assertEquals(listOf("Jin Woo", "Sung Jin"), term.aliases); assertNull(term.origin)
            capture("explicit-link-and-created-term")

            activeScenario.recreate(); attach()
            scrollTo(By.text("Linked to Explicit fixture series · order 0").pkg(context.packageName))
            node(By.text("Linked to Explicit fixture series · order 0").pkg(context.packageName))
            scrollTo(By.text("Jin → जिन").pkg(context.packageName))
            node(By.text("Jin → जिन").pkg(context.packageName))
            click("Edit glossary term: Jin")
            assertEquals("Jin", readEditableUi(device, "Glossary source spelling").text)
            assertEquals("जिन", readEditableUi(device, "Glossary preferred spelling").text)
            type("Glossary preferred spelling", "जिन वू")
            click("Save glossary term")
            waitFor("Physical Edit must keep the original term identity") { runBlocking {
                memory().profile(series.id)?.glossary?.singleOrNull()?.let { it.id == term.id && it.preferred == "जिन वू" } == true
            } }
            click("Remove glossary term: Jin")
            waitFor("Physical Remove must persist glossary deletion") { runBlocking { memory().profile(series.id)?.glossary?.isEmpty() == true } }
            click("Unlink memory series")
            waitFor("Physical Unlink must return to chapter-only scope") { runBlocking { memory().inspectChapter(chapter.id).association == null } }
            scrollTo(By.text("Chapter-only memory").pkg(context.packageName)); node(By.text("Chapter-only memory").pkg(context.packageName))
            capture("unlinked-and-term-removed")
        } catch (failure: Throwable) {
            // Preserve the actual screen before ActivityScenario disposal; the outer
            // smoke handler reuses this evidence and cannot replace it with the launcher.
            runCatching { recordFailure(failure) }
            throw failure
        } finally {
            try { scenario?.close() }
            finally {
                // The screen owns its Compose scope. After disposal, this actual Store read
                // crosses the same IO mutation guard, so in-flight scoped writes finish
                // before their fixture directory is removed; queued cancelled writes cannot enter.
                try { runBlocking { SeriesMemoryStore(root).listSeries() } }
                finally { root.deleteRecursively() }
            }
        }
    }
}
