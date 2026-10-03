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

@Dao
interface OrezMessageDao {
    @Query("SELECT * FROM (SELECT * FROM orez_messages ORDER BY id DESC LIMIT 200) ORDER BY id ASC")
    fun observe(): Flow<List<OrezMessageEntity>>
    @Insert suspend fun insert(message: OrezMessageEntity)
    @Query("SELECT COUNT(*) FROM orez_messages") suspend fun count(): Int
    @Query("DELETE FROM orez_messages WHERE id NOT IN (SELECT id FROM orez_messages ORDER BY id DESC LIMIT 200)")
    suspend fun trimHistory()
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

@Database(
    entities = [OrezMessageEntity::class, OrezConversationEntity::class, OrezTranslationEntity::class],
    version = 2,
    exportSchema = false
)
abstract class OrezRoomDatabase : RoomDatabase() {
    abstract fun messages(): OrezMessageDao
    abstract fun datasets(): OrezDatasetDao

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

        fun get(context: Context): OrezRoomDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                OrezRoomDatabase::class.java,
                "orez_v10.db"
            ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
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
