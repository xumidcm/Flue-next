package com.flue.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.ui.common.flueElasticNestedScroll
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.input.flueRotaryScrollable
import com.flue.launcher.ui.input.ROTARY_SCROLL_MULTIPLIER
import com.flue.launcher.ui.input.requestFocusAfterFirstFrame
import com.flue.launcher.ui.settings.miuix.MiuixSettingsCard
import com.flue.launcher.ui.settings.miuix.MiuixSettingsRow
import com.flue.launcher.ui.settings.miuix.MiuixSlider
import com.flue.launcher.ui.settings.miuix.MiuixSwitch
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.UiStyle
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.standard.WatchCanvasPreset
import com.flue.launcher.watchface.standard.WatchCanvasSettings
import com.flue.launcher.watchface.standard.WatchmakerWatchFaceHost
import com.flue.launcher.watchface.standard.rememberWatchmakerWatchDataProvider
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

const val EXTRA_CANVAS_ADJUST_WATCHFACE_ID = "canvas_adjust_watchface_id"

/**
 * 画布调节页：半透明面板浮在**真实表盘**上，调参数即时看到结果。
 *
 * 结构参考 clox 的 `WatchfaceConfigActivity`（底部圆角卡片、点卡片外收起、
 * 高度封顶后内部滚动），但有一处**故意不照抄**：clox 那个 Activity 用的是
 * `Theme.CloX.Transparent`，透明窗口浮在正在跑的表盘服务上面。Flue 的表盘
 * 长在 [LauncherActivity] 里，透明窗口不会让底下那个 Activity 走 onStop ——
 * 于是 Launcher 的渲染器和本页的渲染器会**同时跑**，312 那种 377 层表盘直接
 * 双份帧开销。所以这里用普通不透明窗口，本页自己挂一个
 * [WatchmakerWatchFaceHost]：底下 Activity 正常 onStop 停渲染，全程只有一个
 * 渲染器在画。老师要的「半透明」是**面板**半透明，那部分照做。
 *
 * 只对 Watchmaker 表盘有意义（画布变换是 .watch 的设计画布 → 屏幕的映射），
 * 入口在设置页已按 `isWatchmaker` 判过，这里再兜一次底。
 */
