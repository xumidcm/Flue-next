package com.flue.launcher.aod

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flue.launcher.R
import kotlin.math.roundToInt

/** Standalone section so settings and the screen controller use one preference source. */
@Composable
fun AodSettingsSection() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val prefs = remember(context) { AodPreferences.preferences(context) }
    var config by remember(prefs) { mutableStateOf(AodPreferences.read(prefs)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    DisposableEffect(prefs, owner) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> config = AodPreferences.read(prefs) }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) overlayGranted = Settings.canDrawOverlays(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        owner.lifecycle.addObserver(observer)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); owner.lifecycle.removeObserver(observer) }
    }
    val controlsEnabled = config.enabled || config.alwaysWhenCharging
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.aod_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.aod_description), style = MaterialTheme.typography.bodySmall)
            AodToggle(R.string.aod_enabled, config.enabled) { prefs.edit().putBoolean("enabled", it).apply() }
            AodToggle(R.string.aod_charging, config.alwaysWhenCharging) { prefs.edit().putBoolean("always_when_charging", it).apply() }
            AodSlider(R.string.aod_timeout, config.timeoutSeconds, 5f..300f, 58, controlsEnabled, "%d s") { prefs.edit().putInt("timeout_seconds", it).apply() }
            AodSlider(R.string.aod_brightness, config.brightnessPercent, 1f..100f, 98, controlsEnabled, "%d%%") { prefs.edit().putInt("brightness_percent", it).apply() }
            AodToggle(R.string.aod_reawaken, config.reawaken, controlsEnabled) { prefs.edit().putBoolean("reawaken", it).apply() }
            AodToggle(R.string.aod_lock, config.lockGestures, controlsEnabled) { prefs.edit().putBoolean("lock_gestures", it).apply() }
            AodSlider(R.string.aod_lock_delay, config.lockDelaySeconds, 0f..30f, 29, controlsEnabled && config.lockGestures, "%d s") { prefs.edit().putInt("lock_delay_seconds", it).apply() }
            AodToggle(R.string.aod_shallow, config.shallowSleep, controlsEnabled && config.lockGestures) { prefs.edit().putBoolean("shallow_sleep", it).apply() }
            AodToggle(R.string.aod_power_exit, config.powerButtonExit, controlsEnabled && config.reawaken) { prefs.edit().putBoolean("power_button_exit", it).apply() }
            Text(stringResource(R.string.aod_unlock_hint), style = MaterialTheme.typography.bodySmall)
            if (config.reawaken && controlsEnabled) {
                Text(stringResource(if (overlayGranted) R.string.aod_permission_granted else R.string.aod_permission_description), style = MaterialTheme.typography.bodySmall)
                if (!overlayGranted) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                }) { Text(stringResource(R.string.aod_permission_action)) }
            }
        }
    }
}

@Composable
private fun AodToggle(label: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun AodSlider(label: Int, value: Int, range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean, unit: String, onChange: (Int) -> Unit) {
    var dragValue by remember(value) { mutableStateOf(value.toFloat()) }
    Text("${stringResource(label)} · ${unit.format(dragValue.roundToInt())}", style = MaterialTheme.typography.bodyMedium)
    Slider(value = dragValue, onValueChange = { dragValue = it }, valueRange = range, steps = steps,
        enabled = enabled, onValueChangeFinished = { onChange(dragValue.roundToInt()) })
}
