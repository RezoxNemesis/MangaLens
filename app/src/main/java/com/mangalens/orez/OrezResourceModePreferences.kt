package com.mangalens.orez

import android.content.Context
import com.mangalens.core.compute.ResourceGovernorRuntime
import com.mangalens.core.compute.ResourcePressure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal data class OrezResourcePreferenceState(
    val loaded: Boolean = false,
    val mode: OrezResourceMode = OrezResourceMode.BALANCED,
    val saving: Boolean = false,
    val error: String? = null
)

/** App-owned preference IO survives screen disposal; there is no model or task mutation. */
internal class OrezResourceModePreferences(
    private val scope: CoroutineScope,
    private val read: suspend () -> String?,
    private val write: suspend (String) -> Boolean
) {
    private val guard = Any()
    private val mutable = MutableStateFlow(OrezResourcePreferenceState())
    val state: StateFlow<OrezResourcePreferenceState> = mutable.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val loaded = try { OrezResourcePreferenceState(true, OrezResourceRouting.parseMode(read())) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { OrezResourcePreferenceState(true, error = "Resource preference could not be read. Balanced is used for new requests.") }
            synchronized(guard) { mutable.value = loaded }
        }
    }

    /** Only one accepted write is outstanding, so IO completion cannot reorder accepted choices. */
    fun select(mode: OrezResourceMode): Boolean = synchronized(guard) {
        val before = mutable.value
        if (!before.loaded || before.saving || (before.mode == mode && before.error == null)) return@synchronized false
        mutable.value = before.copy(mode = mode, saving = true, error = null)
        scope.launch(Dispatchers.IO) {
            val saved = try { write(mode.name) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            synchronized(guard) {
                mutable.value = mutable.value.copy(saving = false, error = if (saved) null else
                    "This resource mode is active for this session, but could not be saved. Tap it again to retry.")
            }
        }
        true
    }

    suspend fun capture(task: OrezModelTask = OrezModelTask.CHAT): OrezRequestResources {
        currentCoroutineContext()[OrezRequestResources]?.let { return it.forTask(task) }
        // After cold preference loading, capture once before any model IO, mutex or native wait.
        val accepted = state.value.takeIf { it.loaded } ?: state.first { it.loaded }
        val pressure = when (ResourceGovernorRuntime.shared.snapshot().pressure) {
            ResourcePressure.NORMAL -> OrezRoutingPressure.NORMAL
            ResourcePressure.ELEVATED -> OrezRoutingPressure.ELEVATED
            ResourcePressure.CRITICAL -> OrezRoutingPressure.CRITICAL
        }
        return OrezRequestResources(accepted.mode, task, pressure)
    }

    companion object {
        private val stores = ConcurrentHashMap<String, OrezResourceModePreferences>()
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun get(context: Context): OrezResourceModePreferences {
            val app = context.applicationContext
            // No canonical path, preference creation, disk read or JNI is performed on this call site.
            val key = app.packageName
            return stores.computeIfAbsent(key) {
                OrezResourceModePreferences(appScope,
                    read = { app.getSharedPreferences("orez_resource_mode", Context.MODE_PRIVATE).getString("mode", null) },
                    write = { value -> app.getSharedPreferences("orez_resource_mode", Context.MODE_PRIVATE).edit().putString("mode", value).commit() })
            }
        }
    }
}