class CanvasAdjustActivity : FlueComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecentsVisibility.apply(this)
        val requestedId = intent.getStringExtra(EXTRA_CANVAS_ADJUST_WATCHFACE_ID)
        setContent {
            val vm: LauncherViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShapeOverride by vm.screenShapeOverride.collectAsStateWithLifecycle()
            WatchLauncherTheme(
                themeMode = themeMode,
                uiStyle = uiStyle,
                screenShape = screenShapeOverride,
            ) {
                ProvideGlobalUiScale(globalUiScalePercent) {
                    CanvasAdjustScreen(
                        vm = vm,
                        requestedWatchFaceId = requestedId,
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        fun start(context: Context, watchFaceId: String) {
            context.startActivity(
                Intent(context, CanvasAdjustActivity::class.java)
                    .putExtra(EXTRA_CANVAS_ADJUST_WATCHFACE_ID, watchFaceId)
            )
        }
    }
}

@Composable
private fun CanvasAdjustScreen(
    vm: LauncherViewModel,
    requestedWatchFaceId: String?,
    onDismiss: () -> Unit,
) {
    val availableWatchFaces by vm.availableWatchFaces.collectAsStateWithLifecycle()
    val selectedWatchFace by vm.selectedWatchFace.collectAsStateWithLifecycle()
    val canvasDefault by vm.watchfaceCanvasDefault.collectAsStateWithLifecycle()
    val canvasOverrides by vm.watchfaceCanvasOverrides.collectAsStateWithLifecycle()
    val refreshToken by vm.watchFaceRefreshToken.collectAsStateWithLifecycle()
    val gravityParallaxEnabled by vm.watchfaceGravityParallaxEnabled.collectAsStateWithLifecycle()
    val gravityBackdropBlurEnabled by
        vm.watchfaceGravityBackdropBlurEnabled.collectAsStateWithLifecycle()
    val textEntryAnimation by vm.watchFaceTextEntryAnimation.collectAsStateWithLifecycle()

    // 传进来的 id 优先；找不到（表盘刚被删）就退回当前选中的那张。
    val descriptor: WatchFaceDescriptor = remember(requestedWatchFaceId, availableWatchFaces, selectedWatchFace) {
        availableWatchFaces.firstOrNull { it.id == requestedWatchFaceId } ?: selectedWatchFace
    }

    // 三层继承与设置页同源：覆盖表里有这张表盘就用它自己的，否则用全局默认。
    val followsDefault = descriptor.id !in canvasOverrides
    val persisted = canvasOverrides[descriptor.id] ?: canvasDefault

    // draft 是**实时预览**用的那一份：拖动中每帧更新它、喂给渲染器，但不落盘。
    // 落盘只在手指抬起（onValueChangeFinished）时发生 —— 每帧一次 DataStore 写
    // 会把滑杆拖卡，这条教训在设置页原来那套滑杆里已经写死了。
    var draft by remember { mutableStateOf(persisted) }
    // 落盘值变了（滑杆抬手写回、或者跟随开关切换到了另一层）就把 draft 拉回来对齐。
    LaunchedEffect(persisted) { draft = persisted }

    val commit: (WatchCanvasSettings) -> Unit = { updated ->
        draft = updated
        if (followsDefault) vm.setWatchfaceCanvasDefault(updated)
        else vm.setWatchfaceCanvasOverride(descriptor.id, updated)
    }

    val dataProvider = rememberWatchmakerWatchDataProvider(active = true)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (descriptor.isWatchmaker) {
            WatchmakerWatchFaceHost(
                descriptor = descriptor,
                isFaceVisible = true,
                refreshToken = refreshToken,
                onLongPress = null,
                onDoubleTap = null,
                // 预览页不做兜底切换：加载失败就让它空着，避免把用户正在调的
                // 表盘在后台悄悄换成内置表盘。真正的兜底逻辑在桌面那条路径上。
                onLoadFailure = { _, _ -> },
                dataProvider = dataProvider,
                canvasSettings = draft,
                gravityParallaxEnabled = gravityParallaxEnabled,
                gravityBackdropBlurEnabled = gravityBackdropBlurEnabled,
                textEntryAnimationEnabled = textEntryAnimation,
                // 本页没有覆层进出，退场动画恒关，免得面板一动表盘文字就跟着淡。
                textExitAnimationEnabled = false,
                textExitAnimationActive = false,
            )
        }

        val configuration = LocalConfiguration.current
        val round = isFlueScreenRound()
        Column(modifier = Modifier.fillMaxSize()) {
            // 表盘可视区：点这里收起，等价于 clox 的 root.setOnClickListener { finish() }。
            // 做成上方的兄弟节点而不是整屏遮罩，面板就不必去吞点击 ——
            // 命中测试一旦落在面板里的任何 pointerInput 节点上，就不会再传到这里。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )
            CanvasAdjustPanel(
                faceName = descriptor.displayName,
                settings = draft,
                followsDefault = followsDefault,
                maxPanelHeight = configuration.screenHeightDp.dp * 0.62f,
                horizontalMargin = if (round) 20.dp else 10.dp,
                bottomMargin = if (round) 16.dp else 8.dp,
                onFollowToggle = { follow ->
                    vm.setWatchfaceCanvasFollowsDefault(descriptor.id, follow)
                },
                onPreview = { draft = it },
                onCommit = commit,
                onDone = onDismiss,
            )
        }
    }
}

/**
 * 半透明调节面板。三套 UI 共用这一套内容与状态，只有**容器外壳**和各行的
 * 样式按 [UiStyle] 分岔 —— 内容顺序三套一致，免得换风格后功能位置也跟着变。
 */
@Composable
private fun CanvasAdjustPanel(
    faceName: String,
    settings: WatchCanvasSettings,
    followsDefault: Boolean,
    maxPanelHeight: Dp,
    horizontalMargin: Dp,
    bottomMargin: Dp,
    onFollowToggle: (Boolean) -> Unit,
    onPreview: (WatchCanvasSettings) -> Unit,
    onCommit: (WatchCanvasSettings) -> Unit,
    onDone: () -> Unit,
) {
    val style = LauncherTheme.style
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocusAfterFirstFrame() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalMargin)
            .padding(bottom = bottomMargin)
            .heightIn(max = maxPanelHeight)
            .clip(style.panelShape)
            // glassThick 而不是 glass：厚档的判据是「这块表面下面压着别的内容」，
            // 这里压的是表盘本体（没有被根底压暗过）。薄档在花哨表盘上会让
            // 文字和底下的图案糊在一起。它的底色 alpha 0.82/0.88 也正对上
            // clox 那张卡片的 0xD9（85%）。
            .background(style.glassThick.fill)
            .border(style.glassThick.borderWidth, style.glassThick.highlight, style.panelShape)
                .flueRotaryScrollable(focusRequester, ROTARY_SCROLL_MULTIPLIER) { rotaryDelta ->
                scope.launch { scrollState.scrollBy(-rotaryDelta) }
            }
            .flueElasticNestedScroll(scrollState)
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            Text(
                text = stringResource(R.string.settings_watchface_canvas_section),
                color = style.titleColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = faceName, color = style.secondaryTextColor, fontSize = 11.sp)
        }

        CanvasSwitchRow(
            title = stringResource(R.string.settings_watchface_canvas_follow_default),
            subtitle = if (followsDefault) {
                stringResource(R.string.settings_watchface_canvas_follow_default_on)
            } else {
                stringResource(R.string.settings_watchface_canvas_follow_default_off)
            },
            checked = followsDefault,
            onToggle = onFollowToggle,
        )

        CanvasPresetGrid(
            selected = settings.preset,
            onSelect = { onCommit(settings.copy(preset = it)) },
        )

        CanvasSliderRow(
            title = stringResource(R.string.settings_watchface_canvas_zoom_x),
            value = settings.zoomX,
            range = CANVAS_ZOOM_RANGE,
            steps = CANVAS_ZOOM_STEPS,
            format = ::formatZoom,
            onPreview = { onPreview(settings.copy(zoomX = it)) },
            onCommit = { onCommit(settings.copy(zoomX = it)) },
        )
        CanvasSliderRow(
            title = stringResource(R.string.settings_watchface_canvas_zoom_y),
            value = settings.zoomY,
            range = CANVAS_ZOOM_RANGE,
            steps = CANVAS_ZOOM_STEPS,
            format = ::formatZoom,
            onPreview = { onPreview(settings.copy(zoomY = it)) },
            onCommit = { onCommit(settings.copy(zoomY = it)) },
        )
        CanvasSliderRow(
            title = stringResource(R.string.settings_watchface_canvas_offset_x),
            value = settings.offsetX,
            range = CANVAS_OFFSET_RANGE,
            steps = CANVAS_OFFSET_STEPS,
            format = ::formatOffset,
            onPreview = { onPreview(settings.copy(offsetX = it)) },
            onCommit = { onCommit(settings.copy(offsetX = it)) },
        )
        CanvasSliderRow(
            title = stringResource(R.string.settings_watchface_canvas_offset_y),
            value = settings.offsetY,
            range = CANVAS_OFFSET_RANGE,
            steps = CANVAS_OFFSET_STEPS,
            format = ::formatOffset,
            onPreview = { onPreview(settings.copy(offsetY = it)) },
            onCommit = { onCommit(settings.copy(offsetY = it)) },
        )

        CanvasActionRow(
            resetLabel = stringResource(R.string.settings_watchface_canvas_reset),
            doneLabel = stringResource(R.string.canvas_adjust_done),
            onReset = { onCommit(WatchCanvasSettings.DEFAULT) },
            onDone = onDone,
        )
    }
}

