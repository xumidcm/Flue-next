package com.flue.launcher.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

// 比渲染器的 4 秒保底更宽，防止就绪回调缺失时加载页一直停留。
private const val FACE_RENDER_GATE_MAX_HOLD_MS = 6_000L

internal data class WatchFaceRenderGate(
    val ready: Boolean,
    val onRenderReady: (Int) -> Unit,
)

@Composable
internal fun rememberWatchFaceRenderGate(
    faceKey: String,
    refreshToken: Int,
    needsRenderGate: Boolean,
): WatchFaceRenderGate {
    // 同一宿主的同一表盘刷新会保留旧画面，无需重新遮住它。切换表盘或
    // 宿主被销毁后重新组合必须重新等待，不能把历史就绪状态存成全局缓存。
    var rendered by remember(faceKey) { mutableStateOf(false) }
    val currentToken by rememberUpdatedState(refreshToken)
    var expired by remember(faceKey, refreshToken) { mutableStateOf(false) }
    LaunchedEffect(faceKey, refreshToken, needsRenderGate) {
        if (!needsRenderGate) return@LaunchedEffect
        delay(FACE_RENDER_GATE_MAX_HOLD_MS)
        expired = true
    }
    return WatchFaceRenderGate(
        ready = !needsRenderGate || expired || rendered,
        // 回调捕获当前表盘自己的状态；旧表盘或旧刷新任务不能改写新表盘的门控。
        onRenderReady = { reportedToken ->
            if (reportedToken == refreshToken && reportedToken == currentToken) rendered = true
        },
    )
}
