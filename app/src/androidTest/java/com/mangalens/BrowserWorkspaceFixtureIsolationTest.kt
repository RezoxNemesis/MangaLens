package com.mangalens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.ui.web.AtomicBrowserWorkspaceIo
import com.mangalens.ui.web.BrowserWorkspaceRepository
import com.mangalens.ui.web.BrowserWorkspaceStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserWorkspaceFixtureIsolationTest {
    @Test fun freshScopedBrowserJournalRestoresThePreviousSessionAndKeepsItsDurablePage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BrowserWorkspaceFixtureIsolation(context).use { previous ->
            val held = BrowserWorkspaceRepository.session(context)
            runBlocking { withTimeout(5_000) {
                held.submit { store -> store.navigate(store.snapshot().activeTabId, "https://example.com/preserved") }.await()
            } }
            val bytes = previous.journal.readBytes()
            lateinit var fixtureJournal: java.io.File
            BrowserWorkspaceFixtureIsolation(context).use { fixture ->
                fixtureJournal = fixture.journal
                val fresh = BrowserWorkspaceRepository.session(context)
                assertNotSame(held, fresh)
                val initial = runBlocking { withTimeout(5_000) { fresh.submit { it.snapshot() }.await() } }
                assertEquals("", initial.activeTab.url)
                assertTrue(initial.history.isEmpty())
                runBlocking { withTimeout(5_000) {
                    fresh.submit { store -> store.navigate(store.snapshot().activeTabId, "http://localhost:12345/fixture") }.await()
                } }
                assertEquals("http://localhost:12345/fixture", BrowserWorkspaceStore(AtomicBrowserWorkspaceIo(fixture.journal)).snapshot().activeTab.url)
            }
            assertSame(held, BrowserWorkspaceRepository.session(context))
            assertArrayEquals(bytes, previous.journal.readBytes())
            assertEquals("https://example.com/preserved", BrowserWorkspaceStore(AtomicBrowserWorkspaceIo(previous.journal)).snapshot().activeTab.url)
            assertFalse("Closed fixture retained its generated journal", fixtureJournal.exists())
        }
    }
}
