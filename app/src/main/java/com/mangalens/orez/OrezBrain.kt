package com.mangalens.orez

import com.mangalens.engine.LiveSearchAnswer
import com.mangalens.core.orez.OrezDomain
import com.mangalens.core.orez.OrezIntentRouterV12
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

enum class OrezIntent { GREETING, QUESTION, ADVICE, REASONING, PLANNING, TRANSLATION, TROUBLESHOOTING, MANGA, VIDEO, WEB_SEARCH, COMMAND, MATH, GENERAL }
data class OrezContext(val recentMessages:List<OrezMessageEntity>,val targetLanguage:String="hi",val sourceText:String?=null)
data class OrezBrainResponse(val text:String,val intent:OrezIntent,val sources:List<String> = emptyList(),val usedLocalKnowledge:Boolean=false,val usedLiveSearch:Boolean=false)

class OrezBrain(private val database:OrezRoomDatabase, private val context: android.content.Context, private val liveSearch:suspend(String)->LiveSearchAnswer){
    private val v12Router = OrezIntentRouterV12()
    private val modelManager = OrezModelManager(context)
    private val localModel = OrezLocalModelService(modelManager)
    private val heavyVault = HeavyweightDataVaultManager(context)
    suspend fun answer(input:String,context:OrezContext)=withContext(Dispatchers.Default){
        val clean=input.trim()
        if(clean.isBlank()) return@withContext OrezBrainResponse("Please tell me what you want to do.",OrezIntent.GENERAL)
        val intent=classify(clean)
        if(intent==OrezIntent.MATH) return@withContext OrezBrainResponse(solveMath(clean),intent)

        val contextualQuery=buildContextualQuery(clean,context)
        if(intent==OrezIntent.TRANSLATION){
            val result=retrieveTranslation(clean,context.targetLanguage)
            if(result!=null) return@withContext OrezBrainResponse(result,intent,usedLocalKnowledge=true)
            val modelAnswer=localModel.answer(
                "Translate this text to " + context.targetLanguage + ". Return only the translated text. Text: " + clean,
                context.recentMessages
            )
            if(modelAnswer!=null) return@withContext OrezBrainResponse(modelAnswer,intent,usedLocalKnowledge=true)
            return@withContext OrezBrainResponse("Use the Translate button to run the on-device OCR/ML translation pipeline.",intent)
        }

        val local=retrieveConversation(contextualQuery)
        val heavy=if(intent!=OrezIntent.WEB_SEARCH) retrieveHeavyKnowledge(contextualQuery) else null
        val evidence=buildEvidence(local,heavy)
        if(intent!=OrezIntent.WEB_SEARCH){
            val modelPrompt=if(evidence.isBlank()) clean else "Use the following local OREZ knowledge as evidence. Do not copy it blindly; answer naturally and directly.\n\nLOCAL KNOWLEDGE:\n$evidence\n\nUSER REQUEST:\n$clean"
            val modelAnswer=localModel.answer(modelPrompt, context.recentMessages)
            if(modelAnswer!=null) return@withContext OrezBrainResponse(modelAnswer,intent,usedLocalKnowledge=evidence.isNotBlank())
            if(evidence.isNotBlank()) return@withContext OrezBrainResponse(sanitizeLocal(composeLocal(evidence,intent)),intent,usedLocalKnowledge=true)
        }
        if(intent==OrezIntent.WEB_SEARCH || intent in setOf(OrezIntent.QUESTION, OrezIntent.TROUBLESHOOTING)){
            val live=runCatching{liveSearch(clean)}.getOrNull()
            if(live!=null && live.results.isNotEmpty()){
                val webPrompt="Answer the user request using this current public-information summary. Keep the answer natural, useful and conversational.\n\nCURRENT INFORMATION:\n"+live.summary+"\n\nUSER REQUEST:\n"+clean
                val modelAnswer=localModel.answer(webPrompt,context.recentMessages)
                if(modelAnswer!=null) return@withContext OrezBrainResponse(modelAnswer,intent,live.results.map{it.url},usedLiveSearch=true)
                return@withContext OrezBrainResponse("Current public information found for: $clean\n\n"+live.summary,intent,live.results.map{it.url},usedLiveSearch=true)
            }
        }
        OrezBrainResponse(fallback(intent),intent)
    }

    private suspend fun retrieveConversation(query:String):OrezConversationEntity?{
        val tokens=keywords(query)
        if(tokens.isEmpty()) return database.datasets().searchConversations(query,5).firstOrNull()
        val candidates=LinkedHashMap<String,OrezConversationEntity>()
        for(token in tokens.take(8)) database.datasets().searchConversations(token,8).forEach{candidates[it.key]=it}
        if(candidates.isEmpty()) return null
        return candidates.values.maxByOrNull{candidate->
            val promptTokens=keywords(candidate.prompt).toSet()
            val overlap=tokens.count{it in promptTokens}
            overlap*100-max(0,candidate.prompt.length-query.length).coerceAtMost(80)
        }
    }

    private suspend fun retrieveHeavyKnowledge(query:String):HeavyKnowledgeEntity? {
        val tokens=keywords(query)
        val candidates=tokens.take(10).flatMap { heavyVault.searchKnowledge(it,8) }
        if(candidates.isEmpty()) return null
        return candidates.distinctBy { it.key }.maxByOrNull { candidate ->
            val promptTokens=keywords(candidate.prompt).toSet()
            tokens.count { it in promptTokens } * 100 - kotlin.math.abs(candidate.prompt.length-query.length).coerceAtMost(120)
        }
    }

