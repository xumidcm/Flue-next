package com.flue.launcher.ui.style

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.LocalGlobalUiScale
import com.flue.launcher.ui.theme.UiStyle

/**
 * 瞬时提示的统一入口。它把内容注册到同一 Compose 根节点中的 [FlueOverlayHost]，
 * 背景模糊、压暗、输入拦截和圆屏安全区域均由宿主负责。
 */
/** `dismissOnClickOutside` 为兼容旧调用保留；全屏提示中正文空白不会关闭。 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun FlueDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.92f,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    scrollableContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val controller = requireNotNull(LocalFlueOverlayController.current) {
        "FlueDialog must be rendered inside WatchLauncherTheme/FlueOverlayHost"
    }
    val uiScale = LocalGlobalUiScale.current
    val id = remember { Any() }
    val latestDismiss by androidx.compose.runtime.rememberUpdatedState(onDismissRequest)
    val latestContent by androidx.compose.runtime.rememberUpdatedState(content)
    val entry = remember(
        id,
        modifier,
        maxHeightFraction,
        dismissOnBackPress,
        scrollableContent,
        uiScale,
    ) {
        FlueOverlayEntry(
            id = id,
            onDismissRequest = { latestDismiss() },
            dismissOnBackPress = dismissOnBackPress,
            uiScale = uiScale,
            modifier = modifier,
            maxHeightFraction = maxHeightFraction,
            scrollableContent = scrollableContent,
            content = { latestContent() },
        )
    }
    androidx.compose.runtime.DisposableEffect(controller, entry) {
        controller.register(entry)
        onDispose { controller.unregister(id) }
    }
}

/** Full-screen transient surface that shares the prompt host's frozen blur layer. */
@Composable
fun FlueFullScreenOverlay(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnBackPress: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val controller = requireNotNull(LocalFlueOverlayController.current) {
        "FlueFullScreenOverlay must be rendered inside WatchLauncherTheme/FlueOverlayHost"
    }
    val uiScale = LocalGlobalUiScale.current
    val id = remember { Any() }
    val latestDismiss by androidx.compose.runtime.rememberUpdatedState(onDismissRequest)
    val latestContent by androidx.compose.runtime.rememberUpdatedState(content)
    val entry = remember(id, modifier, dismissOnBackPress, uiScale) {
        FlueOverlayEntry(
            id = id,
            onDismissRequest = { latestDismiss() },
            dismissOnBackPress = dismissOnBackPress,
            uiScale = uiScale,
            modifier = modifier,
            maxHeightFraction = 1f,
            scrollableContent = false,
            fullScreen = true,
            textReveal = true,
            content = { latestContent() },
        )
    }
    androidx.compose.runtime.DisposableEffect(controller, entry) {
        controller.register(entry)
        onDispose { controller.unregister(id) }
    }
}

/**
 * 标题 + 正文 + 按钮的常规弹窗。
 *
 * 这是那二十几处 `AlertDialog` 的落点：形状、贴边、按钮排布全部按风格走，调用点只给内容。
 *
 * [scrollableContent] 与插槽版同义（见那边的 T-06 段落）。这一版的 [content] 是
 * `ColumnScope` 扩展，所以传 `false` 之后调用点可以**自己**给列表标
 * `weight(1f, fill = false)`，不需要这里代劳。
 */
@Composable
fun FlueAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissText: String? = null,
    confirmEnabled: Boolean = true,
    dangerous: Boolean = false,
    dismissOnClickOutside: Boolean = true,
    scrollableContent: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val style = LauncherTheme.style
    FlueDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnClickOutside = dismissOnClickOutside,
        scrollableContent = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .then(
                    if (scrollableContent) Modifier.verticalScroll(rememberScrollState())
                    else Modifier,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                modifier = Modifier.fluePromptTextReveal(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = when (style.uiStyle) {
                    UiStyle.APPLE_WATCH -> 23.sp
                    UiStyle.MIUIX -> 21.sp
                    UiStyle.MATERIAL_3 -> 22.sp
                },
                fontWeight = if (style.uiStyle == UiStyle.MATERIAL_3) FontWeight.Medium else FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (!text.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text,
                    modifier = Modifier.fluePromptTextReveal(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
            if (content != null) {
                Spacer(Modifier.height(16.dp))
                content()
            }
        }
        Spacer(Modifier.height(20.dp))
        FlueDialogButtons(
            confirmText = confirmText,
            onConfirm = onConfirm,
            dismissText = dismissText,
            onDismiss = onDismissRequest,
            confirmEnabled = confirmEnabled,
            dangerous = dangerous,
        )
    }
}

/**
 * 弹窗按钮组。
 *
 * - **APPLE** → 54dp 大胶囊主按钮在上、次按钮在下（watchOS 的竖排大按钮，手表上好点）；
 * - **MIUIX** → 左右并排两个胶囊，取消是浅底、确定是强调色（HyperOS 的排法）；
 * - **MD3** → 右对齐的文字按钮，跟着 Material 走。
 */
@Composable
fun FlueDialogButtons(
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    dangerous: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        FlueDialogButton(
            text = confirmText,
            onClick = onConfirm,
            primary = true,
            enabled = confirmEnabled,
            dangerous = dangerous,
            height = 52.dp,
        )
        if (dismissText != null && onDismiss != null) {
            Spacer(Modifier.height(8.dp))
            FlueDialogButton(
                text = dismissText,
                onClick = onDismiss,
                primary = false,
                height = 48.dp,
            )
        }
    }
}

