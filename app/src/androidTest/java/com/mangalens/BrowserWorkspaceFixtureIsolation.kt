package com.mangalens

import android.content.Context
import com.mangalens.ui.web.AtomicBrowserWorkspaceIo
import com.mangalens.ui.web.BrowserWorkspaceRepository
import com.mangalens.ui.web.BrowserWorkspaceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * Exercises the real browser repository through a unique QA AtomicFile journal, while preserving
 * the app's existing journal and shared session. Only this Android test utility uses reflection;
 * production code has no reset hooks and model/library/user files are never cleared.
 * Close after finishing the activity so its view cannot dispatch into the restored session.
 */
internal class BrowserWorkspaceFixtureIsolation(context: Context) : Closeable {
    private val directory = File(context.cacheDir, "browser-workspace-qa-${UUID.randomUUID()}")
    val journal = File(directory, "session.json")
    private val sharedField = BrowserWorkspaceRepository::class.java.getDeclaredField("shared").apply { isAccessible = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val session = BrowserWorkspaceSession(AtomicBrowserWorkspaceIo(journal), scope)
    private val previous: BrowserWorkspaceSession?
    private var closed = false

    init {
        previous = synchronized(BrowserWorkspaceRepository) {
            val held = sharedField.get(null) as BrowserWorkspaceSession?
            sharedField.set(null, session)
            held
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            runBlocking { withTimeout(5_000) { session.submit { it.snapshot() }.await() } }
        } finally {
            try {
                synchronized(BrowserWorkspaceRepository) {
                    check(sharedField.get(null) === session) { "Another test replaced the scoped browser session" }
                    sharedField.set(null, previous)
                }
            } finally {
                scope.cancel()
                check(directory.deleteRecursively() || !directory.exists()) { "Could not remove scoped QA browser journal" }
            }
        }
    }
}
