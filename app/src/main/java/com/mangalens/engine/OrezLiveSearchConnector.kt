package com.mangalens.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

data class OrezSearchResult(val title:String,val snippet:String,val url:String)
data class LiveSearchAnswer(val query:String,val results:List<OrezSearchResult>,val summary:String)

class OrezLiveSearchConnector(private val client:OkHttpClient=OkHttpClient()){
    suspend fun search(query:String,limit:Int=8):LiveSearchAnswer=withContext(Dispatchers.IO){
        val encoded=URLEncoder.encode(query,"UTF-8")
        val request=Request.Builder().url("https://html.duckduckgo.com/html/?q="+encoded).header("User-Agent","Mozilla/5.0 (Android) MangaLens").build()
        val html=runCatching{client.newCall(request).execute().use{if(it.isSuccessful)it.body?.string().orEmpty() else ""}}.getOrDefault("")
        val results=Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",RegexOption.IGNORE_CASE).findAll(html).take(limit).map{
            OrezSearchResult(clean(it.groupValues[2]),clean(it.groupValues[2]),it.groupValues[1])
        }.toList()
        val summary=results.joinToString("\n\n"){ "• "+it.title+"\n"+it.snippet+"\nSource: "+it.url }
        LiveSearchAnswer(query,results,if(summary.isBlank())"No public search results were returned." else summary)
    }
    suspend fun fetch(url:String,maxChars:Int=12000):String=withContext(Dispatchers.IO){
        if(!url.startsWith("http://")&&!url.startsWith("https://"))return@withContext ""
        val request=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Android) MangaLens").build()
        runCatching{client.newCall(request).execute().use{r->
            if(!r.isSuccessful)"" else r.body?.string().orEmpty().replace(Regex("<script[\\s\\S]*?</script>")," ").replace(Regex("<style[\\s\\S]*?</style>")," ").replace(Regex("<[^>]+>")," ").replace(Regex("\\s+")," ").trim().take(maxChars)
        }}.getOrDefault("")
    }
    private fun clean(s:String)=s.replace(Regex("<[^>]+>")," ").replace("&amp;","&").replace("&quot;","\"").replace("&#x27;","'").replace(Regex("\\s+")," ").trim()
}