@Composable
private fun FlueDialogButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dangerous: Boolean = false,
    height: androidx.compose.ui.unit.Dp = 48.dp,
) {
    val style = LauncherTheme.style
    val motion = LocalFluePromptMotion.current
    val background = when {
        !enabled -> style.secondaryTextColor.copy(alpha = 0.18f)
        dangerous -> Color(0xFFFF453A).copy(alpha = 0.24f)
        primary -> style.accentColor
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val textColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        dangerous -> Color(0xFFFF6B66)
        primary -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) motion.pressedScale else 1f,
        animationSpec = spring(dampingRatio = motion.releaseDampingRatio, stiffness = 520f),
        label = "flue_dialog_button_press",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(fluePromptButtonShape())
            .background(background)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 与 `material3.AlertDialog` **同形状的插槽版**。
 *
 * 存在的理由很实际：那二十几处弹窗的标题 / 正文 / 按钮各有各的内容（有的正文里塞着输入框、
 * 候选列表、进度条），逐个拆成字符串参数既容易抄错，也会把调用点写得更啰嗦。所以给一个
 * 插槽版，迁移时**只改函数名**，外壳（形状 / 遮罩 / 贴边 / inset / 全局 UI 大小 / 限高）
 * 立刻按风格走。
 *
 * 按钮的**排布**仍然分档：Apple 竖排整宽（手表上好点）、MIUIX 左右并排、MD3 右对齐 ——
 * 按钮本体是调用方给的，我们只管怎么摆。
 *
 * ## 第三个动作要走 [extraAction]，不要塞进 [dismissButton]
 *
 * 老师报「弹窗底下只有两个胶囊框，却塞了 3 个选项，两个挤进了同一个框」。根因是调用点把
 * 两个 `TextButton` 包成一个 `Row` 塞进 `dismissButton` —— 而槽位一个就是一枚胶囊，
 * 塞进去的整个 Row 自然共用一枚。所以第三个动作有专门的 [extraAction]：Apple / MIUIX 档
 * 它**自己独占一行胶囊**（非主色、略矮），三个动作就是三枚胶囊；MD3 档按 Material 规范
 * 放在确认对的**左侧同一行**（那一档本来就是右对齐的文字按钮，另起一行会孤零零吊在左下角）。
 *
 * ## T-06：[scrollableContent] = false 时按钮怎么钉在卡底
 *
 * 光把外壳的 `verticalScroll` 去掉还不够。`Column` 测量非 weight 子项时给的是**当前剩余
 * 的全部高度**，而「标题 + 正文」是第一个子项 —— 它会先把整张卡吃光，后面的 Spacer 与
 * 按钮就被挤出去了（症状和原来一模一样，只是不再能滚）。
 *
 * 所以这里同时给那一块加 `weight(1f, fill = false)`：测量顺序变成「先按钮（固定高）、
 * 剩下的才给正文」，按钮从此下不去。`fill = false` 是为了**短内容不留白** —— 只有两个
 * 应用时卡片跟着内容收，不会撑成一整屏。
 *
 * 正文槽自己内部还要再传一次这个意图（把列表标成 `weight(1f, fill = false)`），因为
 * [text] 是 `@Composable () -> Unit`、**没有** `ColumnScope`，我们没法从外面替它标。
 */
@Composable
fun FlueAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    text: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    extraAction: (@Composable () -> Unit)? = null,
    dismissOnClickOutside: Boolean = true,
    scrollableContent: Boolean = true,
) {
    val style = LauncherTheme.style
    FlueDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnClickOutside = dismissOnClickOutside,
        scrollableContent = false,
    ) {
        // 标题与正文的排版交给调用方给的 composable，只统一字色与居中，
        // 免得每个调用点各写一遍 onSurface。
        //
        // 下面那个 `weight` 写在 CompositionLocalProvider **里面**是有效的：它不产生布局
        // 节点，所以这个 Column 的真实父级仍然是 FlueDialog 的卡片 Column，而 ColumnScope
        // 作为外层 lambda 的 receiver 在词法上一直可见。
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .then(
                        if (scrollableContent) Modifier.verticalScroll(rememberScrollState())
                        else Modifier,
                    ),
                horizontalAlignment = if (style.uiStyle == UiStyle.MATERIAL_3) {
                    Alignment.Start
                } else {
                    Alignment.CenterHorizontally
                },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fluePromptTextReveal(),
                    contentAlignment = if (style.uiStyle == UiStyle.MATERIAL_3) {
                        Alignment.CenterStart
                    } else {
                        Alignment.Center
                    },
                ) {
                    androidx.compose.material3.ProvideTextStyle(
                        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    ) { title() }
                }
                if (text != null) {
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        androidx.compose.material3.ProvideTextStyle(
                            MaterialTheme.typography.bodyMedium,
                        ) { text() }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            confirmButton?.let {
                FlueDialogActionSlot(primary = true, height = 50.dp, content = it)
            }
            if (dismissButton != null) {
                if (confirmButton != null) Spacer(Modifier.height(8.dp))
                FlueDialogActionSlot(primary = false, height = 46.dp, content = dismissButton)
            }
            if (extraAction != null) {
                if (confirmButton != null || dismissButton != null) Spacer(Modifier.height(8.dp))
                FlueDialogActionSlot(primary = false, height = 42.dp, content = extraAction)
            }
        }
    }
}

