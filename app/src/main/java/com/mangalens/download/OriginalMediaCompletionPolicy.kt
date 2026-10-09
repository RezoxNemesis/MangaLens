package com.mangalens.download

/** Completion reports verified bytes; an extractor client's inventory is not full-provider access proof. */
internal object OriginalMediaCompletionPolicy {
    fun note(height: Int, requestedHeight: Int?, selection: OriginalMediaSelection?, decoderSupported: Boolean?, hasAudio: Boolean = true): String = buildString {
        if (hasAudio) append("Completed at ${height}p; original encoded streams and both track tails verified.")
        else append("Completed at ${height}p; original video samples and full tail verified. This source has no audio track.")
        if (requestedHeight != null && requestedHeight < 9_000 && height < requestedHeight)
            append(" Source selection was below the ${requestedHeight}p ceiling.")
        if (selection?.client != null && selection.client != "default")
            append(" A fallback extractor client exposed this source; the full provider maximum is unverified.")
        if (selection?.maximumReportedHeight?.let { it > height } == true)
            append(" The provider reported a higher representation; its accessibility is unverified.")
        if (decoderSupported == false)
            append(" This device reports no compatible decoder for the original codecs; use a compatible player.")
    }
}
