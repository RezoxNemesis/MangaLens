package com.mangalens.ui.web

import android.content.Context
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal data class BrowserProfileCatalogState(val custom: List<BrowserProfileChoice> = emptyList(), val loading: Boolean = true, val error: String? = null)
internal object BrowserProfileCatalogCodec {
    fun encode(custom: List<BrowserProfileChoice>): ByteArray {
        require(custom.size <= BrowserProfilePolicy.MAX_CUSTOM && custom.map { it.token }.distinct().size == custom.size)
        return JSONObject().put("version", 1).put("custom", JSONArray(custom.map {
            require(it.kind == BrowserProfileKind.CUSTOM); it.validate(); JSONObject().put("id", it.token).put("label", it.label)
        })).toString().toByteArray(Charsets.UTF_8).also { require(it.size <= BrowserProfilePolicy.CATALOG_BYTES) }
    }
    fun decode(bytes: ByteArray): List<BrowserProfileChoice> {
        require(bytes.size in 1..BrowserProfilePolicy.CATALOG_BYTES)
        val root = JSONObject(bytes.toString(Charsets.UTF_8)); require(root.keys().asSequence().toSet() == setOf("version", "custom"))
        require(root.get("version") == 1); val array = root.getJSONArray("custom"); require(array.length() <= BrowserProfilePolicy.MAX_CUSTOM)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index); require(item.keys().asSequence().toSet() == setOf("id", "label"))
            BrowserProfileChoice(BrowserProfileKind.CUSTOM, item.get("id") as String, item.get("label") as String).validate()
        }.also { require(it.map { profile -> profile.token }.distinct().size == it.size) }
    }
}
internal class BrowserProfileCatalog private constructor(app: Context) {
    private val io = BrowserProfileJournal(File(app.filesDir, "browser_profiles/catalog.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adds = Channel<Pair<String, CompletableDeferred<BrowserProfileChoice>>>(8)
    private val mutable = MutableStateFlow(BrowserProfileCatalogState())
    val state: StateFlow<BrowserProfileCatalogState> = mutable
    init { scope.launch {
        var custom: List<BrowserProfileChoice> = emptyList(); var readable = false
        try { custom = io.read(BrowserProfilePolicy.CATALOG_BYTES)?.let(BrowserProfileCatalogCodec::decode).orEmpty(); readable = true; mutable.value = BrowserProfileCatalogState(custom, false) }
        catch (_: Exception) { mutable.value = BrowserProfileCatalogState(loading = false, error = "Saved profiles could not be read safely. Their data is retained; restart before retrying.") }
        for ((label, result) in adds) {
            try {
                check(readable) { "Saved profiles are unavailable. Their data is retained." }
                check(custom.size < BrowserProfilePolicy.MAX_CUSTOM) { "Four Custom profiles already exist. Use or clear an existing profile." }
                val profile = BrowserProfileChoice(BrowserProfileKind.CUSTOM, browserId(), label.trim()).validate()
                val next = custom + profile; io.write(BrowserProfileCatalogCodec.encode(next)); custom = next
                mutable.value = BrowserProfileCatalogState(custom, false); result.complete(profile)
            } catch (failure: Exception) { result.completeExceptionally(failure); mutable.value = mutable.value.copy(error = "The profile could not be saved. Existing profiles remain unchanged.") }
        }
    } }
    fun add(label: String): CompletableDeferred<BrowserProfileChoice> {
        val result = CompletableDeferred<BrowserProfileChoice>()
        if (label.trim().length !in 1..40 || label.any(Char::isISOControl)) {
            result.completeExceptionally(IllegalArgumentException("Use a profile name of 1–40 characters without control characters.")); return result
        }
        if (!adds.trySend(label to result).isSuccess) result.completeExceptionally(IllegalStateException("Profile changes are busy. Retry shortly."))
        return result
    }
    companion object {
        @Volatile private var instance: BrowserProfileCatalog? = null
        fun shared(context: Context) = instance ?: synchronized(this) { instance ?: BrowserProfileCatalog(context.applicationContext).also { instance = it } }
    }
}
