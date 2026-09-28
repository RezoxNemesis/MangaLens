package com.mangalens.orez

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject

data class OrezPackExample(val prompt:String,val response:String,val language:String,val domain:String)

class OrezConversationPackStore(context: Context) {
    private val root=File(context.filesDir,"orez/conversation-pack")
    private val index=File(root,"retrieval-index.jsonl.gz")

    suspend fun search(query: String, limit: Int = 3): List<OrezPackExample> = withContext(Dispatchers.IO) {
        if(query.isBlank()||!index.isFile)return@withContext emptyList()
        val terms=query.lowercase().split(Regex("[^\p{L}\p{N}]+")).filter{it.length>=3}.toSet()
        if(terms.isEmpty())return@withContext emptyList()
        val scored=mutableListOf<Pair<Int,OrezPackExample>>()
        GZIPInputStream(index.inputStream(),64*1024).bufferedReader().useLines{lines->
            lines.forEach{line->
                runCatching{
                    val o=JSONObject(line);val p=o.optString("prompt");val r=o.optString("response")
                    val hay=(p+" "+o.optString("domain")).lowercase()
                    val score=terms.count{hay.contains(it)}
                    if(score>0)scored+=score to OrezPackExample(p,r,o.optString("language","en"),o.optString("domain","general"))
                }
            }
        }
        scored.sortedByDescending{it.first}.take(limit).map{it.second}
    }

    fun isInstalled(): Boolean = index.isFile
    fun rootDirectory(): File = root
}
