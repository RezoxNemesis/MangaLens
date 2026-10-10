package com.mangalens.ui.reader

internal enum class ReaderComparison { TRANSLATED, ORIGINAL, SIDE_BY_SIDE, SPLIT, HOLD_PEEK }

/** Display choices cannot alter native/source geometry or translation credentials. Defaults retain the old Reader. */
internal data class ReaderDisplayOptions(
    val window: ReaderWindowSettings = ReaderWindowSettings(),
    val pageSpacingDp: Int = 0,
    val marginCrop: Float = 0f,
    val controlsLocked: Boolean = false,
    val comparison: ReaderComparison = ReaderComparison.TRANSLATED,
    val splitFraction: Float = .5f,
    val spreadRtl: Boolean = false
) {
    init {
        require(pageSpacingDp in 0..64 && marginCrop.isFinite() && marginCrop in 0f..MAX_MARGIN_CROP &&
            splitFraction.isFinite() && splitFraction in 0f..1f)
    }
    fun effectiveWindow(mode: String): ReaderWindowSettings = if (mode == "spread" && window.orientation == ReaderWindowOrientation.SYSTEM)
        window.copy(orientation = ReaderWindowOrientation.LANDSCAPE) else window
    companion object { const val MAX_MARGIN_CROP = .15f }
}

/** Small scalar map used by IO preferences and saveable state. Invalid persisted values fall back safely. */
internal object ReaderDisplayOptionsCodec {
    fun encode(value: ReaderDisplayOptions): Map<String, Any> = mapOf(
        "orientation" to value.window.orientation.name, "rotation_lock" to value.window.rotationLocked,
        "brightness" to (value.window.brightnessOverride ?: -1f), "awake" to value.window.keepScreenOn,
        "spacing" to value.pageSpacingDp, "crop" to value.marginCrop, "controls_lock" to value.controlsLocked,
        "comparison" to value.comparison.name, "split" to value.splitFraction, "spread_rtl" to value.spreadRtl)
    fun decode(map: Map<String, Any?>): ReaderDisplayOptions {
        fun finite(key: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
            (map[key] as? Number)?.toFloat()?.takeIf { it.isFinite() && it in range } ?: default
        return ReaderDisplayOptions(ReaderWindowSettings(
            ReaderWindowOrientation.entries.firstOrNull { it.name == map["orientation"] } ?: ReaderWindowOrientation.SYSTEM,
            map["rotation_lock"] as? Boolean ?: false,
            (map["brightness"] as? Number)?.toFloat()?.takeIf { it.isFinite() && it in 0f..1f },
            map["awake"] as? Boolean ?: false),
            (map["spacing"] as? Int)?.takeIf { it in 0..64 } ?: 0,
            finite("crop", 0f, 0f..ReaderDisplayOptions.MAX_MARGIN_CROP), map["controls_lock"] as? Boolean ?: false,
            ReaderComparison.entries.firstOrNull { it.name == map["comparison"] } ?: ReaderComparison.TRANSLATED,
            finite("split", .5f, 0f..1f), map["spread_rtl"] as? Boolean ?: false)
    }
}