/**
 * 六个铺法做成两列网格而不是六行竖列：竖列会把面板撑到必须滚动，而面板内的
 * 竖滚和表盘区的手势、旋钮滚动叠在一起容易打架。标题都是 2~3 个字，两列放得下。
 */
@Composable
private fun CanvasPresetGrid(
    selected: WatchCanvasPreset,
    onSelect: (WatchCanvasPreset) -> Unit,
) {
    val style = LauncherTheme.style
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WatchCanvasPreset.entries.chunked(2).forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowPresets.forEach { preset ->
                    CanvasPresetCell(
                        label = stringResource(canvasPresetTitleRes(preset)),
                        selected = preset == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(preset) },
                    )
                }
                // 奇数个时补一格空位，最后一格才不会被拉成整行宽。
                if (rowPresets.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
        Text(
            text = stringResource(canvasPresetDescRes(selected)),
            color = style.secondaryTextColor,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CanvasPresetCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val style = LauncherTheme.style
    val pressed = rememberCanvasPressedState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed.value) 0.95f else 1f,
        animationSpec = style.pressScaleSpec,
        label = "canvasPresetPress",
    )
    val shape = if (style.uiStyle == UiStyle.MIUIX) style.rowShape else style.chipShape
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(shape)
            .background(
                when {
                    pressed.value && selected -> style.selectedCardColor.copy(alpha = 0.80f)
                    pressed.value -> style.pressedCardColor
                    selected -> style.selectedCardColor
                    else -> style.cardColor
                }
            )
            .border(1.dp, if (selected) style.accentColor else style.outlineColor, shape)
            .canvasPressGesture(pressed, onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) style.accentColor else style.bodyColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CanvasSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val style = LauncherTheme.style
    if (style.uiStyle == UiStyle.MIUIX) {
        MiuixSettingsCard {
            MiuixSettingsRow(
                title = title,
                subtitle = subtitle,
                onClick = { onToggle(!checked) },
                trailing = { MiuixSwitch(checked = checked, onCheckedChange = onToggle) },
            )
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(style.rowShape)
            .background(style.cardColor)
            .border(1.dp, style.outlineColor, style.rowShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = style.titleColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(3.dp))
            Text(text = subtitle, color = style.secondaryTextColor, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.size(10.dp))
        CanvasHandDrawnSwitch(checked = checked, onToggle = onToggle)
    }
}

