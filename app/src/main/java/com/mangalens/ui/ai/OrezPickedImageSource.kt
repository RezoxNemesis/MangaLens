package com.mangalens.ui.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.mangalens.core.compute.*
import com.mangalens.core.translation.ChapterTranslationComputeLane
import com.mangalens.engine.AdvancedTranslationEngine
import com.mangalens.orez.*
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** The exact explicit read-only SAF descriptor remains owned until all real provider consumers return. */
internal class OrezPickedImageLease private constructor(
    private val input: ParcelFileDescriptor.AutoCloseInputStream,
    private val slot: OrezImageProviderAccess.Slot,
    private val originalStamp: Stamp,
    val sha256: String
) : AutoCloseable {
    suspend fun <T> useSource(consume: suspend () -> T): T? {
        if(!slot.retain()) return null
        try { return consume() } finally { slot.release() }
    }
    override fun close() { slot.retire() }
    suspend fun verified(): Boolean = OrezImageProviderAccess.hashRead(slot) { checkActive ->
        digest(input,originalStamp,checkActive)==sha256
    } ?: false
    @Suppress("DEPRECATION")
    suspend fun <T> withCrop(crop: OrezImageCropRequest, consume: suspend (Bitmap,Int,Int,OrezImageRegion) -> T): T? =
        useSource {
            check(verified()) { "The picked image changed. Pick it again." }
            var decoder: BitmapRegionDecoder?=null; var bitmap: Bitmap?=null
            try {
                currentCoroutineContext().ensureActive()
                decoder=BitmapRegionDecoder.newInstance(input.fd,false) ?: error("This image cannot be decoded locally.")
                val plan=OrezImageCropPlan.create(decoder.width,decoder.height,crop)
                val r=plan.region
                bitmap=decoder.decodeRegion(Rect(r.left,r.top,r.right,r.bottom),BitmapFactory.Options().apply {
                    inSampleSize=plan.sample; inPreferredConfig=Bitmap.Config.ARGB_8888
                }) ?: error("The selected image region is unavailable.")
                check(plan.accepts(bitmap.width,bitmap.height)) { "The decoded image exceeded the bounded region budget." }
                check(verified()) { "The picked image changed during decoding." }
                currentCoroutineContext().ensureActive()
                // Existing OCR awaits actual Task return before this bitmap/decoder/FD can close.
                val result=consume(bitmap,decoder.width,decoder.height,r)
                currentCoroutineContext().ensureActive()
                check(verified()) { "The picked image changed during OCR." }
                result
            } finally { try { bitmap?.recycle() } finally { decoder?.recycle() } }
        }
    private data class Stamp(val device:Long,val inode:Long,val size:Long,val modified:Long,val changed:Long)
    companion object {
        private const val MAX_BYTES=40L*1024*1024
        private fun stamp(s: android.system.StructStat)=Stamp(s.st_dev,s.st_ino,s.st_size,s.st_mtime,s.st_ctime)
        suspend fun open(context: Context, uri: Uri): OrezPickedImageLease {
            require(uri.scheme=="content") { "Choose an image through the document picker." }
            val application=context.applicationContext
            // This producer captures only application/source data, never a retired UI callback.
            return OrezImageProviderAccess.open { slot ->
                slot.checkActive()
                val pfd=application.contentResolver.openFileDescriptor(uri,"r",slot.signal)
                    ?: error("The picked image is unavailable.")
                // Register the descriptor before stream allocation/factory handoff can fail.
                slot.ownDescriptor(pfd)
                val input=ParcelFileDescriptor.AutoCloseInputStream(pfd)
                slot.ownStream(input)
                slot.checkActive()
                val stat=Os.fstat(input.fd)
                require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..MAX_BYTES) {
                    "This provider must supply a seekable, regular image of at most 40 MB. Nonseekable sources are unsupported."
                }
                val held=stamp(stat)
                val hash=digest(input,held,slot::checkActive) ?: error("The picked image changed while being read.")
                OrezPickedImageLease(input,slot,held,hash)
            }
        }
        private suspend fun digest(input: ParcelFileDescriptor.AutoCloseInputStream, expected: Stamp, checkActive:()->Unit): String? {
            currentCoroutineContext().ensureActive();checkActive()
            if(stamp(Os.fstat(input.fd))!=expected) return null
            input.channel.position(0)
            val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var count=0L
            while(true) {
                currentCoroutineContext().ensureActive();checkActive();val read=input.read(buffer);if(read<0) break
                count+=read;check(count<=MAX_BYTES) { "The picked image exceeded its byte budget." };hash.update(buffer,0,read)
            }
            input.channel.position(0)
            currentCoroutineContext().ensureActive();checkActive()
            if(count!=expected.size || stamp(Os.fstat(input.fd))!=expected) return null
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal class OrezImagePreview(val bitmap: Bitmap) : AutoCloseable {
    private val closed=AtomicBoolean()
    override fun close() { if(closed.compareAndSet(false,true)) bitmap.recycle() }
}
/** A visible Image retains its own smaller preview until Compose disposal. */
internal class OrezImagePreviewOwnership(private val resource: OrezImagePreview) {
    private val state=java.util.concurrent.atomic.AtomicInteger(0)
    fun claim(): OrezImagePreview?=if(state.compareAndSet(0,1)) resource else null
    fun abandon() { if(state.compareAndSet(0,2)) resource.close() }
    fun disposeUi() { if(state.compareAndSet(1,2)) resource.close() }
}

internal class OrezImageOcrResult(val source: OrezPickedImageLease,readings: List<OrezImageAttachment>,
    val preview: OrezImagePreviewOwnership) : AutoCloseable {
    val readings: List<OrezImageAttachment> = java.util.Collections.unmodifiableList(readings.toList())
    override fun close() { preview.abandon();source.close() }
}

internal class OrezPickedImageOcr(private val context: Context) {
    suspend fun read(uri:Uri,crop:OrezImageCropRequest,script:String,owner:NativeComputePrecondition): OrezImageOcrResult {
        require(script in setOf("AUTO","LATIN","DEVANAGARI","CHINESE","JAPANESE","KOREAN"));crop.validated()
        var acquired:OrezImageOcrResult?=null;var delivered=false
        try {
            val result=withContext(Dispatchers.IO+owner) {
                owner.validate(true)
                val source=OrezPickedImageLease.open(context,uri)
                var preview:OrezImagePreviewOwnership?=null;var transferred=false
                try {
                    val readings=ChapterTranslationComputeLane.withLock {
                        val caller=currentCoroutineContext()
                        val admission=NativeComputeAdmission.shared.acquire(NativeComputeAdmission.Priority.INTERACTIVE) { caller.isActive }
                            ?: throw CancellationException("The image owner closed.")
                        try {
                            owner.validate(admission.waited)
                            ResourceGovernorRuntime.shared.requireNativeEntry(ResourceWorkKind.INTERACTIVE)
                            source.withCrop(crop) { pixels,width,height,region ->
                                val engine=AdvancedTranslationEngine(context)
                                val sets=try { engine.recognizeSavedRegionAlternatives(pixels,AdvancedTranslationEngine.OcrOptions(script,true)) }
                                    finally { engine.close() }
                                owner.validate(true)
                                val readings=sets.take(5).mapNotNull { rows ->
                                    var truncated=rows.size>40
                                    val text=buildString {
                                        for(row in rows.take(40)) {
                                            val value=row.source.trim();if(value.isBlank()) continue
                                            val available=OrezImageAttachment.MAX_TEXT-length-(if(isNotEmpty()) 1 else 0)
                                            if(available<=0) { truncated=true;break }
                                            if(isNotEmpty()) append('\n')
                                            val part=value.take(available).let { if(it.lastOrNull()?.isHighSurrogate()==true) it.dropLast(1) else it }
                                            append(part);if(part!=value) truncated=true
                                        }
                                    }
                                    if(text.isBlank()) null else OrezImageAttachment(source.sha256,width,height,region,
                                        rows.first().recognizerScript,text,truncated).validated()
                                }.distinctBy { it.recognizerScript to it.originalOcr }
                                check(readings.isNotEmpty()) { "No usable OCR text was found in this region. Try another crop or script." }
                                val scale=minOf(1f,512f/maxOf(pixels.width,pixels.height))
                                val thumb=Bitmap.createScaledBitmap(pixels,maxOf(1,(pixels.width*scale).toInt()),maxOf(1,(pixels.height*scale).toInt()),true)
                                val separate=try { requireNotNull(thumb.copy(Bitmap.Config.ARGB_8888,false)) } finally { if(thumb!==pixels) thumb.recycle() }
                                preview=OrezImagePreviewOwnership(OrezImagePreview(separate))
                                readings
                            } ?: error("The selected source was retired.")
                        } finally { admission.close() }
                    }
                    owner.validate(true)
                    OrezImageOcrResult(source,readings,requireNotNull(preview)).also { acquired=it;transferred=true }
                } finally { if(!transferred) { preview?.abandon();source.close() } }
            }
            delivered=true;return result
        } finally { if(!delivered) acquired?.close() }
    }
}
