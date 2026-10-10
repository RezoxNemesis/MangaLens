package com.mangalens.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.UUID

internal interface AdaptiveDownloadCommands {
    fun add(id: String, url: String, mime: String)
    fun pause(id: String)
    fun resume(id: String)
    fun remove(id: String)
}

private class AndroidAdaptiveDownloadCommands(private val context: Context) : AdaptiveDownloadCommands {
    override fun add(id: String, url: String, mime: String) = MangaLensDownloadService.addAdaptive(context, id, android.net.Uri.parse(url), mime)
    override fun pause(id: String) = MangaLensDownloadService.pauseAdaptive(context, id)
    override fun resume(id: String) = MangaLensDownloadService.resumeAdaptive(context, id)
    override fun remove(id: String) = MangaLensDownloadService.remove(context, id)
}

class MediaDownloadManager internal constructor(context: Context, private val adaptive: AdaptiveDownloadCommands) {
    private val context = context.applicationContext
    constructor(context: Context) : this(context.applicationContext, AndroidAdaptiveDownloadCommands(context.applicationContext))
    private val resolver = MediaLinkResolver(siteExtractor = YtDlpSiteMediaExtractor(context, allowSeparateStreams = true))
    private val contexts = DownloadRequestContextStore(context)
    private val dao = DownloadDatabase.get(context).downloads()
    val downloads: Flow<List<DownloadEntity>> = dao.observe()

