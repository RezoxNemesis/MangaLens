package com.mangalens.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.room.InvalidationTracker
import com.mangalens.MainActivity
import com.mangalens.R
import com.mangalens.core.reader.ChapterCbzResources
import com.mangalens.download.DownloadDatabase
import com.mangalens.ui.downloads.OwnedSavedVideoProbe
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Event-coalesced application IO; no source reads/Room observer when these widgets are absent. */
internal object MangaLensWidgetUpdates {
    private data class Request(val app: Context, val sequence: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.CONFLATED)
    private val sequence = AtomicLong()
    private val completed = MutableStateFlow(0L)
    private val producer = OwnedSavedVideoProbe()
    private val hostGuard = Any()
    private val hosts = mutableSetOf<BroadcastReceiver.PendingResult>()
    private var hostFailure = false
    private var observedDatabase: DownloadDatabase? = null
    private var observer: InvalidationTracker.Observer? = null
    init {
        scope.launch {
            for (request in requests) {
                delay(400) // One finite batching delay per native hint; no polling.
                try { refresh(request.app) } catch (_: Exception) { renderUnavailable(request.app) }
                completed.value = maxOf(completed.value, request.sequence)
            }
        }
    }
    fun request(context: Context) { enqueue(context.applicationContext) }
    fun applicationStarted(context: Context) { request(context) }
    private fun enqueue(app: Context): Long {
        val token = sequence.incrementAndGet()
        requests.trySend(Request(app, token)); return token
    }
    /** Claim before goAsync; excess hosts remain framework-owned synchronous receivers. */
    fun fromHost(context: Context, acquire: () -> BroadcastReceiver.PendingResult) {
        val app = context.applicationContext
        val result = synchronized(hostGuard) {
            if (hostFailure || hosts.size >= 8) null else acquire().also { hosts.add(it) }
        }
        val token = enqueue(app)
        if (result == null) return
        scope.launch {
            try { withTimeout(7_000) { completed.first { it >= token } } }
            finally {
                try { result.finish(); synchronized(hostGuard) { hosts.remove(result) } }
                catch (_: Exception) { synchronized(hostGuard) { hostFailure = true } } // Retain failed host; no repeat/extra goAsync claims.
            }
        }
    }
    private fun component(app: Context, kind: ContinuationWidgetKind) = ComponentName(app, when (kind) {
        ContinuationWidgetKind.READING -> ContinueReadingWidget::class.java
        ContinuationWidgetKind.WATCHING -> ContinueWatchingWidget::class.java
        ContinuationWidgetKind.DOWNLOAD -> DownloadProgressWidget::class.java
    })
    private fun active(app: Context): Set<ContinuationWidgetKind> {
        val manager = AppWidgetManager.getInstance(app)
        return ContinuationWidgetKind.entries.filter { manager.getAppWidgetIds(component(app, it)).isNotEmpty() }.toSet()
    }
    private suspend fun refresh(app: Context) {
        val kinds = active(app)
        updateObserver(app, ContinuationWidgetKind.DOWNLOAD in kinds)
        if (kinds.isEmpty()) return
        withTimeout(7_000) {
            producer.run { owner ->
                val resources = ChapterCbzResources()
                try {
                    owner.own(resources); resources.beginPrivateWork()
                    val snapshots = kinds.associateWith { kind ->
                        try {
                            when (kind) {
                                ContinuationWidgetKind.READING -> WidgetContinuationFiles.reading(app, resources, owner::checkActive)
                                ContinuationWidgetKind.WATCHING -> WidgetContinuationFiles.watching(app, resources, owner::checkActive)
                                ContinuationWidgetKind.DOWNLOAD -> WidgetContinuationFiles.download(checkNotNull(observedDatabase), resources, owner::checkActive)
                            }
                        } catch (failure: Exception) {
                            owner.checkActive()
                            if (!resources.privateReleaseProven()) throw IOException("Widget source release is unproven.", failure)
                            MangaLensWidgetPolicy.unavailable(kind)
                        }
                    }
                    if (!resources.privateReleaseProven()) throw IOException("Widget source release is unproven.")
                    snapshots.forEach { (kind, snapshot) -> owner.checkActive(); render(app, kind, snapshot) }
                    owner.checkActive()
                } finally { resources.finishPrivateWork() }
            }
        }
    }
    private fun updateObserver(app: Context, enabled: Boolean) {
        if (enabled && observer == null) {
            val database = DownloadDatabase.get(app)
            val next = object : InvalidationTracker.Observer("media_downloads") {
                override fun onInvalidated(tables: Set<String>) { request(app) }
            }
            database.invalidationTracker.addObserver(next); observedDatabase = database; observer = next
        } else if (!enabled && observer != null) {
            observedDatabase?.invalidationTracker?.removeObserver(checkNotNull(observer)); observer = null; observedDatabase = null
        }
    }
    private fun renderUnavailable(app: Context) {
        try { active(app).forEach { kind -> render(app, kind, MangaLensWidgetPolicy.unavailable(kind)) } } catch (_: Exception) {}
    }
    private fun render(app: Context, kind: ContinuationWidgetKind, snapshot: ContinuationWidgetSnapshot) {
        val label = when (kind) { ContinuationWidgetKind.READING -> "Continue Reading"; ContinuationWidgetKind.WATCHING -> "Continue Watching"; ContinuationWidgetKind.DOWNLOAD -> "Download Progress" }
        val views = RemoteViews(app.packageName, R.layout.mangalens_continuation_widget)
        views.setTextViewText(R.id.widget_continuation_kind, label)
        views.setTextViewText(R.id.widget_continuation_title, snapshot.title)
        views.setTextViewText(R.id.widget_continuation_detail, snapshot.detail)
        views.setContentDescription(R.id.widget_continuation_open, "$label: ${snapshot.title}. ${snapshot.detail}")
        views.setViewVisibility(R.id.widget_continuation_progress, if (snapshot.percent == null) View.GONE else View.VISIBLE)
        snapshot.percent?.let { views.setProgressBar(R.id.widget_continuation_progress, 100, it, false) }
        val data = when (val entry = snapshot.entry) {
            is WidgetNativeEntry.Reader -> "mangalens://reader?chapter=${entry.id}"
            is WidgetNativeEntry.RecentVideo -> "mangalens://recent-video?key=${entry.key}"
            is WidgetNativeEntry.Download -> "mangalens://downloads?focus=${entry.id}"
            null -> if (kind == ContinuationWidgetKind.READING) "mangalens://widget/library" else if (kind == ContinuationWidgetKind.WATCHING) "mangalens://widget/local_video" else "mangalens://widget/downloads"
        }
        val intent = Intent(app, MainActivity::class.java).setData(Uri.parse(data)).setAction(if (snapshot.entry == null) "com.mangalens.WIDGET" else Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        views.setOnClickPendingIntent(R.id.widget_continuation_open, PendingIntent.getActivity(app, 701 + kind.ordinal, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val refresh = Intent().setComponent(component(app, kind)).setAction(CONTINUATION_WIDGET_REFRESH)
            .setData(Uri.parse("mangalens://widget-refresh/${kind.name.lowercase()}"))
        views.setOnClickPendingIntent(R.id.widget_continuation_refresh, PendingIntent.getBroadcast(app, 711 + kind.ordinal, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        AppWidgetManager.getInstance(app).updateAppWidget(component(app, kind), views)
    }
}
