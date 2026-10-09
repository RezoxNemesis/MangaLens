package com.mangalens.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class LayoutDensity(val label: String, val padding: androidx.compose.ui.unit.Dp, val gap: androidx.compose.ui.unit.Dp) {
    COMPACT("Compact", 12.dp, 8.dp), BALANCED("Balanced", 16.dp, 12.dp), COMFORTABLE("Comfortable", 20.dp, 16.dp)
}
enum class Accent(val label: String, private val dark: Long, private val light: Long) {
    BLUE("Blue", 0xFF8DBBFA, 0xFF285EAD), CYAN("Cyan", 0xFF86CADA, 0xFF006A80),
    VIOLET("Violet", 0xFFBEAAEC, 0xFF664099), ROSE("Rose", 0xFFE7A8BC, 0xFF9C3658),
    AMBER("Amber", 0xFFE3C087, 0xFF795300), GREEN("Green", 0xFF93C7AE, 0xFF216445),
    MONOCHROME("Silver", 0xFFD8DEE7, 0xFF465569);
    fun color(darkTheme: Boolean) = Color(if (darkTheme) dark else light)
}
data class Appearance(val density: LayoutDensity = LayoutDensity.COMPACT, val accent: Accent = Accent.BLUE, val reducedMotion: Boolean = false)
val LocalAppearance = staticCompositionLocalOf { Appearance() }

@Composable
fun rememberAppearance(): Appearance {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("mangalens_appearance", Context.MODE_PRIVATE) }
    fun read() = Appearance(
        LayoutDensity.entries.firstOrNull { it.name == prefs.getString("density", "COMPACT") } ?: LayoutDensity.COMPACT,
        Accent.entries.firstOrNull { it.name == prefs.getString("accent", "BLUE") } ?: Accent.BLUE,
        prefs.getBoolean("reduced_motion", false)
    )
    var value by remember(prefs) { mutableStateOf(read()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> value = read() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}
