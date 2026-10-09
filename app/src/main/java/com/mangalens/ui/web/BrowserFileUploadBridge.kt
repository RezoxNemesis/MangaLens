package com.mangalens.ui.web

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberBrowserFileUploadBridge(
    currentScope: () -> BrowserUploadScope?, onStatus: (String) -> Unit
): BrowserFileUploadBridge {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val latestScope by rememberUpdatedState(currentScope)
    val latestStatus by rememberUpdatedState(onStatus)
    val bridge = remember(context, scope) { BrowserFileUploadBridge(context, scope, { latestScope() }, { latestStatus(it) }) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult(), bridge::consume)
    DisposableEffect(bridge, launcher) {
        bridge.launch = { launcher.launch(it) }
        onDispose { bridge.cancel(); bridge.launch = null }
    }
    return bridge
}

internal class BrowserFileUploadBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentScope: () -> BrowserUploadScope?,
    private val status: (String) -> Unit
) {
    private val gate = BrowserUploadGate()
    private var launched: BrowserUploadRequest? = null
    var launch: ((Intent) -> Unit)? = null

    fun show(callback: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?): Boolean {
        if (callback == null) return false
        val owner = currentScope()
        if (params == null || owner == null || launch == null) { callback.onReceiveValue(null); return true }
        val request = gate.begin(owner, params.acceptTypes.orEmpty().toList(), params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) { chosen ->
            runCatching { callback.onReceiveValue(chosen?.map(Uri::parse)?.toTypedArray()) }
            if (chosen != null) status("File selected for this page.")
        } ?: return true
        val mimeTypes = (request.types.mimeTypes + request.types.extensions.mapNotNull {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.removePrefix("."))
        }).distinct()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (mimeTypes.size == 1) mimeTypes.single() else "*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.multiple)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (mimeTypes.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        launched = request
        try { launch!!.invoke(intent) }
        catch (_: Exception) {
            launched = null
            if (gate.finish(request.token, currentScope(), null) == BrowserUploadFinish.REJECTED)
                status("No document chooser is available. Try another app or file provider.")
        }
        return true
    }

    fun cancel() { gate.cancel() }

    fun consume(result: ActivityResult) {
        val request = launched ?: return
        launched = null
        if (result.resultCode != Activity.RESULT_OK) { gate.finish(request.token, currentScope(), null); return }
        val data = result.data
        val clip = data?.clipData
        val uris = if (clip != null) (0 until minOf(clip.itemCount, 17)).map { clip.getItemAt(it).uri }
            else listOfNotNull(data?.data)
        scope.launch {
            val documents = withContext(Dispatchers.IO) { uris.distinct().map(::inspect) }
            if (gate.finish(request.token, currentScope(), documents) == BrowserUploadFinish.REJECTED)
                status("This document cannot be used here. Choose a readable file matching the page's requested type.")
        }
    }

    private fun inspect(uri: Uri): BrowserUploadSelection {
        if (uri.scheme != "content") return BrowserUploadSelection(uri.toString(), null, null, false)
        return try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri)
            var name: String? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it)?.take(512) }
            }
            val readable = mime != "vnd.android.document/directory" && resolver.openFileDescriptor(uri, "r")?.use { true } == true
            BrowserUploadSelection(uri.toString(), mime, name, readable)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { BrowserUploadSelection(uri.toString(), null, null, false) }
    }
}