/**
 * 手搓开关。全项目的开关都是手搓的（设置页两条分支都没用
 * `androidx.compose.material3.Switch`），这里跟上同一套做法，并按老师要求
 * 「尺寸加大 + 点按震动」：轨道 44×26dp，切换时走 ToggleOn/ToggleOff 触感。
 */
@Composable
private fun CanvasHandDrawnSwitch(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val style = LauncherTheme.style
    val haptics = LocalHapticFeedback.current
    val knobProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = 760f,
        ),
        label = "canvasSwitchKnob",
    )
    val trackColor = if (checked) style.positiveColor else style.secondaryTextColor.copy(alpha = 0.45f)
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .pointerInput(checked) {
                detectTapGestures {
                    haptics.performHapticFeedback(
                        if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn
                    )
                    onToggle(!checked)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .padding(start = 3.dp, top = 3.dp)
                .size(20.dp)
                // 行程 = 44 - 20 - 3*2 = 18dp。
                .graphicsLayer { translationX = 18.dp.toPx() * knobProgress }
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/**
 * 滑杆一律用预设组件（老师要求「滑动条用预设不自绘」）：MIUIX 走
 * [MiuixSlider]，另外两套走官方 M3 [Slider]（MD3 侧因此自动吃到
 * Expressive 的形状与动效，因为 `MaterialExpressiveTheme` 已在主题层生效）。
 *
 * [onPreview] 每帧调、只改内存里的 draft 喂给渲染器；[onCommit] 只在抬手时调、
 * 才写 DataStore。渲染器那侧 `setCanvasSettings` 自带相等判断，逐帧调是安全的。
 */
@Composable
private fun CanvasSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    val style = LauncherTheme.style
    var localValue by remember(title) { mutableStateOf(value) }
    var showInput by remember { mutableStateOf(false) }
    LaunchedEffect(value) { localValue = value }

    if (showInput) {
        CanvasValueInputDialog(
            title = title,
            current = localValue,
            range = range,
            format = format,
            onDismiss = { showInput = false },
            onConfirm = { v -> localValue = v; onCommit(v); showInput = false },
        )
    }

    if (style.uiStyle == UiStyle.MIUIX) {
        MiuixSettingsCard {
            // 数值放 trailing slot 而不是 subtitle：MiuixSettingsRow 是共享组件，
            // 为「subtitle 可点」给它加一个参数会污染所有调用点，而它本来就有
            // trailing 可以放任意 Composable。
            MiuixSettingsRow(
                title = title,
                trailing = {
                    Text(
                        text = format(localValue),
                        color = style.accentColor,
                        fontSize = 13.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.canvasValueClickable { showInput = true },
                    )
                },
            )
            Box(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
                MiuixSlider(
                    value = localValue,
                    onValueChange = { localValue = it; onPreview(it) },
                    valueRange = range,
                    steps = steps,
                    onValueChangeFinished = { onCommit(localValue) },
                )
            }
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(style.rowShape)
            .background(style.cardColor)
            .border(1.dp, style.outlineColor, style.rowShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = style.titleColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = format(localValue),
                color = style.accentColor,
                fontSize = 12.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.canvasValueClickable { showInput = true },
            )
        }
        Slider(
            value = localValue,
            onValueChange = { localValue = it; onPreview(it) },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onCommit(localValue) },
            colors = SliderDefaults.colors(
                thumbColor = style.accentColor,
                activeTrackColor = style.accentColor,
            ),
        )
    }
}

