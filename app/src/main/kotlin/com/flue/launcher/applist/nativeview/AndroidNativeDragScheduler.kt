package com.flue.launcher.applist.nativeview

import android.os.SystemClock
import android.view.View

internal class AndroidNativeDragScheduler(
    private val view: View,
) : NativeDragScheduler {
    private var longPressRunnable: Runnable? = null
    private var folderHoverRunnable: Runnable? = null
    private var frameRunnable: Runnable? = null

    override val nowMillis: Long
        get() = SystemClock.uptimeMillis()

    override fun postLongPress(delayMillis: Long, action: () -> Unit) {
        cancelLongPress()
        Runnable {
            longPressRunnable = null
            action()
        }.also { runnable ->
            longPressRunnable = runnable
            view.postDelayed(runnable, delayMillis)
        }
    }

    override fun cancelLongPress() {
        longPressRunnable?.let(view::removeCallbacks)
        longPressRunnable = null
    }

    override fun postFolderHover(delayMillis: Long, action: () -> Unit) {
        cancelFolderHover()
        Runnable {
            folderHoverRunnable = null
            action()
        }.also { runnable ->
            folderHoverRunnable = runnable
            view.postDelayed(runnable, delayMillis)
        }
    }

    override fun cancelFolderHover() {
        folderHoverRunnable?.let(view::removeCallbacks)
        folderHoverRunnable = null
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
