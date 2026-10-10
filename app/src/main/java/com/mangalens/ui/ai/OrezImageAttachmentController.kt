package com.mangalens.ui.ai

import android.content.Context
import android.net.Uri
import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.orez.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class OrezImageAttachmentState(val busy:Boolean=false,val hasSelection:Boolean=false,
    val attachment:OrezImageAttachment?=null,val readings:List<OrezImageAttachment> = emptyList(),
    val preview:OrezImagePreviewOwnership?=null,val status:String?=null)
internal data class OrezImageAnswerCapture(val attachment:OrezImageAttachment,val owner:OrezImageSourceOwner)

/** Ephemeral foreground ownership, not a restored URI/task grant. Main owns its mutations. */
internal class OrezImageAttachmentController(context:Context,private val scope:CoroutineScope,private val stopAnswer:()->Unit) {
    private val service=OrezPickedImageOcr(context.applicationContext)
    private val _state=MutableStateFlow(OrezImageAttachmentState())
    val state=_state.asStateFlow()
    @Volatile private var generation=0L
    @Volatile private var answerGeneration=0L
    @Volatile private var active=true
    @Volatile private var closed=false
    @Volatile private var result:OrezImageOcrResult?=null
    @Volatile private var selected:OrezImageAttachment?=null
    private var selectedUri:Uri?=null
    private var operation:Job?=null
    private var picker=0L
    private var pendingPicker:Long?=null
    private var returnedPicker:Pair<Long,Uri?>?=null

    fun beginPick():Long? { if(closed || pendingPicker!=null) return null;remove();return (++picker).also { pendingPicker=it } }
    fun acceptPick(ticket:Long,uri:Uri?) {
        if(closed || pendingPicker!=ticket) return
        if(!active) { returnedPicker=ticket to uri;return }
        pendingPicker=null;returnedPicker=null
        if(uri==null) return
        selectedUri=uri
        _state.value=OrezImageAttachmentState(hasSelection=true,status="Choose a region and script, then read its text locally.")
    }
    fun draftChanged() { answerGeneration++;stopAnswer() }
    fun regionChanged() {
        retireCurrent();_state.value=OrezImageAttachmentState(hasSelection=selectedUri!=null,status="Region or script changed. Read the image again before asking.")
    }
    fun read(crop:OrezImageCropRequest,script:String) {
        val uri=selectedUri ?: return
        if(closed || !active) return
        retireCurrent();val request=generation
        _state.value=OrezImageAttachmentState(busy=true,hasSelection=true,status="Reading the selected image region locally…")
        val owner=NativeComputePrecondition {
            currentCoroutineContext().ensureActive()
            if(!owns(request)) throw CancellationException("The image attachment request was retired.")
        }
        operation=scope.launch {
            var acquired:OrezImageOcrResult?=null;var handedOff=false
            try {
                acquired=service.read(uri,crop,script,owner)
                currentCoroutineContext().ensureActive()
                if(!owns(request)) throw CancellationException("The image selection changed.")
                result=acquired;selected=acquired.readings.first()
                _state.value=OrezImageAttachmentState(hasSelection=true,attachment=selected,readings=acquired.readings,
                    preview=acquired.preview,status="OCR attachment ready. Questions use this local text only; image objects and artwork are not understood.")
                handedOff=true
            } catch(timeout:TimeoutCancellationException) {
                if(owns(request)) _state.value=OrezImageAttachmentState(hasSelection=true,
                    status="Image provider access timed out. Its retained work may still be finishing; retry later.")
            } catch(cancelled:CancellationException) { throw cancelled }
              catch(failure:Exception) {
                if(owns(request)) _state.value=OrezImageAttachmentState(hasSelection=true,status=failure.message ?: "Local image OCR is unavailable.")
            } finally {
                if(!handedOff) acquired?.close()
                if(generation==request) { operation=null;if(_state.value.busy) _state.value=_state.value.copy(busy=false) }
            }
        }
    }
    fun chooseReading(attachment:OrezImageAttachment) {
        val current=result ?: return
        if(!active || closed || attachment !in current.readings) return
        draftChanged();selected=attachment;_state.value=_state.value.copy(attachment=attachment)
    }
    fun captureAnswer():OrezImageAnswerCapture? {
        val source=result ?: return null;val text=selected ?: return null
        if(!active || closed || _state.value.busy) return null
        val request=generation;val answer=++answerGeneration
        fun current()=owns(request) && answerGeneration==answer && result===source && selected==text
        val owner=object:OrezImageSourceOwner {
            override suspend fun isCurrent(attachment:OrezImageAttachment):Boolean {
                if(attachment!=text || !current()) return false
                return source.source.useSource { source.source.verified() && current() } ?: false
            }
            override suspend fun <T> withVerifiedSource(attachment:OrezImageAttachment,consume:suspend()->T):T? {
                if(attachment!=text || !current()) return null
                return source.source.useSource {
                    if(!source.source.verified() || !current()) throw CancellationException("The attached image source changed.")
                    val response=consume()
                    currentCoroutineContext().ensureActive()
                    if(!source.source.verified() || !current()) throw CancellationException("The image answer owner changed.")
                    response
                }
            }
        }
        return OrezImageAnswerCapture(text.copy(region=text.region.copy()).validated(),owner)
    }
    fun cancelRead() {
        retireCurrent();_state.value=OrezImageAttachmentState(hasSelection=selectedUri!=null,status="Image OCR cancelled. Native resources close after the actual provider returns.")
    }
    fun remove() {
        pendingPicker=null;returnedPicker=null;selectedUri=null;retireCurrent();_state.value=OrezImageAttachmentState()
    }
    fun setForeground(foreground:Boolean) {
        active=foreground
        if(foreground) {
            returnedPicker?.let { (ticket,uri) -> returnedPicker=null;acceptPick(ticket,uri) }
        } else {
            // The separate explicit picker ticket may return on START; no OCR/answer owner survives STOP.
            selectedUri=null;retireCurrent();_state.value=OrezImageAttachmentState()
        }
    }
    fun close() { closed=true;pendingPicker=null;returnedPicker=null;remove() }
    private fun owns(request:Long)=!closed && active && generation==request
    private fun retireCurrent() {
        generation++;answerGeneration++;stopAnswer();operation?.cancel();operation=null
        val old=result;result=null;selected=null
        _state.value=OrezImageAttachmentState(hasSelection=selectedUri!=null)
        old?.close()
    }
}
