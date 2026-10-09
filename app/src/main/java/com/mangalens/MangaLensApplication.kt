package com.mangalens

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import coil.Coil
import kotlinx.coroutines.launch

class MangaLensApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.mangalens.orez.agent.OrezTaskRecovery.enqueue(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            com.mangalens.ui.video.SubtitleGenerationJobs.recoverPending(this@MangaLensApplication)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
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
