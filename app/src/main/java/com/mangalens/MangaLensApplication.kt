package com.mangalens

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import coil.Coil
import kotlinx.coroutines.launch
import com.mangalens.diagnostics.StartupScalarTrace
import com.mangalens.diagnostics.StartupStage

class MangaLensApplication : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        StartupScalarTrace.markOnce(StartupStage.APPLICATION_ATTACHED)
    }

    override fun onCreate() {
        StartupScalarTrace.measure(StartupStage.APPLICATION_CREATE) {
            super.onCreate()
            StartupScalarTrace.measure(StartupStage.RESOURCE_MONITOR_INSTALL) {
                com.mangalens.core.compute.AndroidResourceSignalMonitor.install(this)
            }
            StartupScalarTrace.measure(StartupStage.RECOVERY_ENQUEUE) {
                com.mangalens.orez.agent.OrezTaskRecovery.enqueue(this)
            }
            com.mangalens.widget.MangaLensWidgetUpdates.applicationStarted(this)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                com.mangalens.ui.video.SubtitleGenerationJobs.recoverPending(this@MangaLensApplication)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.mangalens.core.compute.AndroidResourceSignalMonitor.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { Coil.imageLoader(this).memoryCache?.clear() }
        }
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            com.mangalens.oreznative.OrezNativeEngine.trimMemory(
                com.mangalens.core.compute.NativeComputeMemoryRelease.shared::schedule
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
