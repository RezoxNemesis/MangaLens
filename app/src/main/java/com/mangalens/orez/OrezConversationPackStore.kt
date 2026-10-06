package com.mangalens.orez

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream
import org.json.JSONObject

data class OrezPackExample(val prompt: String, val response: String, val language: String, val domain: String)

class OrezConversationPackStore(context: Context) {
    private val root = File(context.filesDir, "orez/conversation-pack")
    private val index = File(root, "retrieval-index.jsonl.gz")
    private val cache = object : LinkedHashMap<String, List<OrezPackExample>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OrezPackExample>>) = size > 16
    }

    /** Bounded working memory and scan time; a large compressed pack must not stall replies. */
    suspend fun search(query: String, limit: Int = 3): List<OrezPackExample> = withContext(Dispatchers.IO) {
        if (query.isBlank() || !index.isFile || limit <= 0) return@withContext emptyList()
        val terms = query.take(4000).lowercase().split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length >= 3 }.distinct().take(32)
        if (terms.isEmpty()) return@withContext emptyList()
        val key = "${index.lastModified()}:${index.length()}:$limit:${terms.joinToString("|")}"
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        val context = currentCoroutineContext()
        val deadline = System.nanoTime() + 1_500_000_000L
        val top = PriorityQueue<Pair<Int, OrezPackExample>>(compareBy { it.first })
        GZIPInputStream(index.inputStream(), 64 * 1024).bufferedReader().use { reader ->
            while (System.nanoTime() < deadline) {
                context.ensureActive()
                // readLine alone can allocate an arbitrarily large malformed record.
                val line = StringBuilder()
                var oversized = false
                var eof = false
                while (true) {
                    val c = reader.read()
                    if (c == -1) { eof = true; break }
                    if (c == 10) break
                    if (line.length < 32_768) line.append(c.toChar()) else oversized = true
                    if (oversized && System.nanoTime() >= deadline) break
                    if (line.length % 4096 == 0) context.ensureActive()
                }
                if (!oversized && line.isNotEmpty()) {
                    runCatching {
                        val o = JSONObject(line.toString())
                        val prompt = o.optString("prompt").take(1200)
                        val hay = (prompt + " " + o.optString("domain")).lowercase()
                        val score = terms.count { hay.contains(it) }
                        if (score > 0 && (top.size < limit.coerceAtMost(10) || score > (top.peek()?.first ?: -1))) {
                            top.add(score to OrezPackExample(prompt, o.optString("response").take(1800),
                                o.optString("language", "en").take(20), o.optString("domain", "general").take(100)))
                            if (top.size > limit.coerceAtMost(10)) top.poll()
                        }
                    }
                }
                if (eof) break
            }
        }
        context.ensureActive()
        top.sortedByDescending { it.first }.map { it.second }.also { synchronized(cache) { cache[key] = it } }
    }
    fun isInstalled(): Boolean = index.isFile
    fun rootDirectory(): File = root
}
