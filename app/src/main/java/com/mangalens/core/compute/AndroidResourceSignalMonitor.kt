package com.mangalens.core.compute

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Application-owned reports; no wake lock, model selection, task mutation or global native abort. */
internal object AndroidResourceSignalMonitor {
    private val installed = AtomicBoolean()
    private val lastMemory = AtomicReference<MemoryPressure?>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        val power = application.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val memory = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                runCatching {
                    if (intent?.action == Intent.ACTION_BATTERY_CHANGED) reportBattery(intent)
                    reportPower(power)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        runCatching {
            val initial = if (Build.VERSION.SDK_INT >= 33) application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else application.registerReceiver(receiver, filter)
            initial?.let(::reportBattery)
        }
        if (Build.VERSION.SDK_INT >= 29 && power != null) runCatching {
            power.addThermalStatusListener(application.mainExecutor) { status -> reportThermal(status) }
        }
        scope.launch {
            while (isActive) {
                runCatching {
                    memory?.let { manager ->
                        val info = ActivityManager.MemoryInfo()
                        manager.getMemoryInfo(info)
                        val pressure = when {
                            info.lowMemory -> MemoryPressure.CRITICAL
                            info.threshold > 0 && info.availMem <= info.threshold + info.threshold / 4 -> MemoryPressure.LOW
                            else -> MemoryPressure.NORMAL
                        }
                        reportMemory(pressure)
                    }
                    reportPower(power)
                    if (Build.VERSION.SDK_INT >= 29 && power != null) reportThermal(power.currentThermalStatus)
                }
                delay(2_000)
            }
        }
    }

    fun onTrimMemory(level: Int) {
        val pressure = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressure.CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressure.LOW
            else -> null
        }
        if (pressure != null) reportMemory(pressure)
    }

    private fun reportMemory(pressure: MemoryPressure) {
        val previous = lastMemory.getAndSet(pressure)
        ResourceGovernorRuntime.shared.update(ResourceSignals(memory = pressure))
        if (previous != pressure) when (pressure) {
            MemoryPressure.NORMAL -> Unit
            MemoryPressure.LOW -> com.mangalens.core.events.AppEvents.memoryPressure(com.mangalens.core.events.MemoryPressureLevel.MODERATE)
            MemoryPressure.CRITICAL -> com.mangalens.core.events.AppEvents.memoryPressure(com.mangalens.core.events.MemoryPressureLevel.CRITICAL)
        }
    }

    private fun reportPower(power: PowerManager?) {
        power?.let { ResourceGovernorRuntime.shared.update(ResourceSignals(powerSave = it.isPowerSaveMode)) }
    }

    private fun reportBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        ResourceGovernorRuntime.shared.update(ResourceSignals(
            batteryPercent = if (level >= 0 && scale > 0) ((level.toLong() * 100) / scale).toInt().coerceIn(0, 100) else null,
            charging = when {
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL -> true
                plugged >= 0 -> plugged > 0
                else -> null
            }
        ))
    }

    private fun reportThermal(status: Int) {
        val thermal = when (status) {
            0 -> ThermalPressure.NONE
            1 -> ThermalPressure.LIGHT
            2 -> ThermalPressure.MODERATE
            3 -> ThermalPressure.SEVERE
            4 -> ThermalPressure.CRITICAL
            5 -> ThermalPressure.EMERGENCY
            6 -> ThermalPressure.SHUTDOWN
            else -> null
        }
        thermal?.let { ResourceGovernorRuntime.shared.update(ResourceSignals(thermal = it)) }
    }
}
