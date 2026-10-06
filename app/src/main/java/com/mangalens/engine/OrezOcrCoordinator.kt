package com.mangalens.engine

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OrezOcrLine(val text:String,val bounds:RectF,val confidence:Float)
data class OrezBubbleLayout(val bounds:RectF,val text:String,val lines:List<String>,val fontScale:Float,val alignment:String)
data class OrezOcrResult(val blocks:List<ProcessedOcrText>,val bubbles:List<OrezBubbleLayout>,val sourceLanguage:LocalSourceLanguage)

class OrezOcrCoordinator(private val engine:AdvancedOcrTranslationEngine=AdvancedOcrTranslationEngine()){
    private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    suspend fun analyze(bitmap:Bitmap):OrezOcrResult{
        val result=recognize(bitmap)
        val blocks=result.textBlocks.flatMapIndexed{bi,block->block.lines.mapIndexed{li,line->
            OcrTextBlock(line.text,RectF(line.boundingBox?:android.graphics.Rect()),line.confidence?:0.7f,bi*1000+li)
        }}
        val processed=engine.preprocess(blocks)
        val groups=engine.groupDialogue(blocks)
        val bubbles=groups.map{group->
            val rect=RectF().also{r->group.forEach{r.union(it.bounds)}}
            val text=group.joinToString(" "){it.text}
            val normalized=engine.normalizeDialogue(text)
            OrezBubbleLayout(rect,normalized,engine.wrapForBubble(normalized,rect.width()),engine.recommendedFontScale(rect.width(),rect.height(),normalized.length),"CENTER")
        }
        return OrezOcrResult(processed,bubbles,processed.firstOrNull()?.sourceLanguage?:LocalSourceLanguage.UNKNOWN)
    }
    private suspend fun recognize(bitmap: Bitmap): Text = suspendCancellableCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            .addOnFailureListener { failure ->
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
    }
    fun close(){recognizer.close()}
}
