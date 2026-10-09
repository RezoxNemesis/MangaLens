package com.mangalens.orez

internal data class OrezModelSwitchResult(val requestedLoaded: Boolean, val loadedPath: String?, val busy: Boolean)

internal object OrezModelLeaseSwitch {
    fun switch(
        requested: String,
        previous: String?,
        alreadyLoaded: Boolean,
        load: (String) -> Boolean,
        closeOwnLease: () -> Unit,
        sharedPath: () -> String?
    ): OrezModelSwitchResult {
        if (alreadyLoaded && previous == requested) return OrezModelSwitchResult(true, requested, false)
        if (alreadyLoaded) closeOwnLease()
        var failure: Exception? = null
        val success = try { load(requested) } catch (caught: Exception) { failure = caught; false }
        if (success) return OrezModelSwitchResult(true, requested, false)
        val busy = sharedPath()?.let { it != requested } == true
        val restored = if (alreadyLoaded && previous != null) {
            try { previous.takeIf { load(it) } } catch (_: Exception) { null }
        } else null
        // The old lease is restored even when a cancelled load adapter throws.
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        return OrezModelSwitchResult(false, restored, busy)
    }
}
