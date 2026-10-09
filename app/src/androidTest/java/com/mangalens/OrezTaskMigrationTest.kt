package com.mangalens

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.orez.OrezRoomDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrezTaskMigrationTest {
    @Test
    fun taskMigrationCreatesDurableTaskJournal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "orez-task-migration-" + System.nanoTime() + ".db"
        context.deleteDatabase(name)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            OrezRoomDatabase.MIGRATION_2_3.migrate(db)

            val columns = linkedSetOf<String>()
            db.query("PRAGMA table_info(orez_tasks)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }

            assertTrue("task table was not created", columns.isNotEmpty())
            assertTrue("task id missing", "id" in columns)
            assertTrue("objective missing", "objective" in columns)
            assertTrue("status missing", "status" in columns)
            assertTrue("planJson missing", "planJson" in columns)
            assertTrue("updatedAt missing", "updatedAt" in columns)

            db.execSQL(
                "INSERT INTO orez_tasks (id, objective, status, planJson, createdAt, updatedAt, lastError) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("task-1", "Translate chapter", "RUNNING", "{}", 1L, 2L, null)
            )

            db.query("SELECT status, objective FROM orez_tasks WHERE id = 'task-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RUNNING", cursor.getString(0))
                assertEquals("Translate chapter", cursor.getString(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
