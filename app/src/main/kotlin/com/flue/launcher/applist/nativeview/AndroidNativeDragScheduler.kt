package com.flue.launcher.applist.nativeview

import android.os.SystemClock
import android.view.View

internal class AndroidNativeDragScheduler(
    private val view: View,
) : NativeDragScheduler {
    private var delayedRunnable: Runnable? = null
    private var frameRunnable: Runnable? = null

    override val nowMillis: Long
        get() = SystemClock.uptimeMillis()

    override fun postDelayed(delayMillis: Long, action: () -> Unit) {
        cancelDelayed()
        Runnable {
            delayedRunnable = null
            action()
        }.also { runnable ->
            delayedRunnable = runnable
            view.postDelayed(runnable, delayMillis)
        }
    }

    override fun cancelDelayed() {
        delayedRunnable?.let(view::removeCallbacks)
        delayedRunnable = null
    }

    override fun postFrame(action: () -> Unit) {
        cancelFrame()
        Runnable {
            frameRunnable = null
            action()
        }.also { runnable ->
            frameRunnable = runnable
            view.postOnAnimation(runnable)
        }
    }

    override fun cancelFrame() {
        frameRunnable?.let(view::removeCallbacks)
        frameRunnable = null
    }
}
