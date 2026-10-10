package com.mangalens.ui.reader

internal enum class ReaderWindowOrientation { SYSTEM, PORTRAIT, LANDSCAPE }

/** Defaults delegate to the existing window owner; they do not set system defaults. */
internal data class ReaderWindowSettings(
    val orientation: ReaderWindowOrientation = ReaderWindowOrientation.SYSTEM,
    val rotationLocked: Boolean = false,
    val brightnessOverride: Float? = null,
    val keepScreenOn: Boolean = false
) {
    init {
        require(brightnessOverride == null ||
            (brightnessOverride.isFinite() && brightnessOverride in 0f..1f)) {
            "Reader brightness must be a finite value from 0 to 1"
        }
    }
}

/** Property operations must run on the host's owner thread (Main for Android windows). */
internal interface ReaderWindowHost {
    var requestedOrientation: Int
    var screenBrightness: Float
    var keepScreenOn: Boolean
}

/**
 * Tracks only window fields this Reader actually changed. It shares no state with the player.
 * Repeated settings are inert, so a recomposition cannot reassert a field changed by another owner.
 * On removal or deactivation, a field is restored only if it still equals our last applied value.
 * A new explicit setting can acquire a fresh lease from the value then observed on the window.
 */
internal class ReaderWindowLeasePolicy(host: ReaderWindowHost) {
    private val orientation = OwnedField(
        read = { host.requestedOrientation },
        write = { host.requestedOrientation = it }
    )
    private val brightness = OwnedField(
        read = { host.screenBrightness },
        write = { host.screenBrightness = it }
    )
    private val awake = OwnedField(
        read = { host.keepScreenOn },
        write = { host.keepScreenOn = it }
    )

    fun update(settings: ReaderWindowSettings) {
        orientation.update(settings.requestedOrientation())
        brightness.update(settings.brightnessOverride)
        awake.update(if (settings.keepScreenOn) true else null)
    }

    /** Releases every field even if an individual host restore throws; safe to call repeatedly. */
    fun deactivate() {
        try {
            orientation.deactivate()
        } finally {
            try {
                brightness.deactivate()
            } finally {
                awake.deactivate()
            }
        }
    }

    // These are ActivityInfo's stable orientation values. Keeping them here makes the ownership
    // policy independent of Android; ReaderWindowLease is the Android property adapter.
    private fun ReaderWindowSettings.requestedOrientation(): Int? = when (orientation) {
        ReaderWindowOrientation.SYSTEM -> if (rotationLocked) 14 else null // LOCKED
        ReaderWindowOrientation.PORTRAIT -> if (rotationLocked) 1 else 7 // PORTRAIT / SENSOR_PORTRAIT
        ReaderWindowOrientation.LANDSCAPE -> if (rotationLocked) 0 else 6 // LANDSCAPE / SENSOR_LANDSCAPE
    }

    private class OwnedField<T : Any>(
        private val read: () -> T,
        private val write: (T) -> Unit
    ) {
        private data class Ownership<T>(val previous: T, val applied: T)
        private var ownership: Ownership<T>? = null
        private var requested: T? = null
        private var hasRequest = false

        fun update(desired: T?) {
            if (hasRequest && requested == desired) return
            val priorOwnership = ownership
            if (priorOwnership != null) {
                val current = read()
                if (current == priorOwnership.applied) {
                    if (desired == null) {
                        if (current != priorOwnership.previous) write(priorOwnership.previous)
                        ownership = null
                    } else {
                        if (current != desired) write(desired)
                        ownership = priorOwnership.copy(applied = desired)
                    }
                    requested = desired
                    hasRequest = true
                    return
                }
                // A different value belongs to someone else. A newly selected explicit control
                // may acquire it as its new baseline; defaults leave it unchanged.
                ownership = null
            }
            if (desired != null) {
                val current = read()
                if (current != desired) {
                    write(desired)
                    ownership = Ownership(previous = current, applied = desired)
                }
            }
            requested = desired
            hasRequest = true
        }

        fun deactivate() {
            try {
                val owned = ownership ?: return
                if (read() == owned.applied && owned.applied != owned.previous) write(owned.previous)
            } finally {
                ownership = null
                requested = null
                hasRequest = false
            }
        }
    }
}
