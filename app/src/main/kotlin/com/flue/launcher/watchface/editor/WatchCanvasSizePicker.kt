package com.flue.launcher.watchface.editor

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.flue.launcher.R

/**
 * 当前设备屏幕对应的画布尺寸（物理像素）。
 *
 * 用 DisplayMetrics 而不是 Compose 的 dp：表盘资源是位图，按物理像素做出来
 * 的表盘在本机才是 1:1 不重采样。
 */
@Composable
fun rememberScreenCanvasSize(): WatchCanvasSize {
    val context = LocalContext.current
    return remember(context) { screenCanvasSize(context) }
}

internal fun screenCanvasSize(context: Context): WatchCanvasSize {
    val metrics = context.resources.displayMetrics
    return WatchCanvasSize.clamped(metrics.widthPixels, metrics.heightPixels)
}

/**
 * 画布尺寸选择对话框。
 *
 * 三档：跟随屏幕 / 市场预设 / 自定义。新建表盘时先弹这个，选完才建草稿；
 * 已有表盘从工具栏进来改尺寸。
 */
@Composable
fun CanvasSizePickerDialog(
    current: WatchCanvasSize,
    screenSize: WatchCanvasSize,
    onConfirm: (WatchCanvasSize) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
) {
    // 初始选中项：与屏幕尺寸一致 → 跟随屏幕；命中预设 → 该预设；否则自定义。
    val initialMode = remember(current, screenSize) {
        when {
            current == screenSize -> CanvasPickerMode.FOLLOW_SCREEN
            WatchCanvasPreset.MARKET_PRESETS.any { it.size == current } -> CanvasPickerMode.PRESET
            else -> CanvasPickerMode.CUSTOM
        }
    }
    var mode by remember { mutableStateOf(initialMode) }
    var selectedPreset by remember {
        mutableStateOf(
            WatchCanvasPreset.MARKET_PRESETS.firstOrNull { it.size == current }
                ?: WatchCanvasPreset.MARKET_PRESETS.first(),
        )
    }
    var customWidth by remember { mutableStateOf(current.width.toString()) }
    var customHeight by remember { mutableStateOf(current.height.toString()) }

    val resolved: WatchCanvasSize? = when (mode) {
        CanvasPickerMode.FOLLOW_SCREEN -> screenSize
        CanvasPickerMode.PRESET -> selectedPreset.size
        CanvasPickerMode.CUSTOM -> {
            val w = customWidth.trim().toIntOrNull()
            val h = customHeight.trim().toIntOrNull()
            if (w != null && h != null &&
                w in WatchCanvasSize.MIN_EDGE..WatchCanvasSize.MAX_EDGE &&
                h in WatchCanvasSize.MIN_EDGE..WatchCanvasSize.MAX_EDGE
            ) {
                WatchCanvasSize(w, h)
            } else {
                null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watch_editor_canvas_size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CanvasModeRow(
                    label = stringResource(R.string.watch_editor_canvas_follow_screen),
                    detail = screenSize.label,
                    selected = mode == CanvasPickerMode.FOLLOW_SCREEN,
                    onSelect = { mode = CanvasPickerMode.FOLLOW_SCREEN },
                )
                CanvasModeRow(
                    label = stringResource(R.string.watch_editor_canvas_common_sizes),
                    detail = if (mode == CanvasPickerMode.PRESET) selectedPreset.size.label else null,
                    selected = mode == CanvasPickerMode.PRESET,
                    onSelect = { mode = CanvasPickerMode.PRESET },
                )
                if (mode == CanvasPickerMode.PRESET) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(
                            WatchCanvasPreset.MARKET_PRESETS,
                            key = { "${it.size.width}x${it.size.height}" },
                        ) { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedPreset = preset }
                                    .padding(start = 28.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                RadioButton(
                                    selected = selectedPreset == preset,
                                    onClick = { selectedPreset = preset },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.size.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        stringResource(watchCanvasPresetNoteLabel(preset.note)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                CanvasModeRow(
                    label = stringResource(R.string.watch_editor_canvas_custom),
                    detail = null,
                    selected = mode == CanvasPickerMode.CUSTOM,
                    onSelect = { mode = CanvasPickerMode.CUSTOM },
                )
                if (mode == CanvasPickerMode.CUSTOM) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = customWidth,
                            onValueChange = { customWidth = it.filter(Char::isDigit).take(4) },
                            label = { Text(stringResource(R.string.watch_editor_width)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = customHeight,
                            onValueChange = { customHeight = it.filter(Char::isDigit).take(4) },
                            label = { Text(stringResource(R.string.watch_editor_height)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        stringResource(
                            R.string.watch_editor_canvas_range_fmt,
                            WatchCanvasSize.MIN_EDGE,
                            WatchCanvasSize.MAX_EDGE,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = resolved != null,
                onClick = { resolved?.let(onConfirm) },
            ) {
                Text(confirmLabel ?: stringResource(R.string.watch_editor_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.watch_editor_cancel)) }
        },
    )
}

private enum class CanvasPickerMode { FOLLOW_SCREEN, PRESET, CUSTOM }

@StringRes
private fun watchCanvasPresetNoteLabel(note: WatchCanvasPresetNote): Int = when (note) {
    WatchCanvasPresetNote.COMMON_ROUND_AMOLED -> R.string.watch_editor_canvas_note_common_round_amoled
    WatchCanvasPresetNote.LARGE_ROUND -> R.string.watch_editor_canvas_note_large_round
    WatchCanvasPresetNote.MEDIUM_ROUND -> R.string.watch_editor_canvas_note_medium_round
    WatchCanvasPresetNote.WEAR_OS_ROUND -> R.string.watch_editor_canvas_note_wear_os_round
    WatchCanvasPresetNote.SMALL_SQUARE -> R.string.watch_editor_canvas_note_small_square
    WatchCanvasPresetNote.PORTRAIT_SQUARE -> R.string.watch_editor_canvas_note_portrait_square
    WatchCanvasPresetNote.RECTANGULAR -> R.string.watch_editor_canvas_note_rectangular
    WatchCanvasPresetNote.WATCHMAKER_NATIVE -> R.string.watch_editor_canvas_note_watchmaker_native
}

@Composable
private fun CanvasModeRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
