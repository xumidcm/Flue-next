package com.flue.launcher.watchface.editor

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flue.launcher.R
import com.flue.launcher.ui.style.FlueAlertDialog
import java.io.File

/** Preview settings are deliberately separate from the Watch document. */
@Composable
fun EditorPreviewViewportDialog(
    currentSize: WatchEditorViewport,
    deviceSize: WatchEditorViewport,
    roundOverride: Boolean?,
    onApply: (WatchEditorViewport, Boolean?) -> Unit,
    onDismiss: () -> Unit,
) {
    var width by remember { mutableStateOf(currentSize.width.toString()) }
    var height by remember { mutableStateOf(currentSize.height.toString()) }
    var round by remember { mutableStateOf(roundOverride) }
    val valid = width.toIntOrNull()?.let { it > 0 } == true &&
        height.toIntOrNull()?.let { it > 0 } == true
    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watch_editor_preview_viewport)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.watch_editor_preview_only), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { width = deviceSize.width.toString(); height = deviceSize.height.toString() }) {
                    Text(stringResource(R.string.watch_editor_preview_current_device) + " · " + deviceSize.label)
                }
                listOf(480 to 480, 320 to 386, 368 to 448, 410 to 502).forEach { (w, h) ->
                    OutlinedButton(onClick = { width = w.toString(); height = h.toString() }) { Text("$w × $h") }
                }
                Text(stringResource(R.string.watch_editor_preview_custom))
                OutlinedTextField(width, { width = it }, label = { Text(stringResource(R.string.watch_editor_preview_width)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(height, { height = it }, label = { Text(stringResource(R.string.watch_editor_preview_height)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                if (!valid) Text(stringResource(R.string.watch_editor_preview_invalid_size), color = MaterialTheme.colorScheme.error)
                listOf(null to R.string.watch_editor_preview_follow_shape, true to R.string.watch_editor_preview_round, false to R.string.watch_editor_preview_rectangle).forEach { (value, label) ->
                    FilterChip(selected = round == value, onClick = { round = value }, label = { Text(stringResource(label)) })
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onApply(WatchEditorViewport(width.toInt(), height.toInt()), round) }) { Text(stringResource(R.string.watch_editor_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.watch_editor_cancel)) } },
    )
}

@Composable
fun EditorAmbientProperties(
    layer: WatchEditorLayer,
    draftResetKey: Int,
    enabled: Boolean,
    onChange: (Map<String, String?>) -> Unit,
) {
    var expanded by remember(layer.key) { mutableStateOf(false) }
    var values by remember(layer.key, draftResetKey) { mutableStateOf(layer.ambientAttributes) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.watch_editor_ambient_properties)) }
        if (expanded) {
            Text(stringResource(R.string.watch_editor_ambient_inherit), style = MaterialTheme.typography.bodySmall)
            listOf(
                "color" to R.string.watch_editor_attribute_color,
                "opacity" to R.string.watch_editor_attribute_opacity,
                "x" to R.string.watch_editor_attribute_x,
                "y" to R.string.watch_editor_attribute_y,
                "width" to R.string.watch_editor_attribute_width,
                "height" to R.string.watch_editor_attribute_height,
                "rotation" to R.string.watch_editor_attribute_rotation,
                "fontSize" to R.string.watch_editor_attribute_text_size,
            ).forEach { (name, label) ->
                OutlinedTextField(
                    value = values[name].orEmpty(),
                    onValueChange = { raw -> values = values + (name to raw); onChange(mapOf("Ambient.$name" to raw.ifBlank { null })) },
                    label = { Text(stringResource(label)) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** One player per visible resource manager; release on completion, replacement and backgrounding. */
@Composable
fun rememberEditorAudioPreview(onError: () -> Unit): (File) -> Unit {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val latestError by rememberUpdatedState(onError)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { runCatching { player?.release() }; player = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); runCatching { player?.release() }; player = null }
    }
    return { file ->
        runCatching { player?.release() }
        player = null
        val next = MediaPlayer()
        player = next
        runCatching {
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener { if (player === it) it.start() }
            next.setOnCompletionListener { it.release(); if (player === it) player = null }
            next.setOnErrorListener { active, _, _ ->
                active.release(); if (player === active) player = null; latestError(); true
            }
            next.prepareAsync()
        }.onFailure { runCatching { next.release() }; if (player === next) player = null; latestError() }
    }
}
