package com.mangalens.ui.video

import android.content.Context
import android.net.Uri
import com.mangalens.ui.downloads.DownloadedVideoOpening
import java.io.IOException

internal sealed interface RecentVideoOpen {
    data class Local(val uri: Uri) : RecentVideoOpen
    data class Online(val sourcePage: String) : RecentVideoOpen
}

/** A history entry is metadata. A local replay obtains fresh read authority; online replay resolves again. */
internal object RecentVideoOpening {
    suspend fun prepare(context: Context, key: String): RecentVideoOpen {
        val store = RecentVideoStore.shared(context)
        if (store.state.value.loading) throw IOException("Recent videos are still loading. Try again shortly.")
        val entry = store.state.value.entries.singleOrNull { it.key == key }
            ?: throw IOException("This video is no longer in recent history.")
        entry.validate()
        val result = when (entry.source.kind) {
            RecentVideoKind.LOCAL -> RecentVideoOpen.Local(DownloadedVideoOpening.prepareRecentLocal(context, entry.source.uri))
            RecentVideoKind.ONLINE -> RecentVideoOpen.Online(entry.source.uri)
        }
        if (store.state.value.entries.none { it.key == key && it.source == entry.source })
            throw IOException("This video was removed from recent history.")
        return result
    }
}
