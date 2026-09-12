package com.flue.launcher.aod

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flue.launcher.R
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.theme.LauncherTheme

/** Snapshot-backed AOD controls shared by the settings page and the controller. */
internal class AodSettingsState internal constructor(
    val config: AodConfig,
    val overlayGranted: Boolean,
    private val prefs: SharedPreferences,
    private val context: Context,
) {
    val controlsEnabled: Boolean
        get() = config.enabled || config.alwaysWhenCharging

    fun setEnabled(value: Boolean) = putBoolean("enabled", value)

    fun setAlwaysWhenCharging(value: Boolean) = putBoolean("always_when_charging", value)

    fun setTimeoutSeconds(value: Int) = putInt("timeout_seconds", value)

    fun setBrightnessPercent(value: Int) = putInt("brightness_percent", value)

    fun setReawaken(value: Boolean) = putBoolean("reawaken", value)

    fun setLockGestures(value: Boolean) = putBoolean("lock_gestures", value)

    fun setLockDelaySeconds(value: Int) = putInt("lock_delay_seconds", value)

    fun setShallowSleep(value: Boolean) = putBoolean("shallow_sleep", value)

    fun setPowerButtonExit(value: Boolean) = putBoolean("power_button_exit", value)

    fun requestOverlayPermission() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    private fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    private fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }
}

/** Observe AOD preferences and refresh the overlay permission when returning from system settings. */
@Composable
internal fun rememberAodSettingsState(): AodSettingsState {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val prefs = remember(context) { AodPreferences.preferences(context) }
    var config by remember(prefs) { mutableStateOf(AodPreferences.read(prefs)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    DisposableEffect(prefs, owner, context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            config = AodPreferences.read(prefs)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        owner.lifecycle.addObserver(observer)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            owner.lifecycle.removeObserver(observer)
        }
    }
    return AodSettingsState(
        config = config,
        overlayGranted = overlayGranted,
        prefs = prefs,
        context = context,
    )
}

/** Supplemental AOD explanation and the optional overlay permission action. */
@Composable
internal fun AodSupplementalNotice(
    state: AodSettingsState,
    modifier: Modifier = Modifier,
) {
    val style = LauncherTheme.style
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.aod_unlock_hint),
            color = style.secondaryTextColor,
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.config.reawaken && state.controlsEnabled) {
            Text(
                stringResource(
                    if (state.overlayGranted) {
                        R.string.aod_permission_granted
                    } else {
                        R.string.aod_permission_description
                    },
                ),
                color = style.secondaryTextColor,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!state.overlayGranted) {
                FlueOutlinedButton(
                    onClick = state::requestOverlayPermission,
                    modifier = Modifier.fillMaxWidth(),
                    contentColor = style.accentColor,
                ) {
                    Text(stringResource(R.string.aod_permission_action))
                }
            }
        }
    }
}

/** Compatibility summary for callers that need a standalone AOD section. */
@Composable
fun AodSettingsSection(showHeader: Boolean = true) {
    val state = rememberAodSettingsState()
    val style = LauncherTheme.style
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showHeader) {
            Text(
                stringResource(R.string.aod_title),
                color = style.titleColor,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.aod_page_description),
                color = style.secondaryTextColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AodSupplementalNotice(state, Modifier.padding(horizontal = 16.dp))
    }
}
