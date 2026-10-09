package com.mangalens.ui.video

internal data class PlaybackControlReceipt(val sessionId: String, val sourceRevision: Long)

/** Called on the player's application thread. Credentials and sources never enter this policy. */
internal class PlaybackLifecyclePolicy(val sessionId: String) {
    init { require(sessionId.matches(Regex("[a-f0-9]{32}"))) }
    var sourceRevision = 0L
        private set
    private var presentationEpoch = 0L
    private var attached = false
    private var foreground = false
    private var clients = 0
    var inPictureInPicture = false
        private set
    var backgroundRequested = false
        private set
    var backgroundEpoch = 0L
        private set
    private var serviceEpoch = 0L
    private val serviceLeases = mutableSetOf<Long>()
    private var continuation: Pair<Long, Long>? = null
    var closed = false
        private set
    val backgroundActive get() = !closed && backgroundRequested && serviceEpoch == backgroundEpoch
    val shouldPause get() = !foreground && !inPictureInPicture && !backgroundActive
    val canRelease get() = clients == 0 && !inPictureInPicture && !backgroundRequested && serviceLeases.isEmpty()

    fun addClient() { check(!closed); clients++ }
    fun removeClient() { check(clients > 0); clients-- }
    fun attachPresentation(): Long {
        check(!closed)
        continuation = null
        attached = true; foreground = true
        return ++presentationEpoch
    }
    fun ownsPresentation(epoch: Long) = !closed && attached && epoch == presentationEpoch
    fun acceptSource(epoch: Long): Boolean {
        if (!ownsPresentation(epoch)) return false
        continuation = null
        sourceRevision++
        return true
    }
    fun stopPresentation(epoch: Long, changingConfiguration: Boolean): Boolean {
        if (!ownsPresentation(epoch) || changingConfiguration) return false
        foreground = false
        return shouldPause
    }
    fun resumePresentation(epoch: Long) {
        if (ownsPresentation(epoch)) { foreground = true; continuation = null }
    }
    fun detachPresentation(epoch: Long, changingConfiguration: Boolean = false): Boolean {
        if (!ownsPresentation(epoch)) return false
        attached = false
        if (changingConfiguration) return false
        foreground = false; inPictureInPicture = false
        return shouldPause
    }
    fun requestBackground(epoch: Long, enabled: Boolean): Boolean {
        if (!ownsPresentation(epoch) || sourceRevision == 0L) return false
        continuation = null
        backgroundEpoch++
        backgroundRequested = enabled
        if (!enabled) serviceEpoch = 0L
        return true
    }
    fun acknowledgeService(id: String, epoch: Long = backgroundEpoch): Boolean {
        if (closed || sessionId != id || epoch != backgroundEpoch || !backgroundRequested || sourceRevision == 0L) return false
        serviceEpoch = epoch
        serviceLeases += epoch
        return true
    }
    fun isServiceOwner(id: String, epoch: Long) = id == sessionId && backgroundActive && epoch == serviceEpoch
    fun acceptsServiceControl(receipt: PlaybackControlReceipt, epoch: Long) =
        isServiceOwner(receipt.sessionId, epoch) && acceptsControl(receipt)
    fun retainBackgroundContinuation() {
        if (!closed && !foreground && backgroundRequested && !backgroundActive)
            continuation = backgroundEpoch to sourceRevision
    }
    fun consumeBackgroundContinuation(id: String, epoch: Long): Boolean {
        if (!isServiceOwner(id, epoch)) return false
        val resume = !foreground && continuation == (epoch to sourceRevision)
        continuation = null
        return resume
    }
    fun serviceStopped(id: String, epoch: Long = backgroundEpoch) {
        if (sessionId != id) return
        serviceLeases -= epoch
        if (serviceEpoch == epoch) serviceEpoch = 0L
        if (backgroundEpoch == epoch) backgroundRequested = false
        if (continuation?.first == epoch) continuation = null
    }
    fun stopBackground(id: String) {
        if (sessionId != id) return
        backgroundRequested = false; serviceEpoch = 0L; continuation = null
    }
    fun setPictureInPicture(epoch: Long, enabled: Boolean): Boolean {
        if (!ownsPresentation(epoch) || sourceRevision == 0L) return false
        inPictureInPicture = enabled
        return true
    }
    fun controlReceipt() = PlaybackControlReceipt(sessionId, sourceRevision)
    fun acceptsControl(receipt: PlaybackControlReceipt) = !closed && sourceRevision > 0 &&
        receipt.sessionId == sessionId && receipt.sourceRevision == sourceRevision &&
        (backgroundActive || inPictureInPicture)
    fun close() { closed = true; attached = false; foreground = false; continuation = null; inPictureInPicture = false; serviceLeases.clear(); stopBackground(sessionId) }
}
