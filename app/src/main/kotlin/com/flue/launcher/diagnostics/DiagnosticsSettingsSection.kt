package com.flue.launcher.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.R
import com.flue.launcher.ui.style.FlueSwitch
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import kotlinx.coroutines.CancellationException

@Composable
fun DiagnosticsSettingsSection(vm: LauncherViewModel) {
    val context = LocalContext.current
    val config by DiagnosticsController.config.collectAsStateWithLifecycle()
    val recording by DiagnosticsController.recording.collectAsStateWithLifecycle()
    val style = LauncherTheme.style
    Column(
        modifier = Modifier.fillMaxWidth().background(style.cardColor, style.cardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.diagnostics_performance_title),
            color = style.titleColor,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.diagnostics_performance_description),
            color = style.secondaryTextColor,
            style = MaterialTheme.typography.bodySmall,
        )
        DiagnosticsSwitch(stringResource(R.string.diagnostics_fps), config.fps, DiagnosticsController::setFps)
        DiagnosticsSwitch(stringResource(R.string.diagnostics_memory), config.memory, DiagnosticsController::setMemory)
        DiagnosticsSwitch(stringResource(R.string.diagnostics_threads), config.threads, DiagnosticsController::setThreads)
        Text(
            stringResource(R.string.diagnostics_recording_description),
            color = style.secondaryTextColor,
            style = MaterialTheme.typography.bodySmall,
        )
        val status = when (recording.phase) {
            RecordingPhase.IDLE -> stringResource(R.string.diagnostics_recording_idle)
            RecordingPhase.STARTING -> stringResource(R.string.diagnostics_recording_preparing)
            RecordingPhase.RECORDING -> stringResource(R.string.diagnostics_recording_active, recording.elapsedSeconds, 300)
            RecordingPhase.FINISHING -> stringResource(R.string.diagnostics_recording_finishing)
            RecordingPhase.READY -> stringResource(if (recording.completedAutomatically) R.string.diagnostics_recording_auto_ready else R.string.diagnostics_recording_ready)
            RecordingPhase.ERROR -> stringResource(R.string.diagnostics_recording_failed)
        }
        Text(status, color = style.bodyColor, style = MaterialTheme.typography.bodyMedium)
        recording.errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        TextButton(
            onClick = { if (recording.phase == RecordingPhase.RECORDING) DiagnosticsController.stopRecording() else DiagnosticsController.startRecording(context, diagnosticsConfiguration(vm)) },
            enabled = recording.phase != RecordingPhase.STARTING && recording.phase != RecordingPhase.FINISHING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(if (recording.phase == RecordingPhase.RECORDING) R.string.diagnostics_stop_recording else R.string.diagnostics_start_recording),
                color = style.accentColor,
            )
        }
        if (recording.archive != null) {
            TextButton(onClick = { DiagnosticsController.shareArchive(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diagnostics_export), color = style.accentColor)
            }
        }
    }
}

@Composable
private fun DiagnosticsSwitch(title: String, checked: Boolean, toggle: (Boolean) -> Unit) {
    val style = LauncherTheme.style
    Row(
        modifier = Modifier.fillMaxWidth().clickable { toggle(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = style.bodyColor,
            style = MaterialTheme.typography.bodyMedium,
        )
        FlueSwitch(checked = checked, onCheckedChange = toggle)
    }
}

/** Placed last on the debug page so all displayed fields can be copied as one report. */
@Composable
fun DiagnosticsInformationSection(vm: LauncherViewModel) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    var refresh by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        failed = false
        try {
            info = DiagnosticsSnapshot.collect(context, diagnosticsConfiguration(vm))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().background(style.cardColor, style.cardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.diagnostics_information_title),
            color = style.titleColor,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.diagnostics_information_privacy),
            color = style.secondaryTextColor,
            style = MaterialTheme.typography.bodySmall,
        )
        if (failed) Text(stringResource(R.string.diagnostics_information_failed), color = MaterialTheme.colorScheme.error)
        if (expanded && info != null) SelectionContainer {
            Text(info.orEmpty(), color = style.bodyColor, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { expanded = !expanded }, enabled = info != null, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(if (expanded) R.string.diagnostics_hide_information else R.string.diagnostics_show_information),
                color = style.accentColor,
            )
        }
        TextButton(onClick = {
            val report = info ?: return@TextButton
            try {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Flue diagnostics", report))
                Toast.makeText(context, R.string.diagnostics_information_copied, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, R.string.diagnostics_copy_failed, Toast.LENGTH_SHORT).show()
            }
        }, enabled = info != null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.diagnostics_copy_information), color = style.accentColor)
        }
        TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.diagnostics_refresh_information), color = style.accentColor)
        }
    }
}
