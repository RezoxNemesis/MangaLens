package com.mangalens.ui.home

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class HomeLayoutPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val store = HomeLayoutStore(object : HomeLayoutStorage {
        override fun read(): String? = preferences.getString(KEY, null)
        override fun write(value: String): Boolean = synchronized(WRITE_LOCK) {
            val before = preferences.getString(KEY, null)
            val saved = preferences.edit().putString(KEY, value).commit()
            if (!saved) {
                // SharedPreferences updates its memory map before a disk commit. Put
                // the previous value back so the UI cannot present a failed save as persisted.
                preferences.edit().apply { if (before == null) remove(KEY) else putString(KEY, before) }.apply()
            }
            saved
        }
    })
    fun read(): HomeLayout = store.load()
    suspend fun save(value: HomeLayout): Boolean = withContext(Dispatchers.IO) { store.save(value) }
    fun listen(onChanged: (HomeLayout) -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY || key == null) onChanged(read())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val PREFERENCES = "mangalens_home"
        const val KEY = "layout_v1"
        private val WRITE_LOCK = Any()
    }
}

internal data class HomeLayoutBinding(val layout: HomeLayout, val preferences: HomeLayoutPreferences)

@Composable
internal fun rememberHomeLayout(): HomeLayoutBinding {
    val context = LocalContext.current
    val preferences = remember(context) { HomeLayoutPreferences(context) }
    var layout by remember(preferences) { mutableStateOf(preferences.read()) }
    DisposableEffect(preferences) {
        val stopListening = preferences.listen { layout = it }
        layout = preferences.read()
        onDispose { stopListening() }
    }
    return HomeLayoutBinding(layout, preferences)
}
