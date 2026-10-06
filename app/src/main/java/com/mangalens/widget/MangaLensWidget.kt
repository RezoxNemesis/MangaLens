package com.mangalens.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mangalens.MainActivity
import com.mangalens.R

/** User-triggered shortcuts only: no wake locks, network work or periodic polling. */
class MangaLensWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.mangalens_widget)
            for ((view, action) in listOf(R.id.widget_translate to "translate", R.id.widget_orez to "orez", R.id.widget_library to "library", R.id.widget_downloads to "downloads")) {
                val intent = Intent(context, MainActivity::class.java).setAction("com.mangalens.WIDGET")
                    .setData(android.net.Uri.parse("mangalens://widget/$action"))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                views.setOnClickPendingIntent(view, PendingIntent.getActivity(context, id * 10 + view, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(id, views)
        }
    }
}
