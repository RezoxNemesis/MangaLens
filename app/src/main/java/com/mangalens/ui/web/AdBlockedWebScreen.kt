package com.mangalens.ui.web

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockWebViewClient
import com.mangalens.core.translation.WebTranslationScript
import com.mangalens.download.MediaDownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdBlockedWebScreen(url: String, translationEnabled: Boolean, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { AdBlockEngine() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var autoScroll by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var translated by remember { mutableStateOf(translationEnabled) }
    var hudVisible by remember { mutableStateOf(true) }

    LaunchedEffect(autoScroll, speed, webView) {
        while (autoScroll && webView != null) {
            webView?.evaluateJavascript("window.scrollBy(0, " + (2.5f * speed) + ");", null)
            delay(16L)
        }
    }
    LaunchedEffect(hudVisible) {
        if (hudVisible) {
            delay(2000L)
            hudVisible = false
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_UP) hudVisible = true
                        false
                    }
                    webViewClient = object : AdBlockWebViewClient(engine) {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            super.onPageFinished(view, pageUrl)
                            if (translated) view?.evaluateJavascript(WebTranslationScript.build("hi"), null)
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
            update = { view ->
                webView = view
                if (translated) view.evaluateJavascript(WebTranslationScript.build("hi"), null)
            }
        )

        if (hudVisible) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                tonalElevation = 6.dp,
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = {
                        translated = !translated
                        webView?.evaluateJavascript(
                            if (translated) WebTranslationScript.build("hi")
                            else "window.__mangalensTranslationOff && window.__mangalensTranslationOff();",
                            null
                        )
                    }) { Text(if (translated) "Translated" else "Translate") }
                    Button(onClick = {
                        scope.launch {
                            runCatching {
                                MediaDownloadManager(context).enqueue(url, "MangaLens web page")
                            }
                        }
                    }) { Text("Download") }
                    Button(onClick = { autoScroll = !autoScroll }) {
                        Text(if (autoScroll) "Pause" else "Scroll")
                    }
                    if (autoScroll) {
                        Slider(value = speed, onValueChange = { speed = it }, valueRange = 1f..5f, steps = 3, modifier = Modifier.width(110.dp))
                    }
                }
            }
        }
    }
}
