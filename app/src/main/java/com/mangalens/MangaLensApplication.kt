package com.mangalens

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import coil.Coil

class MangaLensApplication : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { Coil.imageLoader(this).memoryCache?.clear() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
