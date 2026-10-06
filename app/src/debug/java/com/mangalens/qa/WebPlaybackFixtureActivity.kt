package com.mangalens.qa

import android.app.Activity
import android.os.Bundle
import android.webkit.*
import com.mangalens.core.web.SafeWebView
import java.io.File

/** Own app-private sample served as an HTTPS-origin test resource, without a network/TLS override. */
class WebPlaybackFixtureActivity : Activity() {
    private var web: WebView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ready = false
        val file = File(intent.getStringExtra("sample_video") ?: error("No test sample"))
        require(file.canonicalPath.startsWith(filesDir.canonicalPath + File.separator))
        val view = WebView(this)
        web = view
        SafeWebView.configure(view)
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                if (request?.url?.toString() == "https://mangalens.test/sample.mp4")
                    WebResourceResponse("video/mp4", null, file.inputStream()) else null
            override fun onPageFinished(view: WebView?, url: String?) { ready = true }
        }
        setContentView(view)
        view.loadDataWithBaseURL("https://mangalens.test/", """
            <html><meta name="viewport" content="width=device-width,initial-scale=1">
            <body style="margin:0;background:black"><video id="v" controls playsinline style="width:100%;height:100vh" src="sample.mp4"></video>
            <button style="position:fixed;inset:0;width:100%;height:100%;font-size:30px" onclick="this.style.display='none';document.getElementById('v').play()">Play QA sample</button></body></html>
        """.trimIndent(), "text/html", "UTF-8", null)
    }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
    companion object { @Volatile var ready = false }
}
