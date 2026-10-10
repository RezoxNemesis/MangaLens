package com.mangalens.ui.reader

import android.app.Activity
import android.os.Looper
import android.view.WindowManager

/** Main-thread Android adapter. Reader composition/lifecycle decides when to update or release it. */
internal class ReaderWindowLease(activity: Activity) : AutoCloseable {
    private val policy = ReaderWindowLeasePolicy(ActivityReaderWindowHost(activity))

    fun update(settings: ReaderWindowSettings) {
        requireMainThread()
        policy.update(settings)
    }

    fun deactivate() {
        requireMainThread()
        policy.deactivate()
    }

    override fun close() = deactivate()

    private class ActivityReaderWindowHost(private val activity: Activity) : ReaderWindowHost {
        override var requestedOrientation: Int
            get() {
                requireMainThread()
                return activity.requestedOrientation
            }
            set(value) {
                requireMainThread()
                activity.requestedOrientation = value
            }

        override var screenBrightness: Float
            get() {
                requireMainThread()
                return activity.window.attributes.screenBrightness
            }
            set(value) {
                requireMainThread()
                val window = activity.window
                window.attributes = window.attributes.apply { screenBrightness = value }
            }

        override var keepScreenOn: Boolean
            get() {
                requireMainThread()
                return activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            }
            set(value) {
                requireMainThread()
                if (value) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
    }

    private companion object {
        fun requireMainThread() {
            check(Looper.myLooper() === Looper.getMainLooper()) { "Reader window controls require Main" }
        }
    }
}
