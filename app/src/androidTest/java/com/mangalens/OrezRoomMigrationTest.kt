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
class OrezRoomMigrationTest {
    @Test
    fun styleScopeMigrationPreservesLegacyTranslationAndAllowsIndependentStyles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "orez-migration-" + System.nanoTime() + ".db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS orez_translations (" +
                                "[key] TEXT NOT NULL, source TEXT NOT NULL, target TEXT NOT NULL, " +
                                "targetLanguage TEXT NOT NULL, PRIMARY KEY([key]))"
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS index_orez_translations_source_targetLanguage " +
                                "ON orez_translations (source, targetLanguage)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_orez_translations_source ON orez_translations (source)"
                        )
                        db.execSQL(
                            "INSERT INTO orez_translations ([key], source, target, targetLanguage) VALUES (?, ?, ?, ?)",
                            arrayOf<Any>("legacy-key", "hello", "नमस्ते", "hi")
                        )
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val db = helper.writableDatabase
            OrezRoomDatabase.MIGRATION_1_2.migrate(db)
            val columns = linkedSetOf<String>()
            db.query("PRAGMA table_info(orez_translations)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertTrue("style column missing after migration", "style" in columns)
            assertTrue("scope column missing after migration", "scope" in columns)
            db.query("SELECT target, style, scope FROM orez_translations WHERE source = 'hello' AND targetLanguage = 'hi'").use { cursor ->
                assertTrue("legacy translation was lost", cursor.moveToFirst())
                assertEquals("नमस्ते", cursor.getString(0))
                assertEquals("natural", cursor.getString(1))
                assertEquals("global", cursor.getString(2))
            }
            db.execSQL(
                "INSERT INTO orez_translations ([key], source, target, targetLanguage, style, scope) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("formal-key", "hello", "नमस्कार", "hi", "formal", "global")
            )
            db.query("SELECT COUNT(*) FROM orez_translations WHERE source = 'hello' AND targetLanguage = 'hi'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            val indices = linkedSetOf<String>()
            db.query("PRAGMA index_list(orez_translations)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) indices += cursor.getString(nameIndex)
            }
            assertTrue("style/scope unique index missing", "index_orez_translations_source_targetLanguage_style_scope" in indices)
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
