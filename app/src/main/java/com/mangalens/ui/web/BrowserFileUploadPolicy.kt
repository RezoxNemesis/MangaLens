package com.mangalens.ui.web

import java.net.URI
import java.util.Locale

internal data class BrowserUploadScope(val tabId: String, val navigationEpoch: Long, val pageUrl: String, val profileKey: String = "normal")
internal data class BrowserUploadTypes(val mimeTypes: List<String>, val extensions: List<String>)
internal data class BrowserUploadRequest(val token: String, val scope: BrowserUploadScope, val types: BrowserUploadTypes, val multiple: Boolean)
internal data class BrowserUploadSelection(val uri: String, val mimeType: String?, val displayName: String?, val readable: Boolean)
internal enum class BrowserUploadFinish { ACCEPTED, REJECTED, STALE, IGNORED }

internal class BrowserUploadGate {
    private data class Pending(val request: BrowserUploadRequest, val callback: (List<String>?) -> Unit)
    private var awaiting: BrowserUploadRequest? = null
    private var pending: Pending? = null

    @Synchronized fun begin(
        scope: BrowserUploadScope, accepts: List<String>, multiple: Boolean, callback: (List<String>?) -> Unit
    ): BrowserUploadRequest? {
        if (awaiting != null || !BrowserProfilePolicy.validKey(scope.profileKey) || !Regex("[a-f0-9]{32}").matches(scope.tabId) || scope.navigationEpoch <= 0 ||
            runCatching { requireBrowserUrl(scope.pageUrl) }.isFailure) {
            callback(null)
            return null
        }
        val request = BrowserUploadRequest(browserId(), scope, browserUploadTypes(accepts), multiple)
        awaiting = request
        pending = Pending(request, callback)
        return request
    }

    @Synchronized fun finish(token: String, current: BrowserUploadScope?, selection: List<BrowserUploadSelection>?): BrowserUploadFinish {
        val request = awaiting?.takeIf { it.token == token } ?: return BrowserUploadFinish.IGNORED
        awaiting = null
        val accepted = pending?.takeIf { it.request.token == token }
        pending = null
        if (accepted == null) return BrowserUploadFinish.IGNORED
        val documents = selection?.distinctBy { it.uri }
        val valid = current == request.scope && !documents.isNullOrEmpty() && documents.size <= 16 &&
            (request.multiple || documents.size == 1) && documents.all { selected ->
                selected.readable && safeContentUri(selected.uri) && request.types.accepts(selected)
            }
        accepted.callback(if (valid) documents!!.map { it.uri } else null)
        return if (current != request.scope) BrowserUploadFinish.STALE
            else if (valid) BrowserUploadFinish.ACCEPTED else BrowserUploadFinish.REJECTED
    }

    /** The platform result remains reserved until it returns, even after the page callback is cancelled. */
    @Synchronized fun cancel() {
        val cancelled = pending
        pending = null
        cancelled?.callback?.invoke(null)
    }
}

internal fun browserUploadTypes(raw: List<String>): BrowserUploadTypes {
    val patterns = raw.take(16).flatMap { it.take(1024).split(',') }.take(32).map { it.trim().lowercase(Locale.ROOT) }
    val mime = patterns.filter { it.length <= 96 && Regex("(?:[a-z0-9.+-]+|\\*)/(?:[a-z0-9.+-]+|\\*)").matches(it) &&
        (!it.startsWith("*/") || it == "*/*") }.distinct().take(16)
    val extensions = patterns.filter { Regex("\\.[a-z0-9]{1,16}").matches(it) }.distinct().take(16)
    return BrowserUploadTypes(if (mime.isEmpty() && extensions.isEmpty()) listOf("*/*") else mime, extensions)
}

private fun safeContentUri(value: String): Boolean = value.length in 1..BrowserWorkspaceLimits.URL_CHARS &&
    value.none { it.code < 32 || it.code == 127 } && runCatching {
        val uri = URI(value)
        uri.scheme == "content" &&
            uri.rawAuthority?.let { Regex("[A-Za-z0-9._-]{1,255}").matches(it) } == true &&
            uri.rawUserInfo == null && !uri.rawPath.isNullOrBlank()
    }.getOrDefault(false)

private fun BrowserUploadTypes.accepts(selection: BrowserUploadSelection): Boolean {
    val mime = selection.mimeType?.trim()?.lowercase(Locale.ROOT)?.takeIf {
        it.length <= 96 && Regex("[a-z0-9.+-]+/[a-z0-9.+-]+").matches(it)
    }
    if (mimeTypes.any { it == "*/*" || it == mime || (it.endsWith("/*") && mime?.startsWith(it.removeSuffix("*")) == true) }) return true
    val name = selection.displayName?.takeIf { it.length <= 512 && it.none { c -> c.code < 32 || c.code == 127 } }
        ?.lowercase(Locale.ROOT)
    return name != null && extensions.any { name.endsWith(it) }
}
