package com.mangalens.engine

import android.graphics.RectF
import java.text.Normalizer
import java.util.Locale

data class OcrTextBlock(val text:String,val bounds:RectF,val confidence:Float=1f,val index:Int=0)
data class ProcessedOcrText(val original:String,val normalized:String,val sourceLanguage:LocalSourceLanguage,val confidence:Float)
data class IntelligentTranslation(val source:String,val target:String,val language:String,val explanation:String?=null)

class AdvancedOcrTranslationEngine {
    private val hinglishMarkers=setOf("kya","kyun","kaise","hai","hain","tha","thi","hoon","mujhe","tum","aap","mera","meri","karna","karo","nahi","nahin","acha","achha","bas","abhi","phir","lekin","aur","yaar","bhai")
    fun preprocess(blocks:List<OcrTextBlock>):List<ProcessedOcrText> = blocks.sortedWith(compareBy<OcrTextBlock>{it.bounds.top}.thenBy{it.bounds.left}).map { b -> val n=normalize(b.text); ProcessedOcrText(b.text,n,detectLanguage(n),(b.confidence*confidenceAdjustment(n)).coerceIn(0f,1f)) }.filter { it.normalized.isNotBlank() }
    fun normalize(raw:String):String { var t=Normalizer.normalize(raw,Normalizer.Form.NFKC).replace(Regex("[\\u0000-\\u001F]+")," ").trim(); t=t.replace('—','-').replace('–','-').replace('“','"').replace('”','"').replace('’',Char(39)).replace(Regex("\\.{4,}"),"...").replace(Regex("!{2,}"),"!").replace(Regex("\\?{2,}"),"?").replace(Regex("\\s+")," ").trim(); return t.split(' ').joinToString(" "){when(it.lowercase(Locale.ROOT)){"im"->"I'm";"ive"->"I've";"dont"->"don't";"cant"->"can't";"wont"->"won't";"didnt"->"didn't";"isnt"->"isn't";"wasnt"->"wasn't";"thats"->"that's";else->it}}}
    fun reconstruct(blocks:List<ProcessedOcrText>)=blocks.joinToString(" "){it.normalized}.replace(Regex("\\s+([,.!?;:])"),"$1").trim()
    fun normalizeDialogue(text:String):String { val t=normalize(text); return if(t.isBlank()||t.last() in ".!?") t else "$t." }
    fun detectLanguage(text:String):LocalSourceLanguage { val l=text.lowercase(Locale.ROOT); return when {text.any{it in '\u3040'..'\u30ff'}->LocalSourceLanguage.JAPANESE;text.any{it in '\uac00'..'\ud7af'}->LocalSourceLanguage.KOREAN;text.any{it in '\u4e00'..'\u9fff'}->LocalSourceLanguage.CHINESE;text.any{it in '\u0900'..'\u097f'}->LocalSourceLanguage.HINDI;hinglishMarkers.count{Regex("\\b"+Regex.escape(it)+"\\b").containsMatchIn(l)}>=2->LocalSourceLanguage.HINDI;Regex("\\b(el|la|los|las|que|una|por|para)\\b").containsMatchIn(l)->LocalSourceLanguage.SPANISH;Regex("\\b(le|les|des|une|avec|pour|est)\\b").containsMatchIn(l)->LocalSourceLanguage.FRENCH;l.any(Char::isLetter)->LocalSourceLanguage.ENGLISH;else->LocalSourceLanguage.UNKNOWN}}
    fun containsHinglish(text:String)=text.lowercase(Locale.ROOT).split(Regex("\\W+")).count{it in hinglishMarkers}>0
    fun translateWithDictionary(source:String,targetLanguage:String,dictionary:Map<String,String>):IntelligentTranslation { val n=normalize(source); val direct=dictionary[n.lowercase(Locale.ROOT)]; val out=direct?:n.split(' ').joinToString(" "){dictionary[it.lowercase(Locale.ROOT)]?:it}; return IntelligentTranslation(n,out,targetLanguage,if(containsHinglish(n))"Hinglish detected: mixed Hindi vocabulary and English-style sentence structure." else null)}
    fun groupDialogue(blocks:List<OcrTextBlock>):List<List<OcrTextBlock>> { val s=blocks.sortedWith(compareBy<OcrTextBlock>{it.bounds.top}.thenBy{it.bounds.left}); val g=mutableListOf<MutableList<OcrTextBlock>>(); for(b in s){val c=g.lastOrNull();if(c==null||b.bounds.top-c.last().bounds.bottom>maxOf(b.bounds.height(),c.last().bounds.height())*.8f)g+=mutableListOf(b)else c+=b};return g}
    fun wrapForBubble(text:String,widthPx:Float):List<String>{
        val words=normalize(text).split(" ").filter{it.isNotBlank()}
        val maxChars=(widthPx/18f).toInt().coerceIn(8,42)
        val lines=mutableListOf<String>();var line=""
        for(word in words){
            val candidate=if(line.isBlank())word else line+" "+word
            if(candidate.length>maxChars&&line.isNotBlank()){lines+=line;line=word}else line=candidate
        }
        if(line.isNotBlank())lines+=line
        return lines.ifEmpty{listOf("")}
    }
    fun recommendedFontScale(widthPx:Float,heightPx:Float,characterCount:Int):Float{
        val area=(widthPx*heightPx).coerceAtLeast(1f)
        val density=(area/((characterCount.coerceAtLeast(1))*900f)).coerceIn(.55f,1.35f)
        return density
    }
    fun mergeAdjacentLines(blocks:List<OcrTextBlock>):List<OcrTextBlock>{
        val ordered=blocks.sortedWith(compareBy<OcrTextBlock>{it.bounds.top}.thenBy{it.bounds.left})
        val out=mutableListOf<OcrTextBlock>()
        for(block in ordered){
            val previous=out.lastOrNull()
            val height = previous?.bounds?.height()?.coerceAtLeast(1f) ?: 1f
            val gap = if (previous != null) block.bounds.left - previous.bounds.right else Float.MAX_VALUE
            if(previous!=null && kotlin.math.abs(block.bounds.top-previous.bounds.top)<height*.65f &&
                block.bounds.left >= previous.bounds.left && gap >= -height*.25f && gap < height*2.5f){
                out[out.lastIndex] = previous.copy(
                    text = previous.text.trimEnd() + " " + block.text.trimStart(),
                    bounds = RectF(previous.bounds).apply { union(block.bounds) },
                    confidence = minOf(previous.confidence, block.confidence)
                )
            }else out+=OcrTextBlock(block.text,RectF(block.bounds),block.confidence,block.index)
        }
        return out
    }
    fun repairCommonOcrErrors(text:String):String{
        var value=normalize(text)
        val replacements=mapOf(
            " rn " to " m ","cl " to "d ","0ne" to "one","1ife" to "life",
            "l've" to "I've","l'm" to "I'm","dont" to "don't","cant" to "can't",
            "wont" to "won't","teh" to "the","thls" to "this"
        )
        replacements.forEach{(a,b)->value=value.replace(a,b,true)}
        return value
    }
    private fun confidenceAdjustment(t:String)=when{t.length<2->.55f;t.count(Char::isLetter)<t.length*.35->.75f;else->1f}
}

