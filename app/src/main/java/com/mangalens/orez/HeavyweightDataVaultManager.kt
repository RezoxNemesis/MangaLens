package com.mangalens.orez

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.security.MessageDigest

@Entity(
    tableName = "heavy_knowledge",
    indices = [Index(value = ["domain"]), Index(value = ["normalizedPrompt"])]
)
data class HeavyKnowledgeEntity(
    @PrimaryKey val key: String,
    val domain: String,
    val prompt: String,
    val normalizedPrompt: String,
    val response: String
)

@Entity(
    tableName = "heavy_translation",
    indices = [
        Index(value = ["source", "targetLanguage"], unique = true),
        Index(value = ["targetLanguage"])
    ]
)
data class HeavyTranslationEntity(
    @PrimaryKey val key: String,
    val source: String,
    val target: String,
    val targetLanguage: String
)

@Entity(tableName = "heavy_regex", indices = [Index(value = ["kind"])])
data class HeavyRegexEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val pattern: String,
    val replacement: String
)

@Dao
interface HeavyKnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(items: List<HeavyKnowledgeEntity>)

    @Query("SELECT * FROM heavy_knowledge WHERE normalizedPrompt LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<HeavyKnowledgeEntity>

    @Query("SELECT COUNT(*) FROM heavy_knowledge")
    suspend fun count(): Long
}

@Dao
interface HeavyTranslationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(items: List<HeavyTranslationEntity>)

    @Query("SELECT target FROM heavy_translation WHERE source = :source AND targetLanguage = :language LIMIT 1")
    suspend fun exact(source: String, language: String): String?

    @Query("SELECT target FROM heavy_translation WHERE source LIKE '%' || :source || '%' AND targetLanguage = :language LIMIT :limit")
    suspend fun search(source: String, language: String, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM heavy_translation")
    suspend fun count(): Long
}

@Dao
interface HeavyRegexDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(items: List<HeavyRegexEntity>)

    @Query("SELECT * FROM heavy_regex WHERE kind = :kind")
    suspend fun byKind(kind: String): List<HeavyRegexEntity>

    @Query("SELECT COUNT(*) FROM heavy_regex")
    suspend fun count(): Long
}

@Database(
    entities = [HeavyKnowledgeEntity::class, HeavyTranslationEntity::class, HeavyRegexEntity::class],
    version = 1,
    exportSchema = false
)
abstract class HeavyweightDataVaultDatabase : RoomDatabase() {
    abstract fun knowledge(): HeavyKnowledgeDao
    abstract fun translations(): HeavyTranslationDao
    abstract fun regex(): HeavyRegexDao

    companion object {
        @Volatile private var instance: HeavyweightDataVaultDatabase? = null

        fun get(context: Context): HeavyweightDataVaultDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HeavyweightDataVaultDatabase::class.java,
                    "orez_heavy_master.db"
                )
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addCallback(object : Callback() {
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL("PRAGMA synchronous=NORMAL")
                            db.execSQL("PRAGMA temp_store=MEMORY")
                            db.execSQL("PRAGMA cache_size=-32768")
                            db.execSQL("PRAGMA foreign_keys=ON")
                        }
                    })
                    .build()
                    .also { instance = it }
            }
    }
}

data class HeavyVaultStats(
    val knowledgeRows: Long,
    val translationRows: Long,
    val regexRows: Long,
    val databaseBytes: Long
) {
    val totalRows: Long get() = knowledgeRows + translationRows + regexRows
}

class HeavyweightDataVaultManager(private val context: Context) {
    private val db = HeavyweightDataVaultDatabase.get(context)

