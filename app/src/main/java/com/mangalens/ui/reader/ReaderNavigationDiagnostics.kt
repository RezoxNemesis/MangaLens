package com.mangalens.ui.reader

internal data class ReaderNavigationObservation(
    val action: String,
    val chapterId: String,
    val position: ReaderPositionState,
    val physicalMode: String,
    val physicalPage: Int,
    val physicalOffset: Int,
    val geometryChecked: Boolean,
    val controls: Boolean,
    val hudVisible: Boolean,
    val expectedRequest: Long? = null,
    val targetPage: Int? = null
)

/** Inert in normal reading. Only a controlled fixture installs an observer. */
internal object ReaderNavigationDiagnostics {
    @Volatile private var listener: ((ReaderNavigationObservation) -> Unit)? = null
    val enabled: Boolean get() = listener != null

    @Synchronized fun install(observer: (ReaderNavigationObservation) -> Unit): AutoCloseable {
        listener = observer
        return AutoCloseable { synchronized(this) { if (listener === observer) listener = null } }
    }

    fun observe(event: ReaderNavigationObservation) {
        val observer = listener ?: return
        // Fixture diagnostics must never change a real gesture or restoration outcome.
        runCatching { observer(event) }
    }
}