    private suspend fun retrieveTranslation(source:String,targetLanguage:String):String?{
        heavyVault.exactTranslation(source,targetLanguage)?.let{return it}
        database.datasets().exactTranslation(source,targetLanguage)?.let{return it}
        for(token in keywords(source).take(6)){
            val hits=database.datasets().searchTranslations(token,targetLanguage,8)
            if(hits.isNotEmpty()) return hits.first()
        }
        return null
    }

    private fun buildEvidence(local: OrezConversationEntity?, heavy: HeavyKnowledgeEntity?): String {
        val parts = mutableListOf<String>()
        if (local != null) parts += "Conversation example: ${local.prompt}\nAssistant response: ${local.response}"
        if (heavy != null) parts += "Knowledge reference: ${heavy.prompt}\nReference response: ${heavy.response}"
        return parts.joinToString("\n\n").take(9000)
    }
    private fun buildContextualQuery(input:String,context:OrezContext):String{
        val recent=context.recentMessages.takeLast(4).joinToString(" "){it.text}
        return if(recent.isBlank()) input else "$recent $input"
    }

    private fun keywords(text:String):List<String>{
        val stop=setOf("the","a","an","is","are","am","to","of","and","or","for","in","on","with","my","me","i","you","please","can","how","what","why","this","that","hai","mujhe","main","ka","ki","ke","ko","kya","aur","se")
        return Regex("[\\p{L}\\p{N}]{3,}").findAll(text.lowercase(Locale.ROOT)).map{it.value}.filter{it !in stop}.distinct().toList()
    }

    private fun classify(s:String):OrezIntent {
        return when (v12Router.classify(s).domain) {
            OrezDomain.GREETING -> OrezIntent.GREETING
            OrezDomain.TRANSLATION -> OrezIntent.TRANSLATION
            OrezDomain.MANGA -> OrezIntent.MANGA
            OrezDomain.VIDEO -> OrezIntent.VIDEO
            OrezDomain.WEB -> OrezIntent.WEB_SEARCH
            OrezDomain.TROUBLESHOOTING -> OrezIntent.TROUBLESHOOTING
            OrezDomain.PLANNING -> OrezIntent.PLANNING
            OrezDomain.REASONING -> OrezIntent.REASONING
            OrezDomain.ADVICE -> OrezIntent.ADVICE
            OrezDomain.MATH -> OrezIntent.MATH
            OrezDomain.GENERAL -> OrezIntent.QUESTION
        }
    }

    private fun sanitizeLocal(response:String):String {
        var value=response.replace(Regex("(?i)https?://\\S+"), "")
            .replace(Regex("\\n{3,}"), "\\n\\n").trim()
        val leakage=listOf(
            "normalize spacing", "treat a missed day as data",
            "no local example matched", "system prompt", "developer message",
            "assistant instructions", "internal policy"
        )
        leakage.forEach { phrase ->
            value=value.replace(Regex("(?i)\\b"+Regex.escape(phrase)+"\\b[.: -]*"), "")
        }
        return value.replace(Regex("\\n{3,}"), "\\n\\n").trim()
    }

    private fun composeLocal(response:String,intent:OrezIntent):String=when(intent){
        OrezIntent.ADVICE->"Practical guidance:\n$response"
        OrezIntent.REASONING->"Reasoning:\n$response"
        OrezIntent.PLANNING->"Plan:\n$response"
        else->response
    }

    private fun solveMath(s:String):String{
        val m=Regex("(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/x×÷])\\s*(-?\\d+(?:\\.\\d+)?)").find(s)?:return "I couldn't parse that calculation."
        val a=m.groupValues[1].toDouble()
        val b=m.groupValues[3].toDouble()
        val r=when(m.groupValues[2]){
            "+"->a+b
            "-"->a-b
            "*","x","×"->a*b
            "/","÷"->if(b==0.0)return "Division by zero is undefined." else a/b
            else->return "Unsupported operation."
        }
        return "Answer: "+if(r%1.0==0.0)r.toLong().toString() else String.format(Locale.US,"%.8f",r).trimEnd('0').trimEnd('.')
    }

    fun close() = localModel.close()

    private fun fallback(i:OrezIntent)=when(i){
        OrezIntent.GREETING->"Namaste! Main OREZ hoon. Batao kya karna hai."
        OrezIntent.ADVICE->"Main problem ko goal, constraints aur practical next steps mein tod sakta hoon."
        OrezIntent.REASONING->"Main assumptions, evidence, alternatives aur conclusion ko alag karke reasoning kar sakta hoon."
        OrezIntent.PLANNING->"Main request ko goals, priorities, time blocks aur checkpoints mein convert kar sakta hoon."
        OrezIntent.MANGA->"OREZ can coordinate OCR, dialogue grouping, bubble reconstruction and translation."
        OrezIntent.VIDEO->"OREZ can coordinate subtitle extraction, translation and the immersive Media3 player."
        OrezIntent.TROUBLESHOOTING->"Tell me the exact symptom; I will narrow it down to likely causes and safe fixes."
        OrezIntent.WEB_SEARCH->"Live search returned no usable public result. Local OREZ knowledge remains available."
        else->"OREZ searches local knowledge first and uses live information when the request requires current data."
    }
}
