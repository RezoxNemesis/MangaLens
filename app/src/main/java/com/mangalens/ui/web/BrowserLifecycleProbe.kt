package com.mangalens.ui.web

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.webkit.WebView

/** Plain WebView in normal browsing; the explicit trace observes the real widget's super calls. */
internal fun createBrowserLifecycleWebView(context: Context): WebView {
    if (!BrowserLifecycleDiagnostics.enabled) return WebView(context)
    val ordinal = BrowserLifecycleDiagnostics.nextViewOrdinal()
    BrowserLifecycleDiagnostics.observe(BrowserLifecycleObservation(BrowserLifecyclePhase.VIEW_FACTORY_BEGIN, ordinal))
    return LifecycleObservedWebView(context, ordinal).also {
        browserLifecycleViewEvent(BrowserLifecyclePhase.VIEW_FACTORY_END, it)
    }
}

internal fun browserLifecycleViewEvent(phase: BrowserLifecyclePhase, view: WebView?) {
    if (!BrowserLifecycleDiagnostics.enabled) return
    runCatching {
        BrowserLifecycleDiagnostics.observe(BrowserLifecycleObservation(phase,
            viewOrdinal = (view as? LifecycleObservedWebView)?.ordinal ?: 0,
            attached = view?.isAttachedToWindow, width = view?.width, height = view?.height))
    }
}

private class LifecycleObservedWebView(context: Context, val ordinal: Int) : WebView(context) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        browserLifecycleViewEvent(BrowserLifecyclePhase.VIEW_ATTACHED, this)
    }

    override fun onDetachedFromWindow() {
        browserLifecycleViewEvent(BrowserLifecyclePhase.VIEW_DETACH_BEGIN, this)
        super.onDetachedFromWindow()
        browserLifecycleViewEvent(BrowserLifecyclePhase.VIEW_DETACHED, this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (BrowserLifecycleDiagnostics.enabled) {
            BrowserLifecycleDiagnostics.observe(BrowserLifecycleObservation(BrowserLifecyclePhase.VIEW_MEASURE_BEGIN,
                ordinal, isAttachedToWindow, View.MeasureSpec.getSize(widthMeasureSpec),
                View.MeasureSpec.getSize(heightMeasureSpec), View.MeasureSpec.getMode(widthMeasureSpec),
                View.MeasureSpec.getMode(heightMeasureSpec)))
        }
        var completed = false
        try { super.onMeasure(widthMeasureSpec, heightMeasureSpec); completed = true }
        finally {
            browserLifecycleViewEvent(if (completed) BrowserLifecyclePhase.VIEW_MEASURE_END else
                BrowserLifecyclePhase.VIEW_MEASURE_ABORT, this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        browserLifecycleViewEvent(BrowserLifecyclePhase.VIEW_DRAW_BEGIN, this)
        var completed = false
        try { super.onDraw(canvas); completed = true }
        finally {
            browserLifecycleViewEvent(if (completed) BrowserLifecyclePhase.VIEW_DRAW_END else
                BrowserLifecyclePhase.VIEW_DRAW_ABORT, this)
        }
    }
}
