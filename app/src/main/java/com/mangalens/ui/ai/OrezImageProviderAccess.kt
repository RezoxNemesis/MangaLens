package com.mangalens.ui.ai

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.mangalens.ui.downloads.OwnedSavedVideoProbe
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One source reservation survives UI retirement until actual open/read/cancel/close return. */
internal object OrezImageProviderAccess {
    const val PROVIDER_TIMEOUT_MS=5_000L
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val active=AtomicReference<Slot?>(null)
    // Reuse the existing application-owned read/requester pattern. A hash result owns no pixels/URI.
    private val hashes=OwnedSavedVideoProbe()

    suspend fun open(produce:suspend(Slot)->OrezPickedImageLease):OrezPickedImageLease = withTimeout(PROVIDER_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val slot=Slot()
            if(!active.compareAndSet(null,slot)) {
                continuation.resumeWithException(IllegalStateException(if(active.get()?.cleanupFailed==true)
                    "Previous image access could not close safely. Restart the app before picking another image."
                    else "Previous image access is still finishing. Try again later."))
                return@suspendCancellableCoroutine
            }
            val requester=AtomicReference<CancellableContinuation<OrezPickedImageLease>?>(continuation)
            continuation.invokeOnCancellation { requester.set(null);slot.retire() }
            scope.launch {
                var value:OrezPickedImageLease?=null;var failure:Throwable?=null
                try { slot.checkActive();value=produce(slot);slot.checkActive() }
                catch(problem:Throwable) { failure=problem;slot.retire() }
                finally { slot.producerReturned() }
                val receiver=requester.getAndSet(null)
                if(receiver==null) slot.retire()
                else if(failure!=null) receiver.resumeWithException(requireNotNull(failure))
                else receiver.resume(requireNotNull(value))
            }
        }
    }

    suspend fun <T> hashRead(slot:Slot,read:suspend(()->Unit)->T):T? = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
        hashes.run { owner ->
            if(!slot.retain()) throw CancellationException("The image source was retired.")
            val retained=ReadReference(slot)
            try {
                owner.own(retained)
                read { owner.checkActive();slot.checkActive() }
            } finally { retained.producerReturned() }
        }
    }

    /** Probe cancellation may request close before read return; it cannot release source use early. */
    private class ReadReference(private val slot:Slot):AutoCloseable {
        private val guard=Any();private var finished=false;private var closed=false;private var released=false
        override fun close() { synchronized(guard) { closed=true;releaseIfDone() } }
        fun producerReturned() { synchronized(guard) { finished=true;releaseIfDone() } }
        private fun releaseIfDone() { if(finished && closed && !released) { released=true;slot.release() } }
    }

    internal class Slot {
        val signal=CancellationSignal()
        private val guard=Any()
        @Volatile private var retired=false
        @Volatile var cleanupFailed=false
            private set
        private var users=0
        private var producerFinished=false
        private var cancelClaimed=false;private var cancelFinished=false
        private var closeClaimed=false;private var closeFinished=false
        private var closeTarget:AutoCloseable?=null
        private var released=false
        fun checkActive() { if(retired) throw CancellationException("The picked image source was retired.") }
        fun ownDescriptor(value:ParcelFileDescriptor) { synchronized(guard) { check(closeTarget==null);closeTarget=value } }
        fun ownStream(value:ParcelFileDescriptor.AutoCloseInputStream) {
            synchronized(guard) { check(closeTarget is ParcelFileDescriptor);closeTarget=value }
        }
        fun retain():Boolean=synchronized(guard) { if(retired) false else { users++;true } }
        fun release() { synchronized(guard) { users--;check(users>=0);scheduleCloseIfDone();settle() } }
        fun producerReturned() { synchronized(guard) { producerFinished=true;scheduleCloseIfDone();settle() } }
        fun retire() {
            synchronized(guard) {
                retired=true
                if(!cancelClaimed) {
                    cancelClaimed=true
                    scope.launch {
                        try { signal.cancel() } catch(problem:Throwable) { cleanupFailure(problem) }
                        finally { synchronized(guard) { cancelFinished=true;settle() } }
                    }
                }
                scheduleCloseIfDone();settle()
            }
        }
        /** Called under guard; enqueues one I/O close, never invokes provider work on Main. */
        private fun scheduleCloseIfDone() {
            if(!retired || !producerFinished || users!=0 || closeClaimed) return
            closeClaimed=true
            val target=closeTarget
            scope.launch {
                try { target?.close() } catch(problem:Throwable) { cleanupFailure(problem) }
                finally { synchronized(guard) { closeFinished=true;if(!cleanupFailed) closeTarget=null;settle() } }
            }
        }
        private fun cleanupFailure(problem:Throwable) {
            synchronized(guard) { cleanupFailed=true }
            android.util.Log.w("OrezImage","source_cleanup_failure type=${problem.javaClass.simpleName}")
        }
        private fun settle() {
            if(retired && producerFinished && users==0 && closeFinished && cancelFinished && !released && !cleanupFailed) {
                released=true;check(active.compareAndSet(this,null))
            }
        }
    }
}
