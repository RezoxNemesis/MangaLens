package com.mangalens.ui.video

internal data class CaptionSourceTicket(val selection: Long, val sourceRevision: Long, val uri: String)

/** Save the captured request unchanged; recreation never grants it a new source revision. */
internal fun CaptionSourceTicket.savedFields(): List<String> = listOf(selection.toString(), sourceRevision.toString(), uri)

internal fun restoreCaptionTicket(fields: List<String>): CaptionSourceTicket? {
    if (fields.size != 3 || fields.any { it.length > 8192 }) return null
    val selection = fields[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val revision = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val uri = fields[2].takeIf { it.isNotBlank() && '\u0000' !in it } ?: return null
    return CaptionSourceTicket(selection, revision, uri)
}

/** A picker or text model result retains no authority after a newer source or selection. */
internal class CaptionPublication {
    private var selection = 0L
    @Synchronized fun capture(sourceRevision: Long, uri: String) = CaptionSourceTicket(++selection, sourceRevision, uri)
    @Synchronized fun invalidate() { selection++ }
    @Synchronized fun accepts(ticket: CaptionSourceTicket, sourceRevision: Long, uri: String?): Boolean =
        ticket.selection == selection && ticket.sourceRevision == sourceRevision && ticket.uri == uri
}
