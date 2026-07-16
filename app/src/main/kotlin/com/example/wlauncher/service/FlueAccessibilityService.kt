package com.flue.launcher.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.flue.launcher.launchsession.ForegroundPackageEvidence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

class FlueAccessibilityService : AccessibilityService() {

    private var pendingExternalForegroundEvidence: ForegroundPackageEvidence? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        clearForegroundEvidence()
        activeService = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val observedPackage = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (observedPackage == applicationContext.packageName) {
            pendingExternalForegroundEvidence?.let { sealedEvidence ->
                _lastExternalForegroundEvidence.value = sealedEvidence
            }
            pendingExternalForegroundEvidence = null
        } else {
            _lastExternalForegroundEvidence.value = null
            pendingExternalForegroundEvidence = ForegroundPackageEvidence(
                packageName = observedPackage,
                observedAtElapsedMs = SystemClock.elapsedRealtime(),
                sequence = evidenceSequence.incrementAndGet(),
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        clearForegroundEvidence()
        if (activeService === this) {
            activeService = null
            _connected.value = false
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearForegroundEvidence()
        if (activeService === this) {
            activeService = null
            _connected.value = false
        }
        super.onDestroy()
    }

    private fun clearForegroundEvidence() {
        pendingExternalForegroundEvidence = null
        _lastExternalForegroundEvidence.value = null
    }

    companion object {
        private var activeService: FlueAccessibilityService? = null
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
        private val evidenceSequence = AtomicLong(0L)
        private val _lastExternalForegroundEvidence =
            MutableStateFlow<ForegroundPackageEvidence?>(null)
        val lastExternalForegroundEvidence: StateFlow<ForegroundPackageEvidence?> =
            _lastExternalForegroundEvidence.asStateFlow()

        fun isConnected(): Boolean = activeService != null || _connected.value

        fun latestExternalForegroundEvidence(): ForegroundPackageEvidence? =
            _lastExternalForegroundEvidence.value

        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, FlueAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return enabledServices
                ?.split(':')
                ?.mapNotNull { ComponentName.unflattenFromString(it) }
                ?.any { it.packageName == component.packageName && it.className == component.className }
                ?: false
        }

        fun lockScreen(): Boolean {
            val service = activeService ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }

        fun openPowerDialog(): Boolean {
            val service = activeService ?: return false
            return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        }
    }
}
