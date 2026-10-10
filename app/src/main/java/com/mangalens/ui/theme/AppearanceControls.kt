package com.mangalens.ui.theme

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun AppearanceControls(themeMode: ThemeMode) {
    val context = LocalContext.current
    val prefs = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences("mangalens_appearance", Context.MODE_PRIVATE)
    }
    val appearance = LocalAppearance.current
    val haptics = LocalInterfaceHaptics.current
    var textScale by remember(appearance.typographyScale) { mutableFloatStateOf(appearance.typographyScale) }
    fun selection() = haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    com.mangalens.ui.components.Panel(Modifier.fillMaxWidth()) {
        Text("Layout density", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayoutDensity.entries.forEach { density ->
                FilterChip(appearance.density == density,
                    { prefs.edit().putString("density", density.name).apply(); selection() }, label = { Text(density.label) })
            }
        }
        Text("Accent", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Accent.entries.forEach { accent ->
                FilterChip(appearance.accent == accent,
                    { prefs.edit().putString("accent", accent.name).apply(); selection() }, label = { Text(accent.label) })
            }
        }
        Text("Interface text size · ${(textScale * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Text("Android text sizing still applies. Reader lettering has its own size control.", style = MaterialTheme.typography.bodySmall)
        Slider(textScale, { textScale = InterfaceTextScale.bounded(it) },
            onValueChangeFinished = {
                prefs.edit().putFloat("typography_scale", InterfaceTextScale.bounded(textScale)).apply()
                selection()
            }, valueRange = InterfaceTextScale.MIN..InterfaceTextScale.MAX, steps = 7,
            modifier = Modifier.semantics { contentDescription = "Interface text size" })
        Text("The quick brown fox · MangaLens", style = MaterialTheme.typography.bodyMedium)
        TextButton({ textScale = 1f; prefs.edit().putFloat("typography_scale", 1f).apply(); selection() }) { Text("Reset text size") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Touch feedback", Modifier.weight(1f))
            Switch(appearance.haptics, { prefs.edit().putBoolean("haptics", it).apply() },
                modifier = Modifier.semantics { contentDescription = "Touch feedback" })
        }
        Text("For appearance selections and guided panel controls. Uses Android’s touch-feedback setting.", style = MaterialTheme.typography.bodySmall)
        TextButton({ haptics.performHapticFeedback(HapticFeedbackType.LongPress) }, enabled = appearance.haptics) { Text("Try touch feedback") }
        if (themeMode == ThemeMode.CUSTOM_DARK) {
            Text("Custom Dark tone", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CustomDarkTone.entries.forEach { tone ->
                    FilterChip(appearance.customDarkTone == tone,
                        { prefs.edit().putString("custom_dark_tone", tone.name).apply(); selection() }, label = { Text(tone.label) })
                }
            }
            Text("Surface separation", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CustomDarkContrast.entries.forEach { contrast ->
                    FilterChip(appearance.customDarkContrast == contrast,
                        { prefs.edit().putString("custom_dark_contrast", contrast.name).apply(); selection() }, label = { Text(contrast.label) })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Reduce motion", Modifier.weight(1f))
            Switch(appearance.reducedMotion, { prefs.edit().putBoolean("reduced_motion", it).apply() })
        }
        Text("Build ${com.mangalens.BuildConfig.VERSION_NAME} • ${com.mangalens.BuildConfig.SOURCE_SHA} • ${com.mangalens.BuildConfig.BUILD_CHANNEL}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
