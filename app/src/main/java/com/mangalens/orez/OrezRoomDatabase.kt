package com.mangalens.orez

import android.content.Context
import androidx.room.*
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "orez_messages", indices = [Index("createdAt")])
data class OrezMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "orez_conversations", indices = [Index(value = ["prompt"], unique = true), Index("category")])
data class OrezConversationEntity(
    @PrimaryKey val key: String,
    val prompt: String,
    val response: String,
    val category: String
)

@Entity(
    tableName = "orez_translations",
    indices = [
        Index(value = ["source", "targetLanguage", "style", "scope"], unique = true),
        Index("source")
    ]
)
data class OrezTranslationEntity(
    @PrimaryKey val key: String,
    val source: String,
    val target: String,
    val targetLanguage: String,
    val style: String = "natural",
    val scope: String = "global"
)

@Entity(
    tableName = "orez_tasks",
    indices = [Index("status"), Index("updatedAt")]
)
data class OrezTaskEntity(
    @PrimaryKey val id: String,
    val objective: String,
    val status: String,
    val planJson: String,
    val createdAt: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val lastError: String? = null
)

@Dao
interface OrezMessageDao {
    @Query("SELECT * FROM (SELECT * FROM orez_messages ORDER BY id DESC LIMIT 200) ORDER BY id ASC")
    fun observe(): Flow<List<OrezMessageEntity>>
    @Insert suspend fun insert(message: OrezMessageEntity)
    @Query("SELECT COUNT(*) FROM orez_messages") suspend fun count(): Int
    @Query("DELETE FROM orez_messages WHERE id NOT IN (SELECT id FROM orez_messages ORDER BY id DESC LIMIT 200)")
    suspend fun trimHistory()
    @Query("DELETE FROM orez_messages WHERE id = :id") suspend fun deleteMessage(id: Long)
    @Query("SELECT MIN(id) FROM orez_messages WHERE id > :id AND role = 'YOU'") suspend fun nextUserMessageId(id: Long): Long?
    @Query("DELETE FROM orez_messages WHERE id >= :fromId AND id < :untilId") suspend fun deleteRange(fromId: Long, untilId: Long)
    @Query("DELETE FROM orez_messages") suspend fun clear()
}

@Dao
interface OrezDatasetDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversations(items: List<OrezConversationEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTranslations(items: List<OrezTranslationEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranslation(item: OrezTranslationEntity)
    @Query("SELECT * FROM orez_conversations WHERE prompt = :prompt COLLATE NOCASE LIMIT 1")
    suspend fun exactConversation(prompt: String): OrezConversationEntity?
    @Query("SELECT * FROM orez_conversations WHERE prompt LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY length(prompt) ASC LIMIT :limit")
    suspend fun searchConversations(query: String, limit: Int): List<OrezConversationEntity>
    @Query("SELECT target FROM orez_translations WHERE source = :source COLLATE NOCASE AND targetLanguage = :targetLanguage COLLATE NOCASE AND style = 'natural' AND scope = 'global' LIMIT 1")
    suspend fun exactTranslation(source: String, targetLanguage: String): String?
    @Query("SELECT target FROM orez_translations WHERE source = :source COLLATE NOCASE AND targetLanguage = :targetLanguage COLLATE NOCASE AND style = :style AND scope = :scope LIMIT 1")
    suspend fun exactTranslationScoped(source: String, targetLanguage: String, style: String, scope: String): String?
    @Query("SELECT target FROM orez_translations WHERE source LIKE '%' || :source || '%' COLLATE NOCASE AND targetLanguage = :targetLanguage COLLATE NOCASE AND style = 'natural' AND scope = 'global' ORDER BY length(source) ASC LIMIT :limit")
    suspend fun searchTranslations(source: String, targetLanguage: String, limit: Int): List<String>
    @Query("SELECT COUNT(*) FROM orez_conversations") fun conversationCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM orez_conversations") suspend fun conversationCountOnce(): Int
    @Query("SELECT COUNT(*) FROM orez_translations") suspend fun translationCountOnce(): Int
    @Query("SELECT COUNT(*) FROM orez_translations") fun translationCount(): Flow<Int>
    @Query("DELETE FROM orez_conversations") suspend fun clearConversations()
    @Query("DELETE FROM orez_translations") suspend fun clearTranslations()
}

@Dao
interface OrezTaskDao {
    @Query("SELECT * FROM orez_tasks WHERE status IN ('PLANNED','WAITING_APPROVAL','RUNNING') ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<OrezTaskEntity>>

    @Query("SELECT * FROM orez_tasks WHERE id = :id LIMIT 1")
    suspend fun get(id: String): OrezTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: OrezTaskEntity)

    @Transaction
    suspend fun checkpointIfNotCancelled(task: OrezTaskEntity) {
        if (get(task.id)?.status == "CANCELLED" && task.status != "CANCELLED") return
        upsert(task)
    }

    @Query("DELETE FROM orez_tasks WHERE status IN ('COMPLETED','DISPATCHED','FAILED','CANCELLED') AND updatedAt < :before")
    suspend fun pruneFinished(before: Long)
}

@Database(
    entities = [
        OrezMessageEntity::class,
        OrezConversationEntity::class,
        OrezTranslationEntity::class,
        OrezTaskEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class OrezRoomDatabase : RoomDatabase() {
    abstract fun messages(): OrezMessageDao
    abstract fun datasets(): OrezDatasetDao
    abstract fun tasks(): OrezTaskDao

    companion object {
        @Volatile private var INSTANCE: OrezRoomDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE orez_translations ADD COLUMN style TEXT NOT NULL DEFAULT 'natural'")
                database.execSQL("ALTER TABLE orez_translations ADD COLUMN scope TEXT NOT NULL DEFAULT 'global'")
                database.execSQL("DROP INDEX IF EXISTS index_orez_translations_source_targetLanguage")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_orez_translations_source_targetLanguage_style_scope " +
                        "ON orez_translations (source, targetLanguage, style, scope)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS orez_tasks (" +
                        "id TEXT NOT NULL, objective TEXT NOT NULL, status TEXT NOT NULL, " +
                        "planJson TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, lastError TEXT, PRIMARY KEY(id))"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_orez_tasks_status ON orez_tasks (status)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_orez_tasks_updatedAt ON orez_tasks (updatedAt)"
                )
            }
        }

        fun get(context: Context): OrezRoomDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                OrezRoomDatabase::class.java,
                "orez_v10.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
        }
    }

    /**
     * Replaces the corpus in bounded Room batches. Sequences keep the full 80k
     * corpus out of the heap while the transaction is being prepared.
     */
    suspend fun replaceDatasetsStreaming(
        conversations: Sequence<OrezConversationEntity>,
        translations: Sequence<OrezTranslationEntity>
    ) {
        withTransaction {
            datasets().clearConversations()
            datasets().clearTranslations()

            conversations.chunked(500).forEach { batch ->
                datasets().insertConversations(batch)
            }
            translations.chunked(500).forEach { batch ->
                datasets().insertTranslations(batch)
            }
        }
    }

    suspend fun replaceDatasets(
        conversations: List<OrezConversationEntity>,
        translations: List<OrezTranslationEntity>
    ) {
        replaceDatasetsStreaming(conversations.asSequence(), translations.asSequence())
    }
}
