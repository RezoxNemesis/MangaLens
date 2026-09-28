package com.mangalens.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LiveSubtitleCue(val text:String,val translatedText:String,val startMs:Long,val endMs:Long,val sourceLanguage:String)

@UnstableApi
class LiveSubtitleEngine(private val context:Context,private val translate:suspend(String,String)->String) {
    private val _cues=MutableStateFlow<List<LiveSubtitleCue>>(emptyList())
    val cues:StateFlow<List<LiveSubtitleCue>> = _cues
    private var recognizer:SpeechRecognizer?=null
    private var listening=false
    private var target="hi"
    private var player:ExoPlayer?=null
    fun attach(exo:ExoPlayer,targetLanguage:String="hi"){player=exo;target=targetLanguage}
    fun start(){
        if(listening||!SpeechRecognizer.isRecognitionAvailable(context))return
        recognizer=SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object:RecognitionListener{
            override fun onResults(results:Bundle){
                val text=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if(text.isNotBlank()){
                    val start=player?.currentPosition?:0L
                    val base=LiveSubtitleCue(text,text,start,start+3000L,"auto")
                    _cues.value=(_cues.value+base).takeLast(40)
                    CoroutineScope(Dispatchers.Main.immediate).launch{
                        val translated=runCatching{translate(text,target)}.getOrDefault(text)
                        _cues.value=_cues.value.dropLast(1)+base.copy(translatedText=translated)
                    }
                }
                if(listening)listen()
            }
            override fun onError(error:Int){if(listening)listen()}
            override fun onReadyForSpeech(p:Bundle?){}
            override fun onBeginningOfSpeech(){}
            override fun onRmsChanged(v:Float){}
            override fun onBufferReceived(b:ByteArray?){}
            override fun onEndOfSpeech(){}
            override fun onPartialResults(b:Bundle?){}
            override fun onEvent(t:Int,b:Bundle?){}
        })
        listening=true;listen()
    }
    private fun listen(){recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
    })}
    fun stop(){listening=false;recognizer?.stopListening();recognizer?.destroy();recognizer=null}
    fun clear(){_cues.value=emptyList()}
}
