package com.mangalens.engine

import android.content.Context
import com.mangalens.acquisition.RenderedBrowserAcquirer
import com.mangalens.acquisition.RenderedPageSet
import com.mangalens.core.acquisition.ChapterImageCandidate
import com.mangalens.core.adblock.AdBlockEngine

class MangaChapterScraper(
    context: Context,
    adBlock: AdBlockEngine = AdBlockEngine(),
    adBlockEnabled: () -> Boolean = { true }
) {
    private val acquirer = RenderedBrowserAcquirer(context, adBlock, adBlockEnabled)
    suspend fun extract(url: String, timeoutMs: Long = 18_000L): List<String> =
        extractCandidates(url, timeoutMs).map { it.url }

    suspend fun extractCandidates(url: String, timeoutMs: Long = 18_000L): List<ChapterImageCandidate> =
        discover(url, timeoutMs).imageCandidates

    suspend fun discover(url: String, timeoutMs: Long = 18_000L, isCurrent: () -> Boolean = { true }): RenderedPageSet =
        acquirer.discoverWithCookie(url, timeoutMs, null, isCurrent)
}
