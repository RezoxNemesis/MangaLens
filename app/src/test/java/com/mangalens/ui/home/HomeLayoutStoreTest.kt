package com.mangalens.ui.home

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class HomeLayoutStoreTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("home-layout").toFile()
        val file = root.resolve("layout.txt")
        var failWrites = false
        val storage = object : HomeLayoutStorage {
            override fun read(): String? = file.takeIf { it.isFile }?.readText()
            override fun write(value: String): Boolean {
                if (failWrites) return false
                val temporary = root.resolve("layout.new").apply { writeText(value) }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return true
            }
        }
        fun store() = HomeLayoutStore(storage)
        override fun close() { root.deleteRecursively() }
    }

    @Test fun visibilityAndOrderSurviveAColdPreferenceStoreInstance() {
        Fixture().use { f ->
            val expected = HomeLayout(order = listOf(HomeModule.QUICK_ACTIONS, HomeModule.RECENT_MANGA,
                HomeModule.CONTINUE_READING) + HomeModule.entries.drop(3),
                hidden = setOf(HomeModule.QUICK_ACTIONS, HomeModule.DOWNLOADS, HomeModule.TRANSLATION_QUEUE, HomeModule.BOOKMARKS))
            assertTrue(f.store().save(expected))
            assertEquals(expected, f.store().load())
            assertTrue(f.file.isFile)
        }
    }

    @Test fun anUnsuccessfulSaveKeepsTheLastPersistedLayout() {
        Fixture().use { f ->
            val store = f.store()
            val saved = HomeLayout(hidden = setOf(HomeModule.DOWNLOADS, HomeModule.TRANSLATION_QUEUE))
            assertTrue(store.save(saved))
            f.failWrites = true
            assertFalse(store.save(HomeLayoutPolicy.setVisible(saved, HomeModule.QUICK_ACTIONS, false)))
            assertEquals(saved, f.store().load())
        }
    }

    @Test fun readingCorruptPreferencesDoesNotSilentlyOverwriteThem() {
        Fixture().use { f ->
            f.file.writeText("corrupted older layout")
            assertEquals(HomeLayout(), f.store().load())
            assertEquals("corrupted older layout", f.file.readText())
        }
    }

    @Test fun resetIsAnExplicitPersistedUserActionRatherThanAnAutomaticStartupMigration() {
        Fixture().use { f ->
            val store = f.store()
            val changed = HomeLayout(hidden = HomeLayout().hidden + HomeModule.OREZ_AI)
            assertTrue(store.save(changed))
            val editorDraft = HomeLayoutPolicy.reset()
            assertEquals(changed, f.store().load())
            assertTrue(store.save(editorDraft))
            assertEquals(HomeLayout(), f.store().load())
        }
    }
}
