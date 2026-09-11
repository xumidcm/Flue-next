package com.flue.launcher.aod

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** One Activity owns the screen policy; renderer previews never change it. */
class AodController(
    private val activity: Activity,
    private val onCollapseOverlays: () -> Unit,
    private val onWillForeground: () -> Unit = {},
    private val screenInteractive: () -> Boolean = {
        (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    },
    private val overlayAllowed: () -> Boolean = { Settings.canDrawOverlays(activity) },
    private val requestForeground: (Intent) -> Unit = { activity.startActivity(it) },
) {
    companion object { const val EXTRA_AOD_FOREGROUND = "com.flue.launcher.AOD_FOREGROUND" }
    var ambientMode by mutableStateOf(false)
        private set
    var gesturesLocked by mutableStateOf(false)
        private set
    private val prefs = AodPreferences.preferences(activity)
    private var config = AodPreferences.read(prefs)
    private val handler = Handler(Looper.getMainLooper())
    private val wakeHandler = Handler(Looper.getMainLooper())
    private val generation = AodGeneration()
    private var started = false
    private var resumed = false
    private var pausedForScreenOff = false
    private var pointerHeld = false
    private var charging = false
    private var previousBrightness: Float? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var consumeGesture = false
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val touchSlop = ViewConfiguration.get(activity).scaledDoubleTapSlop.toFloat()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_BATTERY_CHANGED -> {
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val next = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
                        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    if (charging != next) { charging = next; applyConfig() }
                }
            }
        }
    }
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        config = AodPreferences.read(prefs)
        applyConfig()
    }

    fun start() {
        if (started) return
        started = true
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        val battery = ContextCompat.registerReceiver(activity, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (battery != null) receiver.onReceive(activity, battery)
        applyConfig()
    }

    fun onResume() {
        resumed = true
        pausedForScreenOff = false
        finishWakeRequest()
        applyConfig()
    }
    fun onPause() {
        // SCREEN_OFF may be delivered after onPause. The physical screen state
        // distinguishes that ordering from leaving Home for an interactive app.
        pausedForScreenOff = resumed && !screenInteractive()
        resumed = false
        pointerHeld = false
        cancelPending()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        releaseWakeLock()
    }

    fun stop() {
        if (!started) return
        started = false
        resumed = false
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        runCatching { activity.unregisterReceiver(receiver) }
        cancelPending()
        finishWakeRequest()
        restoreBrightness()
        ambientMode = false
        gesturesLocked = false
        releaseWakeLock()
        setShowWhenLocked(false)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun active(): Boolean = started && config.active(charging)
    private fun cancelPending() { generation.invalidate(); handler.removeCallbacksAndMessages(null) }
    private fun later(delayMs: Long, block: () -> Unit) {
        val token = generation.value
        handler.postDelayed({ if (generation.accepts(token, active())) block() }, delayMs)
    }

    private fun applyConfig() {
        cancelPending()
        if (!active()) {
            finishWakeRequest()
            ambientMode = false
            gesturesLocked = false
            restoreBrightness()
            releaseWakeLock()
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setShowWhenLocked(false)
            return
        }
        setShowWhenLocked(true)
        if (resumed) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!config.lockGestures) gesturesLocked = false
        if (ambientMode) {
            dimBrightness()
            if (resumed && !gesturesLocked) scheduleGestureLock()
        } else if (resumed) resetIdleTimer()
    }

    private fun resetIdleTimer() {
        cancelPending()
        if (active() && resumed && !pointerHeld) later(config.timeoutSeconds * 1_000L) { enterAmbient(false) }
    }

    fun enterAodNow(): Boolean {
        if (!active()) return false
        enterAmbient(true)
        return true
    }

    private fun enterAmbient(immediateLock: Boolean) {
        if (!active()) return
        cancelPending()
        onCollapseOverlays()
        ambientMode = true
        dimBrightness()
        if (config.lockGestures && immediateLock) gesturesLocked = true else scheduleGestureLock()
    }

    private fun scheduleGestureLock() {
        if (!config.lockGestures || gesturesLocked) return
        later(config.lockDelaySeconds * 1_000L) { if (ambientMode) gesturesLocked = true }
    }

    private fun brighten(unlock: Boolean) {
        cancelPending()
        ambientMode = false
        if (unlock) gesturesLocked = false
        restoreBrightness()
        resetIdleTimer()
    }

    /** Consumes every event in a waking/locked stream, including its final UP. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pointerHeld = true
            consumeGesture = ambientMode || gesturesLocked
            downX = event.x; downY = event.y; moved = false
            if (active()) {
                if (ambientMode && (!gesturesLocked || config.shallowSleep)) brighten(unlock = !gesturesLocked)
                else if (!ambientMode) resetIdleTimer()
            }
        }
        val consumed = consumeGesture
        if (event.actionMasked == MotionEvent.ACTION_MOVE &&
            (kotlin.math.abs(event.x - downX) > touchSlop || kotlin.math.abs(event.y - downY) > touchSlop)) moved = true
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (consumed && gesturesLocked && !moved && event.eventTime - event.downTime <= ViewConfiguration.getLongPressTimeout()) {
                val secondTap = lastTapAt != 0L && event.eventTime - lastTapAt <= ViewConfiguration.getDoubleTapTimeout() &&
                    kotlin.math.abs(event.x - lastTapX) <= touchSlop && kotlin.math.abs(event.y - lastTapY) <= touchSlop
                if (secondTap) { lastTapAt = 0L; brighten(unlock = true) }
                else { lastTapAt = event.eventTime; lastTapX = event.x; lastTapY = event.y }
            }
            consumeGesture = false
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) { consumeGesture = false; lastTapAt = 0L }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pointerHeld = false
            if (!ambientMode) resetIdleTimer()
        }
        return consumed
    }

    private fun dimBrightness() {
        val attrs = activity.window.attributes
        if (previousBrightness == null) previousBrightness = attrs.screenBrightness
        attrs.screenBrightness = config.brightnessPercent / 100f
        activity.window.attributes = attrs
    }

    private fun restoreBrightness() {
        val previous = previousBrightness ?: return
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = previous }
        previousBrightness = null
    }

    private fun setShowWhenLocked(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            activity.setShowWhenLocked(show)
            if (!show) activity.setTurnScreenOn(false)
        } else if (show) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    private fun onScreenOff() {
        val action = aodScreenOffAction(config, charging, gesturesLocked, resumed || pausedForScreenOff || overlayAllowed())
        if (!active() || action == AodScreenOffAction.NONE) return
        val previousAmbient = ambientMode
        val previousLocked = gesturesLocked
        fun recoverFailedReturn() {
            finishWakeRequest()
            if (!active()) return
            cancelPending()
            ambientMode = previousAmbient
            gesturesLocked = previousLocked
            if (ambientMode) dimBrightness() else restoreBrightness()
            if (resumed && !ambientMode) resetIdleTimer()
        }
        when (action) {
            AodScreenOffAction.EXIT_AMBIENT -> brighten(unlock = true)
            AodScreenOffAction.ENTER_AMBIENT -> enterAmbient(true)
            AodScreenOffAction.KEEP_AMBIENT -> enterAmbient(true)
            AodScreenOffAction.NONE -> return
        }
        runCatching {
            finishWakeRequest()
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "flue:aod_reawaken")
            lock.acquire(3_000L)
            wakeLock = lock
            if (Build.VERSION.SDK_INT >= 27) activity.setTurnScreenOn(true)
            onWillForeground()
            requestForeground(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                setPackage(activity.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(EXTRA_AOD_FOREGROUND, true)
            })
            // A denied background start can fail silently. The bounded request
            // has no retry loop and never leaves TURN_SCREEN_ON or a dim window
            // override behind when Home did not resume.
            wakeHandler.postDelayed({
                if (!resumed) recoverFailedReturn() else finishWakeRequest()
            }, 3_000L)
        }.onFailure { recoverFailedReturn() }
    }

    private fun finishWakeRequest() {
        wakeHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= 27) activity.setTurnScreenOn(false)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}
