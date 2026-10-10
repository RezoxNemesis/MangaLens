package com.mangalens.ui.web

import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

/** The existing visible WebView executes a fixed script; no JavaScript bridge is installed. */
internal class BrowserDomWebViewHost(override val owner: BrowserDomOwner, private val view: WebView,
    private val stillOwned: () -> Boolean,
    private val navigation: suspend (String, () -> Boolean) -> Boolean) : BrowserDomLiveHost {
    override suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main.immediate) {
        if (!stillOwned() || !view.isAttachedToWindow) return@withContext null
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { encoded ->
                if (continuation.isActive) continuation.resume(if (stillOwned())
                    runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull() else null)
            }
        }
    }
    override suspend fun navigate(target: String, stillExecuting: () -> Boolean): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!stillOwned() || !stillExecuting()) false else navigation(target, stillExecuting)
    }
}
