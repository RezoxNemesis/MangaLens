package com.mangalens

import com.mangalens.ui.web.WebNavigationObservation
import java.net.URI
import java.util.Base64
import java.util.Locale

internal class WebNavigationFixtureScope(origin: String) {
    private val fixture = URI(origin)
    val origin: String = "${fixture.scheme.lowercase(Locale.ROOT)}://${fixture.rawAuthority.lowercase(Locale.ROOT)}"

    init {
        require(fixture.scheme == "http" && fixture.host in setOf("localhost", "127.0.0.1") && fixture.rawUserInfo == null)
    }

    fun accepts(url: String?): Boolean {
        if (url.isNullOrEmpty() || url == "about:blank") return true
        return runCatching {
            val candidate = URI(url)
            candidate.rawUserInfo == null && candidate.scheme?.lowercase(Locale.ROOT) == fixture.scheme &&
                candidate.rawAuthority?.lowercase(Locale.ROOT) == fixture.rawAuthority.lowercase(Locale.ROOT)
        }.getOrDefault(false)
    }

    fun redacted(url: String?): String = if (accepts(url)) url.orEmpty() else "[non-fixture]"

    fun observationValues(event: WebNavigationObservation): Map<String, String>? {
        val requested = event.state.navigation?.url ?: event.targetUrl ?: event.currentUrl
        if (!accepts(requested)) return null
        return mapOf(
            "ticket_epoch" to event.state.navigation?.epoch.toString(),
            "ticket_url" to redacted(event.state.navigation?.url),
            "phase" to event.state.phase.name, "error" to event.state.error.orEmpty(),
            "current_url" to redacted(event.currentUrl), "visible_url" to redacted(event.visibleUrl),
            "callback_url" to redacted(event.callbackUrl), "target_url" to redacted(event.targetUrl),
            "detail" to event.detail, "view_identity" to event.viewIdentity.toString()
        )
    }

    // Guard in the renderer as well as before dispatch: navigation may change while JS is queued.
    // ES5 syntax supports the actual API-28 device's Chromium 66 provider.
    fun domSnapshotScript(): String = """
        (function(){
          var loc=window.location;
          if(loc.href!=='about:blank' && loc.protocol+'//'+loc.host!== '$origin'){
            return JSON.stringify({skipped:'non-fixture'});
          }
          var body=document.body, h=document.querySelector('h1'), rect=h?h.getBoundingClientRect():null;
          var style=h?window.getComputedStyle(h):null;
          return JSON.stringify({url:loc.href,readyState:document.readyState,title:document.title,
            bodyText:body?(body.innerText||'').slice(0,1400):null,htmlLength:document.documentElement?document.documentElement.outerHTML.length:0,
            heading:h?h.textContent:null,headingBounds:rect?{left:rect.left,top:rect.top,width:rect.width,height:rect.height}:null,
            headingStyle:style?{display:style.display,visibility:style.visibility,opacity:style.opacity}:null,
            links:document.links.length,images:document.images.length,viewport:{width:window.innerWidth,height:window.innerHeight}});
        })();
    """.trimIndent()
}

/** A bounded, reversible stdout transport when the device artifact cannot be downloaded. */
internal fun webNavigationTraceStdout(
    events: List<String>, droppedEvents: Int, maxBytes: Int = 64_000, chunkChars: Int = 3000
): List<String> {
    require(maxBytes in 256..64_000 && chunkChars in 1..3000 && droppedEvents >= 0)
    var available = maxBytes - 256 // The fixed envelope and integer counters fit within this reserve.
    val retained = ArrayList<String>()
    for (event in events.asReversed()) {
        val bytes = event.toByteArray(Charsets.UTF_8).size + 1
        if (bytes <= available) {
            retained += event
            available -= bytes
        }
    }
    retained.reverse()
    val payload = "{\"events\":[${retained.joinToString(",")}],\"dropped_events\":$droppedEvents," +
        "\"stdout_omitted_events\":${events.size - retained.size}}"
    val chunks = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8)).chunked(chunkChars)
    return chunks.mapIndexed { index, chunk -> "MANGALENS_WEB_TRACE part=${index + 1}/${chunks.size} data=$chunk" }
}