/**
 * 插槽版按钮的外壳：把调用方给的按钮**撑满整行**并垫一层胶囊底。
 *
 * ## 老师报的问题
 *
 * 「AW 风格的弹窗不该只有文字那里点击有反应，应该整行都有反应，而且要像『确定』那样是
 * 胶囊。」根因是插槽版把调用方的 `TextButton` 原样摆进去 —— M3 的 `TextButton` 按内容
 * 收宽，所以可点区域就只有文字那一小块。
 *
 * ## 为什么用 propagateMinConstraints 而不是给调用点逐个加 modifier
 *
 * `propagateMinConstraints = true` 会把这个 Box 的最小约束**下传给子项**，于是任何子项
 * （包括原封不动的 `TextButton`）都会被撑到整宽整高 —— 它自己的 `clickable` 跟着铺满，
 * 整行就都能点了。二十几个调用点一个都不用改。
 *
 * ## 为什么胶囊是**淡色**而不是实心强调色
 *
 * 底色由外壳画、文字颜色仍归调用方（`TextButton` 的文字色来自它自己的 `colors`，不吃
 * `LocalContentColor`）。实心强调色底 + 强调色文字 = 看不见。所以主按钮用强调色 18%
 * 的淡底 —— watchOS 的「确定」本来就是这种带色底的胶囊，观感是对的，而且对任何子项都成立。
 */
@Composable
private fun FlueDialogActionSlot(
    primary: Boolean,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val style = LauncherTheme.style
    val motion = LocalFluePromptMotion.current
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) motion.pressedScale else 1f,
        animationSpec = spring(dampingRatio = motion.releaseDampingRatio, stiffness = 520f),
        label = "flue_dialog_action_press",
    )
    val background = if (primary) {
        style.accentColor.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(fluePromptButtonShape())
            .background(background)
            .pointerInput(motion) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
        // 关键：把最小约束下传，子按钮被撑满 → 整块胶囊都是它的点击目标。
        propagateMinConstraints = true,
    ) {
        content()
    }
}

@Composable
fun <T> FlueSingleChoiceDialog(
    onDismissRequest: () -> Unit,
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    cancelText: String,
    message: String? = null,
) {
    val style = LauncherTheme.style
    FlueDialog(
        onDismissRequest = onDismissRequest,
        dismissOnClickOutside = false,
        scrollableContent = false,
    ) {
        Text(
            text = title,
            modifier = Modifier.fluePromptTextReveal(),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = when (style.uiStyle) {
                UiStyle.APPLE_WATCH -> 23.sp
                UiStyle.MIUIX -> 21.sp
                UiStyle.MATERIAL_3 -> 22.sp
            },
            fontWeight = if (style.uiStyle == UiStyle.MATERIAL_3) FontWeight.Medium else FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                modifier = Modifier.fluePromptTextReveal(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, label) ->
                val chosen = value == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(fluePromptButtonShape())
                        .background(
                            if (chosen) style.accentColor.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        )
                        .clickable {
                            onSelect(value)
                            onDismissRequest()
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        color = if (chosen) style.accentColor else MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (chosen) {
                        Text("●", color = style.accentColor, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FlueDialogButton(
            text = cancelText,
            onClick = onDismissRequest,
            primary = false,
        )
    }
}

@Composable
private fun fluePromptButtonShape(): Shape = when (LauncherTheme.style.uiStyle) {
    UiStyle.APPLE_WATCH -> RoundedCornerShape(999.dp)
    UiStyle.MIUIX -> LauncherTheme.style.compactShape
    UiStyle.MATERIAL_3 -> LauncherTheme.style.compactShape
}
