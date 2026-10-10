package com.mangalens.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

internal const val CONTINUATION_WIDGET_REFRESH = "com.mangalens.widget.REFRESH_CONTINUATION"

/** Native user-added surfaces; app/system updates are finite hints, never periodic work. */
abstract class MangaLensContinuationWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        MangaLensWidgetUpdates.fromHost(context) { goAsync() }
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CONTINUATION_WIDGET_REFRESH) MangaLensWidgetUpdates.fromHost(context) { goAsync() }
        else super.onReceive(context, intent)
    }
    override fun onDisabled(context: Context) { MangaLensWidgetUpdates.request(context) }
}
class ContinueReadingWidget : MangaLensContinuationWidget()
class ContinueWatchingWidget : MangaLensContinuationWidget()
class DownloadProgressWidget : MangaLensContinuationWidget()
