package com.flue.launcher.ui.style

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideDialogUiScale
import com.flue.launcher.ui.theme.UiStyle

/**
 * 弹窗的唯一宿主与三档外观。
 *
 * ## 老师报的问题
 *
 * 二十几处 `AlertDialog` / `Dialog` **各写各的**，只有「遮罩圆角」那一处是 Apple 玻璃观感。
 * 于是切风格时弹窗永远是 Material 的样子。
 *
 * ## 为什么窗口那层只有一份
 *
 * 三档只在**表面装饰**上不同（形状 / 背景 / 描边 / 投影 / 贴边方向），而窗口那层的坑是
 * 共有的，而且踩过：
 *
 * - `usePlatformDefaultWidth = false` **必须**配 `decorFitsSystemWindows = false`，否则
 *   Dialog 自己的 Window 去避让状态栏、顶边下移，让出的一条露出遮罩 —— 就是 D1 那条
 *   「顶部空一条半透明黑」；
 * - Dialog 是独立 Window，`LocalDensity` 不继承，不套 [ProvideDialogUiScale] 的话设置里的
 *   「全局 UI 大小」对弹窗完全无效；
 * - 圆屏上不限高，按钮会被挤出屏幕。
 *
 * 所以窗口 + 遮罩 + inset + 限高 + 滚动只写一份（[FlueDialog]），三档各自只提供一个
 * 装饰 modifier（[flueDialogSurface]）。以后再加风格，加的是装饰，不是再抄一遍窗口。
 *
 * ## T-06：`scrollableContent` 为什么是 opt-in、为什么必须默认 `true`
 *
 * 老师报「应用选择弹窗显示不全下面的按钮」。根源**不在**各个选择器，在这个外壳：卡片那层
 * 无条件套了 `verticalScroll`，于是 content 拿到的纵向最大约束是 `Infinity` ——
 * `weight(1f)` 在无限约束下没有意义，子级只能写死 dp。三个选择器的注释都记着这个坑
 * （`WatchAppPicker.kt` 那句「FlueDialog 里嵌 verticalScroll 崩过一次」）。
 *
 * 于是在 454×454 屏上：可用卡高 ≈ 209dp，而内容（内边距 48 + 标题 24 + 副标题 21 +
 * 输入框 56 + 列表 250 + 按钮 50…）要 483dp，超出一倍多；更要命的是列表自己吞掉纵向拖动，
 * **外层那层滚动根本滚不起来** —— 按钮就永远摸不到。
 *
 * 传 `false` 去掉 `verticalScroll`，content 就拿到有限纵向约束，列表能用
 * `weight(1f, fill = false)`、按钮被钉在卡底。**但这必须是 opt-in**：另外二十几处
 * [FlueAlertDialog] 里**没有**任何 weight 子项，去掉外层滚动后它们在长文案下会直接溢出，
 * 而且再也滚不动 —— 那是把一个 bug 换成二十个。默认值只能是 `true`。
 */

/** 各档弹窗贴边方向：Apple 与 HyperOS 贴底，Material 居中。 */
@Composable
private fun dialogAlignment(uiStyle: UiStyle): Alignment = when (uiStyle) {
    UiStyle.MATERIAL_3 -> Alignment.Center
    else -> Alignment.BottomCenter
}

/** 遮罩浓度。M3 的 scrim 比 Apple 的玻璃遮罩更重。 */
private fun dialogScrimAlpha(uiStyle: UiStyle): Float = when (uiStyle) {
    UiStyle.APPLE_WATCH -> 0.22f
    UiStyle.MIUIX -> 0.30f
    UiStyle.MATERIAL_3 -> 0.32f
}

/**
 * 三档的表面装饰。
 *
 * @param shape 显式形状。默认按风格取；「遮罩圆角」那处要把**正在拖动的圆角**传进来，
 *   让弹窗自己当预览。
 */
