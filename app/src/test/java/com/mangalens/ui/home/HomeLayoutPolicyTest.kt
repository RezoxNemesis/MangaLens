package com.mangalens.ui.home

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutPolicyTest {
    @Test fun defaultsPreserveTheExistingModuleOrderAndKeepNewOptionalSectionsHidden() {
        val layout = HomeLayoutPolicy.decode(null)
        assertEquals(listOf(HomeModule.QUICK_ACTIONS, HomeModule.CONTINUE_READING, HomeModule.RECENT_MANGA,
            HomeModule.SOURCE_CHAPTERS, HomeModule.OREZ_AI, HomeModule.CONTINUE_WATCHING), layout.visibleModules)
        assertTrue(layout.hidden.containsAll(listOf(HomeModule.DOWNLOADS, HomeModule.TRANSLATION_QUEUE, HomeModule.BOOKMARKS)))
    }

    @Test fun savedVisibilityAndOrderRoundTripTogether() {
        val changed = HomeLayoutPolicy.move(HomeLayout(), HomeModule.RECENT_MANGA, -1)
            .let { HomeLayoutPolicy.setVisible(it, HomeModule.QUICK_ACTIONS, false) }
            .let { HomeLayoutPolicy.setVisible(it, HomeModule.BOOKMARKS, true) }
        val reopened = HomeLayoutPolicy.decode(HomeLayoutPolicy.encode(changed))
        assertEquals(changed, reopened)
        assertFalse(HomeModule.QUICK_ACTIONS in reopened.visibleModules)
        assertTrue(HomeModule.BOOKMARKS in reopened.visibleModules)
        assertTrue(reopened.visibleModules.indexOf(HomeModule.RECENT_MANGA) < reopened.visibleModules.indexOf(HomeModule.CONTINUE_READING))
    }

    @Test fun duplicateAndUnknownSavedIdsCannotDuplicateOrDeleteRealModules() {
        val layout = HomeLayoutPolicy.decode("""{"version":1,"order":["orez_ai","unknown","orez_ai","recent_manga"],"hidden":["quick_actions","unknown"]}""")
        assertEquals(HomeModule.OREZ_AI, layout.order.first())
        assertEquals(HomeModule.RECENT_MANGA, layout.order[1])
        assertEquals(HomeModule.entries.toSet(), layout.order.toSet())
        assertEquals(HomeModule.entries.size, layout.order.size)
        assertTrue(HomeModule.QUICK_ACTIONS in layout.hidden)
        assertTrue(HomeModule.DOWNLOADS in layout.hidden)
        assertFalse(HomeModule.CONTINUE_READING in layout.hidden)
    }

    @Test fun corruptOversizedAndUnknownVersionStorageFallsBackWithoutInventingSections() {
        for (raw in listOf("{broken", "x".repeat(4097), """{"version":99,"order":[],"hidden":[]}""", """{"version":1,"order":"orez_ai","hidden":[]}""")) {
            assertEquals(HomeLayout(), HomeLayoutPolicy.decode(raw))
        }
    }

    @Test fun movingAnEdgeOrUsingAnInvalidDirectionCannotRemoveASection() {
        val layout = HomeLayout()
        assertEquals(layout, HomeLayoutPolicy.move(layout, layout.order.first(), -1))
        assertEquals(layout, HomeLayoutPolicy.move(layout, layout.order.last(), 1))
        assertEquals(layout, HomeLayoutPolicy.move(layout, HomeModule.RECENT_MANGA, 2))
    }

    @Test fun hidingAllSectionsLeavesAValidRecoverableLayoutAndResetRestoresDefaults() {
        var hidden = HomeLayout()
        for (module in HomeModule.entries) hidden = HomeLayoutPolicy.setVisible(hidden, module, false)
        assertTrue(hidden.visibleModules.isEmpty())
        assertEquals(hidden, HomeLayoutPolicy.decode(HomeLayoutPolicy.encode(hidden)))
        assertEquals(HomeLayout(), HomeLayoutPolicy.reset())
    }
}
