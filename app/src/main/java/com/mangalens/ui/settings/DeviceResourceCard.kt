package com.mangalens.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangalens.core.compute.ResourceGovernor
import com.mangalens.core.compute.ResourceGovernorRuntime
import com.mangalens.core.compute.ResourcePressure
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/** Shows the same policy that admits native/OCR work. Controlled test policies are scoped to this card. */
@Composable
internal fun DeviceResourceCard(governor: ResourceGovernor = ResourceGovernorRuntime.shared) {
    var snapshot by remember(governor) { mutableStateOf(governor.snapshot()) }
    LaunchedEffect(governor) {
        while (isActive) {
            snapshot = governor.snapshot()
            delay(500)
        }
    }
    com.mangalens.ui.components.Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Device resources", style = MaterialTheme.typography.titleMedium)
            Text(when (snapshot.pressure) {
                ResourcePressure.NORMAL -> "Background AI ready"
                ResourcePressure.ELEVATED -> "Background AI taking short breaks"
                ResourcePressure.CRITICAL -> "Background AI waiting"
            })
            snapshot.reason?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(snapshot.signals.thermal?.let { "Thermal status: " + it.name.lowercase(Locale.ROOT) }
                ?: "Thermal report unavailable", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot.signals.batteryPercent?.let { percent ->
                Text("Battery $percent%" + when (snapshot.signals.charging) {
                    true -> " · Charging"
                    false -> " · Unplugged"
                    null -> ""
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Background tasks keep their saved progress. Playback and controls retain priority.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