@Composable
fun Modifier.flueDialogSurface(shape: Shape? = null): Modifier {
    val style = LauncherTheme.style
    return when (style.uiStyle) {
        UiStyle.APPLE_WATCH -> {
            // 走 panelShape 而不是写死 28dp：弹窗是「面板」这一档，和媒体卡、
            // 大块表面同源。写字面量的后果是同一档里卡片 26 / 面板 30 / 弹窗 28
            // 三个圆角并存，凑一屏就看得出参差（老师报的 AW 卡片圆角不统一）。
            val s = shape ?: style.panelShape
            this
                // clip = false：投影要落在形状外面，clip 了就看不见。
                .shadow(24.dp, s, clip = false)
                .clip(s)
                // MaterialTheme 才是深浅色的真来源。Apple 档的卡片令牌是**为了与页面对比**
                // 才那么定的，当模态表面用会让文字反色 —— 这条约束跟着骨架一起搬过来。
                .background(MaterialTheme.colorScheme.surface)
                // 高光与面板用同一个形状，玻璃边缘才不会跟形状变化脱节。
                .border(style.glass.borderWidth, style.glass.highlight, s)
                .padding(horizontal = 22.dp, vertical = 24.dp)
        }
        UiStyle.MIUIX -> {
            // HyperOS 的弹窗是贴底的大圆角白卡，没有玻璃描边、投影也很轻。
            val s = shape ?: style.panelShape
            this
                .shadow(8.dp, s, clip = false)
                .clip(s)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 20.dp, vertical = 22.dp)
        }
        UiStyle.MATERIAL_3 -> {
            // M3 用 shape scale 的 extraLarge + surfaceContainerHigh，跟着 Material 走。
            val s = shape ?: MaterialTheme.shapes.extraLarge
            this
                .shadow(6.dp, s, clip = false)
                .clip(s)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 24.dp, vertical = 24.dp)
        }
    }
}

/**
 * 弹窗宿主。内容直接写在 [content] 里，外面的窗口 / 遮罩 / 限高 / 滚动都归这里。
 *
 * ## 「点空白关闭」为什么要自己做
 *
 * 老师报「点空白位置应该也能关闭」。`DialogProperties.dismissOnClickOutside` 判的是
 * 「点击落在**内容边界之外**」—— 而我们为了自绘遮罩，内容是一个 `fillMaxSize()` 的 Box，
 * 于是屏幕上任何一点都算「内容之内」，那一项**永远不会触发**。
 *
 * 所以遮罩层自己挂一个无涟漪的 `clickable` 关闭，卡片那层再挂一个**吸收指针**的 sink：
 * 卡片上的空白（标题旁、正文空隙）不能把点击冒泡到遮罩去，否则点一下正文就把弹窗关了。
 *
 * @param maxHeightFraction 内容最高占屏比。默认 0.92 —— 圆屏上留一点余量，按钮不会被挤出去。
 * @param scrollableContent 卡片整块是否可纵向滚动。见下面那段。
 */
@Composable
fun FlueDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    maxHeightFraction: Float = 0.92f,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    scrollableContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LauncherTheme.style
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false,
            // 见类注释：漏了这一项就是 D1 那条「顶部空一条」。
            decorFitsSystemWindows = false,
        ),
    ) {
        ProvideDialogUiScale {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dialogScrimAlpha(style.uiStyle)))
                    // 遮罩自己收「点空白关闭」，见上面那段注释。无涟漪：整屏的涟漪很怪。
                    .then(
                        if (dismissOnClickOutside) {
                            Modifier.clickable(
                                interactionSource = null,
                                indication = null,
                                onClick = onDismissRequest,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = dialogAlignment(style.uiStyle),
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = dialogAlignment(style.uiStyle),
                ) {
                    val maxDialogHeight = maxHeight * maxHeightFraction
                    // 无条件 remember：`scrollableContent` 在一次组合里不会变，但把
                    // rememberScrollState 塞进 if 分支会让 slot table 的结构跟着参数走，
                    // 白省一个对象、换来一类难查的状态丢失。
                    val contentScrollState = rememberScrollState()
                    Column(
                        modifier = modifier
                            .fillMaxWidth()
                            .heightIn(max = maxDialogHeight)
                            // 卡片吸收指针：卡片上的空白不该把点击冒泡到遮罩，
                            // 否则点一下正文就关窗了。
                            .flueAbsorbPointer()
                            .then(
                                if (scrollableContent) {
                                    Modifier.verticalScroll(contentScrollState)
                                } else {
                                    Modifier
                                },
                            )
                            .flueDialogSurface(shape),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = content,
                    )
                }
            }
        }
    }
}

/**
 * 吸收落在这个节点上的指针序列，不让它冒泡给祖先的 `clickable`。
 *
 * 只吃「按下 → 抬起」这一串，**不消费 change**：卡片里的滚动、输入框、按钮照常工作
 * （它们是子节点，先拿到事件）。这里等的是没人处理的那一份。
 *
 * T-06 之后仍然成立，而且更稳了：`awaitPointerEvent()` 走的是 Main pass（子 → 父），
 * 列表的纵向拖动在子节点就被消费掉，这里只是看着。而 `scrollableContent = false` 的弹窗
 * 里卡片**不再是滚动容器** —— 原来「列表 + 卡片」两层可滚容器嵌套着抢纵向手势（列表吞掉
 * 拖动、外层滚不动，那正是按钮摸不到的另一半原因），现在纵向手势只有列表一家收。
 */