    suspend fun enqueue(
        url: String,
        title: String? = null,
        mimeType: String? = null,
        quality: DownloadQuality = DownloadQuality.BEST,
        sourcePageUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
        requestId: String? = null
    ): String = withContext(Dispatchers.IO) {
        require(requestId == null || requestId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid download request ID" }
        // Durable Orez retries reuse the same request rather than creating another transfer.
        if (requestId != null) {
            val existing = DownloadIdMutationFences.withId(requestId) {
                dao.get(requestId)?.also { item ->
                    if (item.state == DownloadState.QUEUED && !DownloadPrivateFileOwners.hasOwners(privateOwner(item.id))) {
                        start(item.id, item.sourceUrl, item.title, item.mimeType, ExistingWorkPolicy.KEEP, item.selectedTransport)
                    }
                }
            }
            if (existing != null) return@withContext requestId
        }
        val clean = url.trim()
        require(clean.startsWith("http://") || clean.startsWith("https://")) {
            "Only HTTP(S) media links can be downloaded."
        }
        val resolved = resolver.resolveCancellable(clean, quality)
            ?: throw IllegalArgumentException("The page did not expose an accessible media source.")
        SelectedDownloadTransportPolicy.requireSupported(resolved)
        val mediaUrl = resolved.url
        val id = requestId ?: UUID.randomUUID().toString()
        val explicitTitle = title?.takeIf(String::isNotBlank)
        val resolvedTitle = resolved.title?.takeIf(String::isNotBlank)
        val finalTitle = when {
            explicitTitle != null && resolved.provider.lowercase() in setOf("direct", "generic", "web-sniff") -> explicitTitle
            resolvedTitle != null -> resolvedTitle
            explicitTitle != null -> explicitTitle
            else -> mediaUrl.substringAfterLast('/').substringBefore('?').ifBlank { "MangaLens media" }
        }
        val mime = mimeType ?: resolved.mimeType ?: "application/octet-stream"
        DownloadIdMutationFences.withId(id) {
            // Resolution is outside the mutation fence. A late resolved request cannot replace
            // a concurrently created, canceled or cleaned request with the same durable ID.
            dao.get(id)?.let { return@withId id }
            requireReleased(id)
            contexts.write(id, resolved.copy(
                sourcePageUrl = sourcePageUrl ?: resolved.sourcePageUrl,
                headers = resolvedDownloadHeaders(clean, resolved.url, resolved.headers, headers)
            ))
            val pageUrl = sourcePageUrl ?: resolved.sourcePageUrl ?: clean
            dao.upsert(
                DownloadEntity(
                    id = id,
                    sourceUrl = mediaUrl,
                    title = finalTitle,
                    mimeType = mime,
                    state = DownloadState.QUEUED,
                    provider = resolved.provider,
                    sourcePageUrl = pageUrl,
                    requestedHeight = quality.height, selectedTransport = OriginalFragmentTransport.downloadKind(resolved)
                )
            )
            start(id, mediaUrl, finalTitle, mime, selectedTransport = OriginalFragmentTransport.downloadKind(resolved))
            id
        }
    }

    /**
     * Queues a concrete media request already observed by the WebView/native resolver.
     * This deliberately skips page re-extraction so signed CDN URLs keep the exact
     * Cookie/Referer/User-Agent context that made them playable in the browser.
     */
    suspend fun enqueueResolvedMedia(
        url: String,
        title: String? = null,
        mimeType: String = "video/mp4",
        quality: DownloadQuality = DownloadQuality.BEST,
        sourcePageUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
        provider: String = "web-sniff",
        capturedMedia: ResolvedMediaLink? = null
    ): String = withContext(Dispatchers.IO) {
        val clean = url.trim()
        require(clean.startsWith("http://") || clean.startsWith("https://")) {
            "Only HTTP(S) media links can be downloaded."
        }
        val page = sourcePageUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val safeHeaders = headers.filter { (name, value) ->
            name.lowercase() in setOf("accept", "accept-language", "cookie", "origin", "referer", "user-agent") &&
                value.length <= 16_384 &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' }
        }
        capturedMedia?.let {
            require(it.url == clean && MediaTransportMime.capture(it.mimeType) == MediaTransportMime.capture(mimeType)) {
                "Detected download no longer matches its complete captured source."
            }
            requireBoundMediaSource(clean, it)
        }
        val resolved = capturedMedia?.copy(sourcePageUrl = page ?: capturedMedia.sourcePageUrl,
            headers = safeHeaders.toMap(), audioHeaders = capturedMedia.audioHeaders.toMap()) ?: ResolvedMediaLink(
            url = clean,
            mimeType = mimeType,
            provider = provider,
            title = title?.takeIf(String::isNotBlank),
            sourcePageUrl = page,
            headers = safeHeaders,
            requestedHeight = quality.height
        )
        SelectedDownloadTransportPolicy.requireSupported(resolved)
        val id = UUID.randomUUID().toString()
        val finalTitle = title?.takeIf(String::isNotBlank)
            ?: clean.substringAfterLast('/').substringBefore('?').ifBlank { "MangaLens media" }
        DownloadIdMutationFences.withId(id) {
            requireReleased(id)
            contexts.write(id, resolved)
            dao.upsert(
                DownloadEntity(
                    id = id,
                    sourceUrl = clean,
                    title = finalTitle,
                    mimeType = mimeType,
                    state = DownloadState.QUEUED,
                    provider = provider,
                    sourcePageUrl = page ?: clean,
                    requestedHeight = quality.height, selectedTransport = OriginalFragmentTransport.downloadKind(resolved)
                )
            )
            start(id, clean, finalTitle, mimeType, selectedTransport = OriginalFragmentTransport.downloadKind(resolved))
            id
        }
    }

    suspend fun pause(id: String) = withContext(Dispatchers.IO) {
        DownloadIdMutationFences.withId(id) {
            val item = dao.get(id) ?: return@withId
            if (dao.stopIfActive(id, DownloadState.PAUSED, null) == 0) return@withId
            if (item.isAdaptive) adaptive.pause(id)
            else WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        }
    }

    suspend fun resume(id: String) = withContext(Dispatchers.IO) {
        val captured = dao.get(id) ?: return@withContext
        if (captured.state != DownloadState.PAUSED && captured.state != DownloadState.FAILED) return@withContext
        var refreshed: ResolvedMediaLink? = null
        if (captured.state == DownloadState.FAILED) {
            val saved = contexts.readMedia(id)
            val page = saved?.sourcePageUrl
            if (!page.isNullOrBlank() && page != captured.sourceUrl) {
                val quality = DownloadQuality.selectable.firstOrNull { it.height == saved?.requestedHeight } ?: DownloadQuality.BEST
                refreshed = resolver.resolveCancellable(page, quality)?.let {
                    it.copy(sourcePageUrl = it.sourcePageUrl ?: page, requestedHeight = it.requestedHeight ?: quality.height)
                }
            }
        }
        DownloadIdMutationFences.withId(id) {
            if (dao.get(id) != captured) return@withId
            if (!captured.isAdaptive) requireReleased(id)
            var item = captured
            refreshed?.let { fresh ->
                SelectedDownloadTransportPolicy.requireSupported(fresh)
                val mime = fresh.mimeType ?: item.mimeType
                val title = fresh.title?.takeIf(String::isNotBlank) ?: item.title
                if (dao.refreshSource(id, fresh.url, mime, title, fresh.provider, fresh.sourcePageUrl,
                        fresh.requestedHeight, "Refreshed expired source", OriginalFragmentTransport.downloadKind(fresh)) == 0) return@withId
                contexts.write(id, fresh)
                item = item.copy(sourceUrl = fresh.url, mimeType = mime, title = title, provider = fresh.provider,
                    sourcePageUrl = fresh.sourcePageUrl, requestedHeight = fresh.requestedHeight, selectedTransport = OriginalFragmentTransport.downloadKind(fresh))
                // No earlier process or native task can still own this ID's input files.
                FailedDownloadPartialFiles.plan(privateOwner(id).root, id).forEach { file ->
                    check(file.delete()) { "Previous partial files are still in use. Try again after the transfer stops." }
                }
            }
            SelectedDownloadTransportPolicy.requireSupported(contexts.readMedia(id))
            OriginalFragmentTransport.requireDownloadBinding(item.selectedTransport, contexts.readMedia(id))
            if (item.isAdaptive) {
                if (dao.resumeIfStopped(id, DownloadState.DOWNLOADING) == 0) return@withId
                if (captured.state == DownloadState.FAILED) adaptive.add(id, item.sourceUrl, item.mimeType)
                else adaptive.resume(id)
            } else {
                if (dao.resumeIfStopped(id, DownloadState.QUEUED) == 0) return@withId
                start(id, item.sourceUrl, item.title, item.mimeType, selectedTransport = item.selectedTransport)
            }
        }
    }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        com.mangalens.orez.agent.OrezDownloadTaskLink.cancelOwningTask(context, id)
        DownloadIdMutationFences.withId(id) { cancelTransfer(id) }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        com.mangalens.orez.agent.OrezDownloadTaskLink.cancelOwningTask(context, id)
        DownloadIdMutationFences.withId(id) {
            cancelTransfer(id); dao.delete(id); contexts.remove(id)
        }
    }

