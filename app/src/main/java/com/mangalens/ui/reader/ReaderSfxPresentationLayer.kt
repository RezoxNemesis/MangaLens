package com.mangalens.ui.reader

import android.graphics.RectF
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.mangalens.core.translation.PersonalReaderRegion
import com.mangalens.core.translation.ReaderSfxRestorationPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private class OriginalSfxSession(private val requests: List<PersonalReaderRegion>,
    private val gateway: ReaderSfxRestorationGateway?) : AutoCloseable, ReaderSfxNoteSource {
    var patches by mutableStateOf(emptyMap<PersonalReaderRegion, ReaderSfxOriginalPatch>())
        private set
    var settled by mutableStateOf(false)
    private var closed = false
    fun accept(region: PersonalReaderRegion, patch: ReaderSfxOriginalPatch) {
        if (closed) patch.close() else patches = patches + (region to patch)
    }
    override fun noteGroup(pageIndex: Int): ReaderSfxNoteGroup? {
        if (closed) return null
        val current = requests.filter { it.pageIndex == pageIndex && gateway?.isCurrent(it) == true }
        if (current.isEmpty()) return null
        val rows = current.take(ReaderSfxNotePolicy.MAX_ROWS_PER_PAGE).map { region ->
            val policy = requireNotNull(region.personal.regionPresentation)
            ReaderSfxNoteRow(region.nativeIndex, requireNotNull(policy.sfx), region.personal.personal.translated,
                policy.annotation, when {
                    patches[region]?.isCurrent() == true -> ReaderSfxOriginalReadiness.RESTORED
                    settled -> ReaderSfxOriginalReadiness.UNAVAILABLE
                    else -> ReaderSfxOriginalReadiness.PREPARING
                })
        }
        return ReaderSfxNoteGroup(pageIndex, rows, maxOf(0, current.size - ReaderSfxNotePolicy.MAX_ROWS_PER_PAGE))
    }
    override fun close() { if (!closed) { closed = true; val held = patches; patches = emptyMap(); held.values.forEach { it.close() } } }
}

/** No layer or resources exist until an explicit personal SFX policy needs original pixels. */
@Composable
internal fun ReaderSfxPresentationLayer(overlays: List<TranslationOverlay>, modifier: Modifier,
    draw: @Composable (Modifier, Map<PersonalReaderRegion, ReaderSfxOriginalPatch>) -> Unit) {
    val requests = remember(overlays) { overlays.mapNotNull { it.personalRegion }
        .filter { ReaderSfxRestorationPlan.needsOriginal(it.personal) } }
    if (requests.isEmpty()) { draw(modifier, emptyMap()); return }
    val gateway = LocalReaderSfxRestoration.current
    val session = remember(gateway, requests) { OriginalSfxSession(requests, gateway) }
    val notes = LocalReaderSfxNotes.current
    DisposableEffect(session) { onDispose { session.close() } }
    DisposableEffect(notes, session) { notes?.register(session); onDispose { notes?.unregister(session) } }
    LaunchedEffect(session) {
        try {
            requests.take(ReaderSfxRestorationPlan.MAX_REGIONS).forEach { region ->
                var received: ReaderSfxOriginalPatch? = null
                try {
                    received = gateway?.open(region)
                    currentCoroutineContext().ensureActive()
                    val patch = received
                    if (patch != null && patch.isCurrent()) { session.accept(region, patch); received = null }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A failed original restoration keeps translated lettering and an explicit fallback. */ }
                finally { received?.close() }
            }
        } finally { session.settled = true }
    }
    draw(modifier, session.patches)
}

/** Outward-rounded original decode is mapped independently on each axis, clipped to the exact native writable patch. */
internal fun drawOriginalSfxPatch(canvas: android.graphics.Canvas, patch: ReaderSfxOriginalPatch): Boolean {
    if (!patch.isCurrent()) return false
    val crop = patch.originalBounds
    val destination = RectF(crop.left.toFloat() * patch.imageWidth / patch.originalWidth,
        crop.top.toFloat() * patch.imageHeight / patch.originalHeight, crop.right.toFloat() * patch.imageWidth / patch.originalWidth,
        crop.bottom.toFloat() * patch.imageHeight / patch.originalHeight)
    val checkpoint = canvas.save()
    try { canvas.clipRect(patch.nativeBounds); canvas.drawBitmap(patch.crop.bitmap, null, destination, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
    finally { canvas.restoreToCount(checkpoint) }
    return true
}
