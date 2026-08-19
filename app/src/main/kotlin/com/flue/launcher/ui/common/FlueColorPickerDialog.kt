package com.flue.launcher.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flue.launcher.R
import java.util.Locale

/**
 * HSV 三滑条取色盘（手表小屏：预览色块 + 色相 / 饱和度 / 明度）。
 *
 * ## 为什么抽出来
 *
 * 这套取色盘原来是 `WatchEditorActivity` 里的一个 private composable。应用列表背景的
 * 「自定义颜色」也要用它 —— 项目里已经有两份手搓取色 UI（编辑器的这个 + 内置表盘配置的
 * `ClockColorPickerRow`），再加第三份就没人会记得同时改三处。所以升成公共件，编辑器改成
 * 调用它。
 *
 * 不接 miuix 的 `basic.ColorPicker`：那是手机尺度的色轮 + 四条滑条，圆屏 230dp 宽放不下，
 * 而 HSV 三滑条在表上是能用的（老师一直在用）。
 *
 * @param title 弹窗标题。调用点各自给，因为「取色盘」在编辑器里指图层颜色、在设置里指背景色。
 */
@Composable
fun FlueColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
    title: String = stringResource(R.string.watch_editor_color_picker),
) {
    // initial 进 remember 的 key：外面换了初始色（比如切了图层）要跟着重置，
    // 否则弹窗会一直显示上一次拖到的位置。
    val initialHsv = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember(initial) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(initial) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(initial) { mutableFloatStateOf(initialHsv[2]) }
    val currentColor = Color(
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(currentColor),
                )
                Text(
                    flueColorToHex(currentColor),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.watch_editor_hue),
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text(
                    stringResource(R.string.watch_editor_saturation),
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..1f,
                )
                Text(
                    stringResource(R.string.watch_editor_brightness),
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentColor) }) {
                Text(stringResource(R.string.watch_editor_use))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.watch_editor_cancel)) }
        },
    )
}

/** `#RRGGBB` 回显。取色盘只产出不透明色，所以不带 alpha 段。 */
fun flueColorToHex(color: Color): String {
    val argb = color.toArgb()
    return String.format(Locale.US, "#%02X%02X%02X", (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
}
