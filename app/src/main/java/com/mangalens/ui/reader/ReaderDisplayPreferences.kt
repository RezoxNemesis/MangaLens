package com.mangalens.ui.reader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** New scalar display choices share the Reader preferences, without opening/reading its file on Main. */
internal class ReaderDisplayPreferences(private val context: Context) {
    suspend fun load(): ReaderDisplayOptions = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences("mangalens_reader", Context.MODE_PRIVATE)
        val values = preferences.all
        ReaderDisplayOptionsCodec.decode(KEYS.associateWith { values[PREFIX + it] })
    }
    suspend fun save(options: ReaderDisplayOptions) {
        val captured = writes.incrementAndGet()
        withContext(NonCancellable + Dispatchers.IO) {
            writeGate.withLock {
                if (captured != writes.get()) return@withLock
                val editor = context.getSharedPreferences("mangalens_reader", Context.MODE_PRIVATE).edit()
                ReaderDisplayOptionsCodec.encode(options).forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(PREFIX + key, value)
                is Boolean -> editor.putBoolean(PREFIX + key, value)
                is Float -> editor.putFloat(PREFIX + key, value)
                is Int -> editor.putInt(PREFIX + key, value)
            }
        }
                editor.apply()
            }
        }
    }
    companion object {
        private val writes = AtomicLong()
        private val writeGate = Mutex()
        private const val PREFIX = "display_v1_"
        private val KEYS = listOf("orientation", "rotation_lock", "brightness", "awake", "spacing", "crop", "controls_lock", "comparison", "split", "spread_rtl")
    }
}