    private suspend fun cancelTransfer(id: String) {
        dao.stopIfActive(id, DownloadState.CANCELLED, "Cancelled by user")
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        adaptive.remove(id)
    }

    /** Explicit row confirmation stops the failed request; it never sweeps an entire cache. */
    suspend fun clearFailedPartials(requested: DownloadEntity): FailedDownloadPartialResult = withContext(Dispatchers.IO) {
        val id = requested.id
        val rows = object : FailedDownloadPartialRows {
            override suspend fun get(id: String) = dao.get(id)
            override suspend fun claim(captured: DownloadEntity) = dao.claimFailedPartials(captured.id, captured.sourceUrl,
                captured.createdAt, captured.bytesDownloaded, captured.totalBytes, captured.mimeType, captured.title,
                captured.stage, captured.error, captured.provider, captured.sourcePageUrl, captured.requestedHeight, captured.selectedTransport) == 1
            override suspend fun complete(captured: DownloadEntity) = dao.finishFailedPartials(captured.id, captured.sourceUrl,
                captured.createdAt, captured.bytesDownloaded, captured.totalBytes) == 1
        }
        FailedDownloadPartialCleaner(privateOwner(id).root, rows, allWorkFinished = { exactId ->
            val infos = try { WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName(exactId)).get(2, java.util.concurrent.TimeUnit.SECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { throw DownloadFilesBusyException() }
            check(infos.size <= 64) { "This transfer has too many saved generations to inspect safely. Files were retained." }
            infos.all { it.state.isFinished }
        }, protectedContent = { captured, files ->
            val history = com.mangalens.ui.video.RecentVideoStore.shared(context).state.value
            check(!history.loading && history.error == null) { "Video history is not readable yet. Partial files were retained." }
            val fileUris = files.flatMap { file -> listOf(file.toURI().toString(), android.net.Uri.fromFile(file).toString(),
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.downloads", file).toString()) }.toSet()
            // Keep every history-referenced source, which also protects favourites without racing
            // a favourite toggle on an existing record. No saved history metadata is removed here.
            history.entries.any { entry -> entry.source.uri in fileUris ||
                entry.source.uri == captured.sourcePageUrl || entry.source.uri == captured.sourceUrl }
        }, stopClaimedRequest = { exactId ->
            com.mangalens.orez.agent.OrezDownloadTaskLink.cancelOwningTask(context, exactId)
            WorkManager.getInstance(context).cancelUniqueWork(workName(exactId))
        }).clean(requested)
    }

    private fun privateOwner(id: String) = DownloadPrivateFileOwner(java.io.File(context.filesDir, "downloads"), id)
    private fun requireReleased(id: String) {
        val owner = privateOwner(id)
        if (DownloadPrivateFileOwners.hasOwners(owner)) throw DownloadPrivateFileOwners.ownershipFailure(owner)
    }

    private fun start(id: String, url: String, title: String, mime: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE, selectedTransport: String? = null) {
        val media = contexts.readMedia(id)
        SelectedDownloadTransportPolicy.requireSupported(media)
        OriginalFragmentTransport.requireDownloadBinding(selectedTransport, media)
        if (selectedTransport == null && isAdaptiveMediaSource(url, mime)) {
            adaptive.add(id, url, mime)
            return
        }
        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(
                workDataOf(
                    MediaDownloadWorker.KEY_ID to id,
                    MediaDownloadWorker.KEY_URL to url,
                    MediaDownloadWorker.KEY_TITLE to title,
                    MediaDownloadWorker.KEY_MIME to mime
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
            .addTag("mangalens_download")
            .addTag(id)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            policy,
            request
        )
    }

    private fun workName(id: String) = "mangalens-download-$id"

    companion object {
        /** Called by native producers only after an accepted committed terminal write. */
        internal suspend fun notifyCommittedState(context: Context, id: String) {
            com.mangalens.orez.agent.OrezTaskEventPublisher.committedDownload(context, id)
        }
    }
}

