package com.mangalens.ui.reader

/** Logical selection is accepted before layout work and cannot be overwritten by an older request. */
internal data class ReaderPositionState(
    val mode: String,
    val page: Int,
    val offset: Int,
    val request: Long = 0,
    val restoring: Boolean = true,
    val animate: Boolean = false
) {
    fun navigate(target: Int, count: Int): ReaderPositionState {
        if (count <= 0) return this
        return copy(page = target.coerceIn(0, count - 1), offset = 0, request = request + 1,
            restoring = true, animate = true)
    }

    fun switchMode(next: String, physicalPage: Int, physicalOffset: Int, count: Int): ReaderPositionState {
        if (next !in MODES || next == mode) return this
        // A pending Next owns its logical selection even while the previous layout still shows another page.
        val selected = if (restoring) page else physicalPage
        return copy(mode = next, page = if (count > 0) selected.coerceIn(0, count - 1) else selected,
            offset = 0, request = request + 1, restoring = true, animate = false)
    }

    fun restored(captured: ReaderPositionState, physicalPage: Int, physicalOffset: Int, count: Int,
        geometryChecked: Boolean = true): ReaderPositionState {
        if (!geometryChecked || captured.request != request || captured.mode != mode || count <= 0) return this
        return copy(page = physicalPage.coerceIn(0, count - 1),
            offset = if (mode == "vertical") physicalOffset.coerceAtLeast(0) else 0,
            restoring = false, animate = false)
    }

    fun observed(expectedRequest: Long, expectedMode: String, physicalPage: Int, physicalOffset: Int, count: Int): ReaderPositionState {
        if (restoring || expectedRequest != request || expectedMode != mode || count <= 0) return this
        return copy(page = physicalPage.coerceIn(0, count - 1),
            offset = if (mode == "vertical") physicalOffset.coerceAtLeast(0) else 0)
    }

    fun checkpoint(): ReaderPositionCheckpoint? =
        if (restoring) null else ReaderPositionCheckpoint(mode, page, offset, request)

    fun acceptsCheckpoint(sample: ReaderPositionCheckpoint): Boolean = checkpoint() == sample

    companion object {
        private val MODES = setOf("vertical", "ltr", "rtl")

        /** Restored saveable state always waits for the new Activity's real layout. */
        fun initial(mode: String, page: Int, offset: Int): ReaderPositionState {
            val supported = mode.takeIf { it in MODES } ?: "vertical"
            return ReaderPositionState(supported, page.coerceAtLeast(0),
                if (supported == "vertical") offset.coerceAtLeast(0) else 0)
        }
    }
}

internal data class ReaderPositionCheckpoint(val mode: String, val page: Int, val offset: Int, val request: Long)