    suspend fun installBundledPacks(): HeavyVaultStats = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("orez_heavy_bootstrap", Context.MODE_PRIVATE)
        if (prefs.getInt("version", 0) == BUNDLED_VERSION) return@withContext stats()
        importJsonlDirectory("orez/heavy/knowledge", "knowledge")
        importJsonlDirectory("orez/heavy/translations", "translations")
        importJsonlDirectory("orez/heavy/regex", "regex")
        importJsonlDirectory("orez", "knowledge", "conversations_")
        importJsonlDirectory("orez", "translations", "translations_")
        prewarmIndexes()
        prefs.edit().putInt("version", BUNDLED_VERSION).apply()
        stats()
    }

    suspend fun importExternalJsonl(file: java.io.File): HeavyVaultStats = withContext(Dispatchers.IO) {
        require(file.exists()) { "Resource pack does not exist." }
        val buffered = file.inputStream().buffered(64 * 1024)
        buffered.mark(4)
        val header = ByteArray(4)
        val read = buffered.read(header)
        buffered.reset()
        if (read >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            buffered.use { importExternalStream(it, file.name) }
        } else {
            buffered.close()
            openJsonlReader(file).use { importExternalReader(it) }
        }
    }

    suspend fun importExternalStream(input: java.io.InputStream, fileName: String): HeavyVaultStats = withContext(Dispatchers.IO) {
        val buffered = java.io.PushbackInputStream(input.buffered(64 * 1024), 4)
        val header = ByteArray(4)
        val read = buffered.read(header)
        if (read > 0) buffered.unread(header, 0, read)
        when {
            read >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() -> {
                java.util.zip.ZipInputStream(buffered).use { zip ->
                    var imported = false
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && (entry.name.endsWith(".jsonl", true) || entry.name.endsWith(".jsonl.gz", true) || entry.name.endsWith(".ndjson", true))) {
                            val source: java.io.InputStream = if (entry.name.endsWith(".gz", true)) GZIPInputStream(zip, 64 * 1024) else zip
                            val reader = BufferedReader(InputStreamReader(source, Charsets.UTF_8), 64 * 1024)
                            importExternalReader(reader)
                            imported = true
                            break
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    check(imported) { "ZIP pack contains no .jsonl or .jsonl.gz data file. Select the corpus data file, not a source-code archive." }
                }
                stats()
            }
            read >= 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte() -> {
                val reader = BufferedReader(InputStreamReader(GZIPInputStream(buffered, 64 * 1024), Charsets.UTF_8), 64 * 1024)
                importExternalReader(reader)
            }
            else -> {
                val reader = BufferedReader(InputStreamReader(buffered, Charsets.UTF_8), 64 * 1024)
                importExternalReader(reader)
            }
        }
    }

    private suspend fun importExternalReader(reader: BufferedReader): HeavyVaultStats {
        val knowledge = ArrayList<HeavyKnowledgeEntity>(1000)
        val translations = ArrayList<HeavyTranslationEntity>(1000)
        val regex = ArrayList<HeavyRegexEntity>(1000)
        var importedRecords = 0
        while (importedRecords < MAX_EXTERNAL_RECORDS) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            var accepted = false
            when (obj.optString("type").lowercase()) {
                "translation" -> {
                    val source = obj.optString("source").trim()
                    val target = obj.optString("target").trim()
                    val language = obj.optString("targetLanguage", "hi").trim()
                    if (source.isNotBlank() && target.isNotBlank()) {
                        translations += HeavyTranslationEntity(
                            obj.optString("key").ifBlank { stableKey(source + "|" + language) },
                            source, target, language
                        )
                        accepted = true
                    }
                }
                "regex" -> {
                    val pattern = obj.optString("pattern")
                    if (pattern.isNotBlank()) {
                        regex += HeavyRegexEntity(
                            obj.optString("key").ifBlank { stableKey(pattern) },
                            obj.optString("kind", "ocr"), pattern, obj.optString("replacement")
                        )
                        accepted = true
                    }
                }
                else -> {
                    val prompt = obj.optString("prompt").trim()
                    val response = obj.optString("response").trim()
                    if (prompt.isNotBlank() && response.isNotBlank()) {
                        knowledge += HeavyKnowledgeEntity(
                            obj.optString("key").ifBlank { stableKey(prompt) },
                            obj.optString("domain", "general"),
                            prompt, normalize(prompt), response
                        )
                        accepted = true
                    }
                }
            }
            if (accepted) importedRecords++
            if (knowledge.size >= 1000) { db.knowledge().insert(knowledge.toList()); knowledge.clear() }
            if (translations.size >= 1000) { db.translations().insert(translations.toList()); translations.clear() }
            if (regex.size >= 1000) { db.regex().insert(regex.toList()); regex.clear() }
        }
        if (knowledge.isNotEmpty()) db.knowledge().insert(knowledge)
        if (translations.isNotEmpty()) db.translations().insert(translations)
        if (regex.isNotEmpty()) db.regex().insert(regex)
        check(importedRecords > 0) { "No usable OREZ records were found. Choose a JSONL or JSONL.GZ corpus pack." }
        prewarmIndexes()
        return stats()
    }

    private fun openJsonlReader(file: java.io.File): BufferedReader {
        val buffered = file.inputStream().buffered(64 * 1024)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val decoded = if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
        return BufferedReader(InputStreamReader(decoded, Charsets.UTF_8), 64 * 1024)
    }

    suspend fun prewarmIndexes() = withContext(Dispatchers.IO) {
        db.knowledge().count()
        db.translations().count()
        db.regex().count()
    }

    suspend fun stats(): HeavyVaultStats = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath("orez_heavy_master.db")
        HeavyVaultStats(
            db.knowledge().count(),
            db.translations().count(),
            db.regex().count(),
            dbFile.takeIf { it.exists() }?.length() ?: 0L
        )
    }

    suspend fun searchKnowledge(query: String, limit: Int = 12): List<HeavyKnowledgeEntity> =
        withContext(Dispatchers.IO) {
            db.knowledge().search(normalize(query), limit.coerceIn(1, 100))
        }

    suspend fun exactTranslation(source: String, language: String): String? =
        withContext(Dispatchers.IO) {
            db.translations().exact(source.trim(), language.trim())
        }

    suspend fun regexRules(kind: String): List<HeavyRegexEntity> =
        withContext(Dispatchers.IO) { db.regex().byKind(kind) }

    private suspend fun importJsonlDirectory(
        directory: String,
        kind: String,
        prefix: String? = null
    ) {
        val names = runCatching { context.assets.list(directory).orEmpty().toList() }
            .getOrDefault(emptyList())
            .filter { prefix == null || it.startsWith(prefix) }
        for (name in names.sorted()) {
            if (!name.endsWith(".jsonl", true)) continue
            context.assets.open(directory + "/" + name).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024).use { reader ->
                    when (kind) {
                        "knowledge" -> importKnowledge(reader)
                        "translations" -> importTranslations(reader)
                        "regex" -> importRegex(reader)
                    }
                }
            }
        }
    }

    private suspend fun importKnowledge(reader: BufferedReader) {
        val batch = ArrayList<HeavyKnowledgeEntity>(1000)
        while (true) {
            val line = reader.readLine() ?: break
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val prompt = obj.optString("prompt").trim()
            val response = obj.optString("response").trim()
            if (prompt.isBlank() || response.isBlank()) continue
            batch += HeavyKnowledgeEntity(
                obj.optString("key").ifBlank { stableKey(prompt) },
                obj.optString("domain", "general"),
                prompt,
                normalize(prompt),
                response
            )
            if (batch.size >= 1000) {
                db.knowledge().insert(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) db.knowledge().insert(batch)
    }

    private suspend fun importTranslations(reader: BufferedReader) {
        val batch = ArrayList<HeavyTranslationEntity>(1000)
        while (true) {
            val line = reader.readLine() ?: break
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val source = obj.optString("source").trim()
            val target = obj.optString("target").trim()
            val language = obj.optString("targetLanguage", "hi").trim()
            if (source.isBlank() || target.isBlank()) continue
            batch += HeavyTranslationEntity(
                obj.optString("key").ifBlank { stableKey(source + "|" + language) },
                source,
                target,
                language
            )
            if (batch.size >= 1000) {
                db.translations().insert(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) db.translations().insert(batch)
    }

    private suspend fun importRegex(reader: BufferedReader) {
        val batch = ArrayList<HeavyRegexEntity>(1000)
        while (true) {
            val line = reader.readLine() ?: break
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val pattern = obj.optString("pattern")
            if (pattern.isBlank()) continue
            batch += HeavyRegexEntity(
                obj.optString("key").ifBlank { stableKey(pattern + obj.optString("kind")) },
                obj.optString("kind", "ocr"),
                pattern,
                obj.optString("replacement")
            )
            if (batch.size >= 1000) {
                db.regex().insert(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) db.regex().insert(batch)
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun stableKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BUNDLED_VERSION = 2
        private const val MAX_EXTERNAL_RECORDS = 100_000
    }
}