private fun Modifier.canvasValueClickable(onClick: () -> Unit): Modifier =
    this.pointerInput(onClick) {
        detectTapGestures(onTap = { onClick() })
    }

@Composable
private fun CanvasValueInputDialog(
    title: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    // 初值直接由数值格式化，不从 format() 的展示文本里剥字符 —— 那串带单位
    // （`1.20×` / `-200 px`），剥出来还要处理空格、小数位被展示格式锁死，
    // 而这里要的就是可编辑的原始值。
    var draft by remember { mutableStateOf(canvasInputInitialText(current)) }
    val parsed = draft.trim().toFloatOrNull()
    val valid = parsed != null && parsed in range
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    isError = !valid && draft.isNotEmpty(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(parsed!!) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.canvas_input_range_hint,
                        format(range.start), format(range.endInclusive)),
                    fontSize = 11.sp,
                    color = LauncherTheme.style.secondaryTextColor,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onConfirm(parsed!!) }, enabled = valid) {
                Text(stringResource(R.string.canvas_adjust_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun CanvasActionRow(
    resetLabel: String,
    doneLabel: String,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CanvasActionButton(
            label = resetLabel,
            primary = false,
            modifier = Modifier.weight(1f),
            onClick = onReset,
        )
        CanvasActionButton(
            label = doneLabel,
            primary = true,
            modifier = Modifier.weight(1f),
            onClick = onDone,
        )
    }
}

@Composable
private fun CanvasActionButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val style = LauncherTheme.style
    val pressed = rememberCanvasPressedState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed.value) 0.96f else 1f,
        animationSpec = style.pressScaleSpec,
        label = "canvasActionPress",
    )
    val shape = if (style.uiStyle == UiStyle.MIUIX) style.rowShape else style.chipShape
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(shape)
            .background(
                when {
                    primary && pressed.value -> style.accentColor.copy(alpha = 0.82f)
                    primary -> style.accentColor
                    pressed.value -> style.pressedCardColor
                    else -> style.cardColor
                }
            )
            .then(
                if (primary) Modifier else Modifier.border(1.dp, style.outlineColor, shape)
            )
            .canvasPressGesture(pressed, onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (primary) Color.White else style.bodyColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun rememberCanvasPressedState(): MutableState<Boolean> {
    val state = remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { state.value = false } }
    return state
}

