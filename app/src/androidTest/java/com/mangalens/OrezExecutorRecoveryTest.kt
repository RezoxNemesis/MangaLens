package com.mangalens

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.agent.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OrezExecutorRecoveryTest {
    @Test fun realRoomCheckpointSurvivesDatabaseReopenAndRejectsLateCancelledWrite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "orez-recovery-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, OrezRoomDatabase::class.java, name).build()
        val plan = OrezAgentPlanner().plan("Download https://example.com/a.mp4 and https://example.com/b.mp4", OrezAgentContext())!!
        try {
            var store = OrezTaskStore(db.tasks())
            store.checkpoint(plan)
            try {
                OrezTaskExecutor(store, OrezDurableTools { step, id ->
                    if (step.index == 1) throw CancellationException("simulate worker interruption")
                    OrezToolResult.Completed(mapOf("downloadId" to id, "destination" to "content://media/fixture"))
                }).run(plan.id)
                fail("Expected interruption")
            } catch (_: CancellationException) { }
            db.close()
            db = Room.databaseBuilder(context, OrezRoomDatabase::class.java, name).build()
            store = OrezTaskStore(db.tasks())
            val restored = store.load(plan.id)!!
            assertEquals(OrezStepStatus.COMPLETED, restored.steps[0].status)
            assertEquals(OrezStepStatus.RUNNING, restored.steps[1].status)
            val visited = mutableListOf<Int>()
            assertTrue(OrezTaskExecutor(store, OrezDurableTools { step, id ->
                visited += step.index
                OrezToolResult.Completed(mapOf("downloadId" to id, "destination" to "content://media/fixture-2"))
            }).run(plan.id) is OrezTaskExecutor.Result.Completed)
            assertEquals(listOf(1), visited)
            assertTrue(store.checkpoint(store.load(plan.id)!!, OrezTaskStatus.CANCELLED))
            assertFalse(store.checkpoint(restored, OrezTaskStatus.RUNNING))
            assertEquals(OrezTaskStatus.CANCELLED, store.load(plan.id)!!.status)
        } finally {
            db.close(); context.deleteDatabase(name)
        }
    }
}