private fun Modifier.flueAbsorbPointer(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
        }
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
@OptIn(ExperimentalLayoutApi::class)
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
    dismissOnClickOutside: Boolean = true,
    scrollableContent: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val style = LauncherTheme.style
    FlueDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnClickOutside = dismissOnClickOutside,
        scrollableContent = scrollableContent,
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = if (style.uiStyle == UiStyle.APPLE_WATCH) 22.sp else 19.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (!text.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (content != null) {
            Spacer(Modifier.height(16.dp))
            content()
        }
        Spacer(Modifier.height(20.dp))
        FlueDialogButtons(
            confirmText = confirmText,
            onConfirm = onConfirm,
            dismissText = dismissText,
            onDismiss = onDismissRequest,
            confirmEnabled = confirmEnabled,
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlueDialogButtons(
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
) {
    val style = LauncherTheme.style
    when (style.uiStyle) {
        UiStyle.APPLE_WATCH -> Column(modifier = modifier.fillMaxWidth()) {
            FlueDialogButton(
                text = confirmText,
                onClick = onConfirm,
                primary = true,
                enabled = confirmEnabled,
                height = 54.dp,
            )
            if (dismissText != null && onDismiss != null) {
                Spacer(Modifier.height(8.dp))
                FlueDialogButton(
                    text = dismissText,
                    onClick = onDismiss,
                    primary = false,
                    height = 46.dp,
                )
            }
        }
        UiStyle.MIUIX -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (dismissText != null && onDismiss != null) {
                Box(modifier = Modifier.weight(1f)) {
                    FlueDialogButton(dismissText, onDismiss, primary = false, height = 46.dp)
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                FlueDialogButton(confirmText, onConfirm, primary = true, enabled = confirmEnabled, height = 46.dp)
            }
        }
        UiStyle.MATERIAL_3 -> FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (dismissText != null && onDismiss != null) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text(dismissText) }
                Spacer(Modifier.height(0.dp))
            }
            androidx.compose.material3.TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmText)
            }
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
    height: androidx.compose.ui.unit.Dp = 48.dp,
) {
    val style = LauncherTheme.style
    val background = when {
        !enabled -> style.secondaryTextColor.copy(alpha = 0.18f)
        primary -> style.accentColor
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val textColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        primary -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlueAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
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
        scrollableContent = scrollableContent,
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
                    // 见上面 T-06 那段：不标 weight 的话这一块会把整张卡吃光，按钮被挤出屏幕。
                    // 卡片可滚时**不能**标 —— 那时纵向约束是 Infinity，weight 无从分配。
                    .then(if (scrollableContent) Modifier else Modifier.weight(1f, fill = false)),
                horizontalAlignment = if (style.uiStyle == UiStyle.MATERIAL_3) {
                    Alignment.Start
                } else {
                    Alignment.CenterHorizontally
                },
            ) {
                androidx.compose.material3.ProvideTextStyle(
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                ) { title() }
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
        when (style.uiStyle) {
            // watchOS：竖排整宽大胶囊，主操作在上。整块胶囊都是点击目标。
            UiStyle.APPLE_WATCH -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FlueDialogActionSlot(primary = true, height = 50.dp, content = confirmButton)
                if (dismissButton != null) {
                    Spacer(Modifier.height(8.dp))
                    FlueDialogActionSlot(primary = false, height = 46.dp, content = dismissButton)
                }
                if (extraAction != null) {
                    Spacer(Modifier.height(8.dp))
                    // 第三个动作独占一行胶囊，不与「取消」挤在同一枚里。
                    FlueDialogActionSlot(primary = false, height = 42.dp, content = extraAction)
                }
            }
            // HyperOS：左右等宽两枚填色胶囊铺满一行，不是右对齐的文字按钮。
            UiStyle.MIUIX -> Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (dismissButton != null) {
                        FlueDialogActionSlot(
                            primary = false,
                            height = 46.dp,
                            modifier = Modifier.weight(1f),
                            content = dismissButton,
                        )
                    }
                    FlueDialogActionSlot(
                        primary = true,
                        height = 46.dp,
                        modifier = Modifier.weight(1f),
                        content = confirmButton,
                    )
                }
                if (extraAction != null) {
                    Spacer(Modifier.height(8.dp))
                    FlueDialogActionSlot(primary = false, height = 42.dp, content = extraAction)
                }
            }
            // 窄屏按按钮实际宽度换行，避免末个按钮被压到零宽、逐字撑高整张弹窗。
            UiStyle.MATERIAL_3 -> FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                extraAction?.invoke()
                dismissButton?.invoke()
                confirmButton()
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
    val background = if (primary) {
        style.accentColor.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(background),
        contentAlignment = Alignment.Center,
        // 关键：把最小约束下传，子按钮被撑满 → 整块胶囊都是它的点击目标。
        propagateMinConstraints = true,
    ) {
        content()
    }
}
