package com.mangalens.core.verification

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.webkit.WebSettings

/**
 * Interactive, user-driven verification surface.
 *
 * This does not bypass a challenge. The user completes the site's own challenge,
 * after which the WebView session cookie is handed back to the acquisition layer.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VerificationDialog(
    request: VerificationRequest,
    onVerified: (cookie: String, userAgent: String) -> Unit,
    onDismiss: () -> Unit
) {
    val cookieState = remember { mutableStateOf("") }
    val userAgentState = remember { mutableStateOf("") }
    val loadingState = remember { mutableStateOf(true) }
    val webViewState = remember { mutableStateOf<WebView?>(null) }

    fun captureSession(webView: WebView) {
        val cookies = CookieManager.getInstance().getCookie(request.url).orEmpty()
        val userAgent = webView.settings.userAgentString.orEmpty()
        cookieState.value = cookies
        userAgentState.value = userAgent

        if (cookies.contains("cf_clearance=", ignoreCase = true)) {
            onVerified(cookies, userAgent)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(620.dp)
            ) {
                Text(
                    text = "🛡️ Verification Required",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Cloudflare security check triggered. Solve below once to resume automated extraction.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(480.dp),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.cacheMode = WebSettings.LOAD_DEFAULT

                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?
                                ) {
                                    loadingState.value = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loadingState.value = false
                                    view?.let { captureSession(it) }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean = false
                            }

                            webViewState.value = this
                            loadUrl(request.url)
                        }
                    },
                    update = { view ->
                        if (webViewState.value == null) {
                            webViewState.value = view
                        }
                    }
                )

                if (loadingState.value) {
                    CircularProgressIndicator()
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(
                        enabled = cookieState.value.isNotBlank(),
                        onClick = {
                            val webView = webViewState.value
                            if (webView != null) {
                                captureSession(webView)
                                val cookie = cookieState.value
                                if (cookie.isNotBlank()) {
                                    onVerified(
                                        cookie,
                                        userAgentState.value.ifBlank {
                                            webView.settings.userAgentString.orEmpty()
                                        }
                                    )
                                }
                            }
                        }
                    ) {
                        Text("✓ Verification Complete - Resuming")
                    }
                }
            }
        }
    }

    LaunchedEffect(request.url) {
        webViewState.value?.let { captureSession(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewState.value?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
            webViewState.value = null
        }
    }
}
