package com.flue.launcher.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 冷启动时的表盘加载页。
 *
 * 存在的理由：偏好（选中哪块表盘）与磁盘扫描都是异步的，而 ViewModel 里
 * `_selectedWatchFace` 的初始值是内置表盘。在两者就绪之前直接渲染，用户会先
 * 看到一瞬间默认表盘再跳到自己那块 —— 老师报的就是这个。所以就绪之前这里顶住。
 *
 * 这段时间同时被用来预热小组件（见 LauncherActivity 的预热挂载），所以加载页
 * 停留得久一点反而有收益。
 */
@Composable
fun WatchFaceLoadingScreen(
    /** 超时后的逃生出口：落盘默认表盘并重启进程。挂起直到落盘完成。 */
    onFallbackToDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEscape by remember { mutableStateOf(false) }
    var escapeRequested by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(ESCAPE_HATCH_DELAY_MS)
        showEscape = true
    }

    // 加载没完成不允许退出：返回键吃掉。上滑应用列表 / 侧滑负一屏那些手势挂在
    // 更外层的容器上，光靠下面的 pointerInput 拦不住，所以 LauncherScreen 那边
    // 还有一道门控把它们整个停掉；这里是第二道防线。
    BackHandler(enabled = true) {}

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    // 全量消费：按下就吃，抬手才放，中途一个事件都不外泄。
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            InfiniteProgressIndicator(
                color = Color.White,
                size = 28.dp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.watch_face_loading),
                color = Color.White.copy(alpha = 0.86f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            // 逃生按钮淡入而不是硬弹：5 秒后突然多一个按钮太扎眼。
            AnimatedVisibility(
                visible = showEscape,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(18.dp))
                    TextButton(
                        text = stringResource(R.string.watch_face_loading_fallback),
                        // 落盘 + 杀进程是不可逆的，连点两次会起两次重启流程。
                        enabled = !escapeRequested,
                        onClick = {
                            escapeRequested = true
                            onFallbackToDefault()
                        },
                        modifier = Modifier.size(width = 200.dp, height = 40.dp),
                    )
                }
            }
        }
    }
}

/** 老师定的 5 秒：超过这个时长还没就绪就给逃生出口。 */
private const val ESCAPE_HATCH_DELAY_MS = 5_000L
