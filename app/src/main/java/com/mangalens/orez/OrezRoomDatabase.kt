package com.mangalens.orez

import android.content.Context
import androidx.room.*
import androidx.room.withTransaction
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

@Entity(tableName = "orez_translations", indices = [Index(value = ["source", "targetLanguage"], unique = true), Index("source")])
data class OrezTranslationEntity(
    @PrimaryKey val key: String,
    val source: String,
    val target: String,
    val targetLanguage: String
)

@Dao
interface OrezMessageDao {
    @Query("SELECT * FROM orez_messages ORDER BY createdAt ASC LIMIT 200")
    fun observe(): Flow<List<OrezMessageEntity>>
    @Insert suspend fun insert(message: OrezMessageEntity)
    @Query("SELECT COUNT(*) FROM orez_messages") suspend fun count(): Int
    @Query("DELETE FROM orez_messages") suspend fun clear()
}

@Dao
interface OrezDatasetDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversations(items: List<OrezConversationEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTranslations(items: List<OrezTranslationEntity>)
    @Query("SELECT * FROM orez_conversations WHERE prompt = :prompt COLLATE NOCASE LIMIT 1")
    suspend fun exactConversation(prompt: String): OrezConversationEntity?
    @Query("SELECT * FROM orez_conversations WHERE prompt LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY length(prompt) ASC LIMIT :limit")
    suspend fun searchConversations(query: String, limit: Int): List<OrezConversationEntity>
    @Query("SELECT target FROM orez_translations WHERE source = :source COLLATE NOCASE AND targetLanguage = :targetLanguage COLLATE NOCASE LIMIT 1")
    suspend fun exactTranslation(source: String, targetLanguage: String): String?
    @Query("SELECT target FROM orez_translations WHERE source LIKE '%' || :source || '%' COLLATE NOCASE AND targetLanguage = :targetLanguage COLLATE NOCASE ORDER BY length(source) ASC LIMIT :limit")
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
    version = 1,
    exportSchema = false
)
abstract class OrezRoomDatabase : RoomDatabase() {
    abstract fun messages(): OrezMessageDao
    abstract fun datasets(): OrezDatasetDao

    companion object {
        @Volatile private var INSTANCE: OrezRoomDatabase? = null

        fun get(context: Context): OrezRoomDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                OrezRoomDatabase::class.java,
                "orez_v10.db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
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