/** 按下即反馈、抬手才触发 —— 与设置页 `instantPressGesture` 同一套手感。 */
private fun Modifier.canvasPressGesture(
    pressed: MutableState<Boolean>,
    onClick: () -> Unit,
): Modifier = this.pointerInput(onClick) {
    detectTapGestures(
        onPress = {
            pressed.value = true
            tryAwaitRelease()
            pressed.value = false
        },
        onTap = { onClick() },
    )
}

// 区间与档位沿用设置页原来那套滑杆的取值，改这里等于改用户已经习惯的手感。
/**
 * 缩放与偏移的滑杆量程。老师要求「最大数值都乘个 2」：缩放 2× → 4×，
 * 偏移 ±200 → ±400。
 *
 * 下限不跟着乘：缩放下限 0.5 已经是「缩到一半」，再往下没有实用意义，而偏移
 * 本来就对称。两个量程都仍在运行时钳制之内（`MAX_CANVAS_ZOOM = 5f`、
 * `MAX_CANVAS_OFFSET = 4096f`，见 `WatchmakerRuntimeContracts.kt`），
 * 所以不需要同步放宽那一侧。
 *
 * 步长保持原来的粒度不变（缩放每格 0.05、偏移每格 10），量程翻倍就把格数
 * 也翻上去 —— 否则同一根滑杆走同样的距离要跨两倍数值，手动微调会变粗糙。
 */
private val CANVAS_ZOOM_RANGE = 0.5f..4f
private const val CANVAS_ZOOM_STEPS = 69
private val CANVAS_OFFSET_RANGE = -400f..400f
private const val CANVAS_OFFSET_STEPS = 79

private fun formatZoom(value: Float): String = "%.2f×".format(Locale.US, value)

private fun formatOffset(value: Float): String = "${value.roundToInt()} px"

/**
 * 手输框的初值文本。整数值不带小数点（偏移是 px，`-200` 比 `-200.0` 好改），
 * 其余保留两位（缩放的一格是 0.05）。
 *
 * 一律用 [Locale.US]：`toFloatOrNull()` 只认小数点，而中文/日文区域下
 * `%.2f` 在某些实现上会给出逗号做小数分隔符，那样自己的初值都解析不回来。
 */
private fun canvasInputInitialText(value: Float): String =
    if (value == value.roundToInt().toFloat()) {
        value.roundToInt().toString()
    } else {
        "%.2f".format(Locale.US, value)
    }

private fun canvasPresetTitleRes(preset: WatchCanvasPreset): Int = when (preset) {
    WatchCanvasPreset.FIT -> R.string.settings_watchface_canvas_preset_fit
    WatchCanvasPreset.FILL -> R.string.settings_watchface_canvas_preset_fill
    WatchCanvasPreset.VERTICAL -> R.string.settings_watchface_canvas_preset_vertical
    WatchCanvasPreset.HORIZONTAL -> R.string.settings_watchface_canvas_preset_horizontal
    WatchCanvasPreset.STRETCH -> R.string.settings_watchface_canvas_preset_stretch
    WatchCanvasPreset.CUSTOM -> R.string.settings_watchface_canvas_preset_custom
}

private fun canvasPresetDescRes(preset: WatchCanvasPreset): Int = when (preset) {
    WatchCanvasPreset.FIT -> R.string.settings_watchface_canvas_preset_fit_desc
    WatchCanvasPreset.FILL -> R.string.settings_watchface_canvas_preset_fill_desc
    WatchCanvasPreset.VERTICAL -> R.string.settings_watchface_canvas_preset_vertical_desc
    WatchCanvasPreset.HORIZONTAL -> R.string.settings_watchface_canvas_preset_horizontal_desc
    WatchCanvasPreset.STRETCH -> R.string.settings_watchface_canvas_preset_stretch_desc
    WatchCanvasPreset.CUSTOM -> R.string.settings_watchface_canvas_preset_custom_desc
}
