package com.flue.launcher.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 玻璃质感令牌（三套风格共用一份配方）。
 *
 * 「玻璃」不是一个颜色，是三层叠加，缺一层就塑料：
 * 1. 根底 `screenBackground.copy(alpha = 0.62f)` —— 表盘背衬透上来，这层才是「毛」；
 * 2. [fill]：表面顶浓底淡的半透明填充 —— 玻璃自身的厚度；
 * 3. [highlight]：顶亮底暗的极细描边 —— 玻璃边缘的高光。贴纯黑时没它，
 *    大圆角转角处只有十几个百分点的亮度差，抗锯齿一上去就发虚。
 *
 * **只统一质感，不统一形状**：形状一律由调用方传入，MIUIX 的大圆角、
 * MD3 的 Expressive 形状各自保留，否则三套风格失去区分度。
 */
@Stable
data class GlassSurfaceTokens(
    val fill: Brush,
    val highlight: Brush,
    val borderWidth: Dp,
    /** 需要纯色而非 Brush 的场合（堆叠层背衬、圆形开关底）的等效底色。 */
    val baseColor: Color,
)

/**
 * 玻璃厚度档。分档的判据是**这块表面下面压着什么**：
 *
 * - [Thin] 压着表盘背衬（控制中心 / 负一屏 / 通知中心的卡片、通知条、
 *   快捷启动 dock）。背衬已被根底压暗，薄玻璃透过去仍然干净。
 * - [Thick] 压着别的内容（通知中心那条浮在通知流上方的操作栏）。这里用
 *   薄档会让按钮图标和下面的通知卡糊在一起，必须挡住底下的东西。
 */
enum class GlassTier { Thin, Thick }

/**
 * 生成玻璃令牌。三套风格调用同一个工厂 —— 老师要求玻璃质感统一，
 * 区分度交给形状、强调色、字体与背景色承担。
 *
 * 取值来源：[GlassTier.Thin] 的深色档是快捷启动 dock 那套实测配方
 * （白 0.13→0.06 填充、白 0.18→0.04 描边、0.8dp）原封不动；
 * [GlassTier.Thick] 的两档是通知操作栏原先写死的 `#151515` @0.82 /
 * `#F2F2F6` @0.88，同样一字未改，只是从写死搬进了令牌 —— 留在原地
 * 就等于在统一配方之外另开一份私有配方，下次调玻璃必然漏掉它。
 *
 * 浅色模式的填充层镜像成黑并略收；边缘高光始终是白色。高光若也跟着
 * 镜像成黑，浅色主题里的玻璃会读成普通描边卡片，而不是受光的透明边缘。
 */
fun flueGlassTokens(
    darkMode: Boolean,
    tier: GlassTier = GlassTier.Thin,
): GlassSurfaceTokens {
    val fillTint = if (darkMode) Color.White else Color.Black
    val highlightTint = Color.White
    // 高光描边两档共用一份配方：厚档底子虽然接近不透明，但那圈亮边是
    // 「玻璃」的读法所在，去掉就变成一块普通的深色药丸了。
    val highlight = Brush.verticalGradient(
        listOf(
            highlightTint.copy(alpha = if (darkMode) 0.18f else 0.32f),
            highlightTint.copy(alpha = if (darkMode) 0.04f else 0.08f),
        )
    )
    return when (tier) {
        GlassTier.Thin -> GlassSurfaceTokens(
            fill = Brush.verticalGradient(
                listOf(
                    fillTint.copy(alpha = if (darkMode) 0.13f else 0.10f),
                    fillTint.copy(alpha = if (darkMode) 0.06f else 0.045f),
                )
            ),
            highlight = highlight,
            borderWidth = 0.8.dp,
            // 比 fill 顶色再浓一点：堆叠层只露出 6dp 左右的边，太淡就看不出层数。
            baseColor = fillTint.copy(alpha = if (darkMode) 0.16f else 0.11f),
        )
        GlassTier.Thick -> {
            // 厚档保持平铺而不做渐变：操作栏只有 30dp 高，渐变在这个高度上
            // 看不出来，反而会让上下两端的挡光能力不一致。
            val base = if (darkMode) {
                Color(0xFF151515).copy(alpha = 0.82f)
            } else {
                Color(0xFFF2F2F6).copy(alpha = 0.88f)
            }
            GlassSurfaceTokens(
                fill = SolidColor(base),
                highlight = highlight,
                borderWidth = 0.8.dp,
                baseColor = base,
            )
        }
    }
}

@Stable
data class LauncherStyle(
    val uiStyle: UiStyle,
    val screenBackground: Color,
    val screenScrim: Brush,
    val cardColor: Color,
    val pressedCardColor: Color,
    val selectedCardColor: Color,
    val outlineColor: Color,
    val titleColor: Color,
    val bodyColor: Color,
    val secondaryTextColor: Color,
    val accentColor: Color,
    val positiveColor: Color,
    val topBarChipColor: Color,
    val topBarTextColor: Color,
    val appListBaseColor: Color,
    val appListFadeColor: Color,
    val appListPressedOverlay: Color,
    val cardShape: Shape,
    val compactShape: Shape,
    val bubbleShape: Shape,
    // 五档形状语言（chip < row < card < panel）。cardShape/compactShape 保留是
    // 兼容旧调用点；新代码一律走这四档，禁止再写 RoundedCornerShape(数字) 字面量。
    val chipShape: Shape = CircleShape,
    val rowShape: Shape = RoundedCornerShape(18.dp),
    val panelShape: Shape = RoundedCornerShape(30.dp),
    /** 玻璃质感（控制中心 / 负一屏 / 通知中心的表面材质），三套风格同源。 */
    val glass: GlassSurfaceTokens,
    /** 厚玻璃：浮在别的内容上方的表面（通知操作栏）。见 [GlassTier]。 */
    val glassThick: GlassSurfaceTokens,
    val watchFaceOverlayStrength: Float,
    val faceSwitchEnterSpec: FiniteAnimationSpec<Float>,
    val faceSwitchExitSpec: FiniteAnimationSpec<Float>,
    val pageMotionSpec: FiniteAnimationSpec<Float>,
    val launchMaskEnterSpec: FiniteAnimationSpec<Float>,
    val launchMaskExitSpec: FiniteAnimationSpec<Float>,
    val pressScaleSpec: FiniteAnimationSpec<Float>,
    val switchThumbTravel: Float,
    /**
     * 圆屏胶囊圆角的上限。`null` = 不设上限，取满半高。
     *
     * 三档取值互不相同，这是三套形状语言各自成立的地方：**APPLE 32dp**（标准 56dp 行取满
     * 28dp 半高，是真胶囊；被内容撑高的行停在 32dp 大圆角，不再拉成长药丸）、**MD3 28dp**、
     * **MIUIX 22dp**（HyperOS 的平滑大圆角档）。
     *
     * 三档都给了上限还有一个附带作用：弧深不再随行高无限增长，所以
     * [com.flue.launcher.ui.style.flueRowHorizontalPadding] 可以按名义行高查表，
     * 不需要为长文字行做内在测量。
     *
     * 由 A2 的 [RoundElementShape] 与 `FlueCapsuleGeometry.capsuleRadiusPx` 消费。
     */
    val capsuleMaxRadius: Dp? = null,
    /**
     * 开关整体的视觉缩放。
     *
     * 留这个口子是为 Apple 档的按压放大（照 Kyant 那套，`pressedScale = 1.5`）留余量：
     * 窄圆屏上 64dp 轨道占屏宽近 28%，得整体收一档才放得下。
     */
    val switchVisualScale: Float = 1f,
    /** 开关的最小触摸目标。视觉可以缩，可点区域不缩。 */
    val switchTouchTarget: Dp = 48.dp,
    /** 该风格加载指示器的默认边长。三套的视觉密度不同，默认值也就不同。 */
    val loadingIndicatorSize: Dp = 28.dp,
)

private val LocalLauncherStyle = compositionLocalOf { appleWatchLauncherStyle(darkMode = true) }

val UiStyle.isMaterial: Boolean
    get() = this == UiStyle.MATERIAL_3

val UiStyle.isMiuix: Boolean
    get() = this == UiStyle.MIUIX

/**
 * APPLE_WATCH 档配色。
 *
 * ## 老师报的「配色很诡异」，问题出在三处
 *
 * 1. **强调色是青色**（`ActiveCyan`）。Apple 的系统强调是蓝（`systemBlue`），青色是
 *    Flue 自己的旧配色，摆在一套照 watchOS 做的形状语言里就是不搭。
 * 2. **浅色档的几个令牌是深色档的值**：`outlineColor` 在浅色下仍是**白色 8%**（画在白卡上
 *    等于没有分隔线），`secondaryTextColor` 两档共用一个浅灰（浅色底下几乎读不出来），
 *    `appListPressedOverlay` 也是白色。这不是审美问题，是漏改。
 * 3. **卡片是半透明白**（`0x1FFFFFFF`）。iOS 的分组列表卡片是**不透明**的
 *    （深色 `secondarySystemBackground`、浅色纯白），半透明叠在渐变遮罩上会脏。
 *
 * 所以这一档换成 iOS / watchOS 的系统色：蓝 `#0A84FF`/`#007AFF`、开关绿
 * `#30D158`/`#34C759`、深色卡片 `#1C1C1E`、浅色卡片纯白、分组底 `#F2F2F7`。
 *
 * `appListBaseColor` **刻意仍用半透明**：应用列表的图标底衬要能透出背景图（K 那批背景
 * 功能就靠它），换成不透明会把自定义背景整片盖掉。
 */
fun appleWatchLauncherStyle(darkMode: Boolean): LauncherStyle {
    // 卡片：iOS 分组列表的表面色，两档都不透明。
    val card = if (darkMode) Color(0xFF1C1C1E) else Color.White
    // 图标底衬仍是半透明 —— 要透出应用列表背景图。
    val listBase = if (darkMode) Color(0x1FFFFFFF) else Color(0x14000000)
    val accent = if (darkMode) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val secondaryText = if (darkMode) Color(0x99EBEBF5) else Color(0x993C3C43)
    return LauncherStyle(
        uiStyle = UiStyle.APPLE_WATCH,
        // watchOS 是纯黑底（OLED 上卡片边界自己就出来了）；浅色取 iOS 分组背景。
        screenBackground = if (darkMode) Color.Black else Color(0xFFF2F2F7),
        screenScrim = Brush.verticalGradient(
            listOf(
                Color.Black.copy(alpha = if (darkMode) 0.0f else 0.04f),
                Color.Transparent,
                Color.Black.copy(alpha = if (darkMode) 0.34f else 0.08f)
            )
        ),
        cardColor = card,
        pressedCardColor = if (darkMode) Color(0xFF2C2C2E) else Color(0xFFE5E5EA),
        selectedCardColor = accent.copy(alpha = if (darkMode) 0.22f else 0.14f),
        // 浅色档必须用**黑**做分隔线：原来两档都是白色，浅色下画在白卡上等于没画。
        outlineColor = if (darkMode) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f),
        titleColor = if (darkMode) Color.White else Color(0xFF101216),
        bodyColor = if (darkMode) Color.White else Color(0xFF101216),
        secondaryTextColor = secondaryText,
        accentColor = accent,
        positiveColor = if (darkMode) Color(0xFF30D158) else Color(0xFF34C759),
        topBarChipColor = if (darkMode) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f),
        topBarTextColor = if (darkMode) Color.White else Color(0xFF16181D),
        appListBaseColor = listBase,
        appListFadeColor = Color.Black.copy(alpha = if (darkMode) 0.72f else 0.08f),
        appListPressedOverlay = if (darkMode) {
            Color.White.copy(alpha = 0.08f)
        } else {
            Color.Black.copy(alpha = 0.06f)
        },
        // 26dp 与通知卡对齐（原 24dp），panel 30dp 用于媒体卡等大块表面。
        cardShape = RoundedCornerShape(26.dp),
        compactShape = RoundedCornerShape(18.dp),
        bubbleShape = CircleShape,
        chipShape = CircleShape,
        rowShape = RoundedCornerShape(18.dp),
        panelShape = RoundedCornerShape(30.dp),
        glass = flueGlassTokens(darkMode),
        glassThick = flueGlassTokens(darkMode, GlassTier.Thick),
        watchFaceOverlayStrength = 1f,
        faceSwitchEnterSpec = tween(durationMillis = 220, delayMillis = 70),
        faceSwitchExitSpec = tween(durationMillis = 180),
        pageMotionSpec = tween(durationMillis = 220),
        launchMaskEnterSpec = tween(durationMillis = 140),
        launchMaskExitSpec = tween(durationMillis = 180),
        pressScaleSpec = spring(stiffness = 820f, dampingRatio = 0.74f),
        switchThumbTravel = 22f,
        // 三套里最接近真胶囊的一档：56dp 标准行取满 28dp 半高，就是 watchOS 的读法。
        //
        // 上限不是 null 而是 32dp —— 老师报的「带滑竿的胶囊撑到边缘 / 长文字把胶囊撑得很难看」
        // 就出在这里。设置页那 10 处容器全部显式传 RoundElementRole.CAPSULE，那一档**跳过**
        // RowCapsuleShape 的宽高比护栏直接取半高：100dp 高的滑块行拿到 50dp 半径（两端各一个
        // 50dp 半圆，正好啃在滑轨上），两行文字撑到 76dp 的行拿到 38dp。
        //
        // 32dp 这个值让「标准单行仍是真胶囊、被内容撑高的行退成大圆角」同时成立，
        // 而且半径有了上限之后**弧深不再随行高增长** —— 内边距可以继续按名义行高查表，
        // 不必为长文字去做内在测量（那条路已经在表盘市场三点菜单上崩过一次）。
        capsuleMaxRadius = 32.dp,
        switchVisualScale = 1f,
        switchTouchTarget = 48.dp,
        loadingIndicatorSize = 28.dp,
    )
}

fun materialLauncherStyle(darkMode: Boolean): LauncherStyle {
    val bg = if (darkMode) Color(0xFF0D1118) else Color(0xFFF5F1EA)
    val surface = if (darkMode) Color(0xFF18212D) else Color(0xFFE8E1D6)
    val surfaceAlt = if (darkMode) Color(0xFF243244) else Color(0xFFD9D0C2)
    val accent = if (darkMode) Color(0xFF8CB4FF) else Color(0xFF295EA8)
    val text = if (darkMode) Color(0xFFF4F7FB) else Color(0xFF1E2938)
    return LauncherStyle(
        uiStyle = UiStyle.MATERIAL_3,
        screenBackground = bg,
        screenScrim = Brush.verticalGradient(
            listOf(
                accent.copy(alpha = if (darkMode) 0.16f else 0.12f),
                Color.Transparent,
                surface.copy(alpha = 0.86f)
            )
        ),
        cardColor = surface,
        pressedCardColor = surfaceAlt,
        selectedCardColor = accent.copy(alpha = if (darkMode) 0.22f else 0.18f),
        outlineColor = accent.copy(alpha = if (darkMode) 0.22f else 0.16f),
        titleColor = text,
        bodyColor = text,
        secondaryTextColor = text.copy(alpha = 0.72f),
        accentColor = accent,
        positiveColor = if (darkMode) Color(0xFF7AD982) else Color(0xFF1C7B39),
        topBarChipColor = surfaceAlt,
        topBarTextColor = text,
        appListBaseColor = surface,
        appListFadeColor = bg.copy(alpha = 0.92f),
        appListPressedOverlay = accent.copy(alpha = if (darkMode) 0.12f else 0.08f),
        cardShape = RoundedCornerShape(32.dp),
        compactShape = RoundedCornerShape(24.dp),
        bubbleShape = RoundedCornerShape(30.dp),
        chipShape = CircleShape,
        rowShape = RoundedCornerShape(24.dp),
        panelShape = RoundedCornerShape(36.dp),
        glass = flueGlassTokens(darkMode),
        glassThick = flueGlassTokens(darkMode, GlassTier.Thick),
        watchFaceOverlayStrength = 1.25f,
        faceSwitchEnterSpec = tween(durationMillis = 300, delayMillis = 20),
        faceSwitchExitSpec = tween(durationMillis = 220),
        pageMotionSpec = spring(
            dampingRatio = 0.84f,
            stiffness = Spring.StiffnessMediumLow
        ),
        launchMaskEnterSpec = tween(durationMillis = 220),
        launchMaskExitSpec = tween(durationMillis = 220),
        pressScaleSpec = spring(stiffness = 560f, dampingRatio = 0.82f),
        switchThumbTravel = 20f,
        // M3 有自己的 shape scale，圆屏也停在 28dp（compactShape 的 24dp 略放）。
        capsuleMaxRadius = 28.dp,
        switchVisualScale = 1f,
        switchTouchTarget = 48.dp,
        // Expressive 的 LoadingIndicator 形变靠形状读出来，小于 32dp 就看不清。
        loadingIndicatorSize = 32.dp,
    )
}

/**
 * MIUIX（HyperOS 视觉语言）风格令牌：
 * 大圆角成组卡片（18dp）、行高较高、辅助文字灰色小号、
 * 强调色默认蓝（#3482FF）、浅色底 #F7F7F7 / 深色底 #000000、卡片 #1B1B1B。
 */
fun miuixLauncherStyle(darkMode: Boolean): LauncherStyle {
    // HyperOS 实测值：卡片深色 #1F1F1F（非 #1B1B1B）、正文 90% 白、
    // 次要文字 50% 白（浅色 50% 黑），而不是近似的纯灰。
    val bg = if (darkMode) Color(0xFF000000) else Color(0xFFF7F7F7)
    val card = if (darkMode) Color(0xFF1F1F1F) else Color(0xFFFFFFFF)
    val cardPressed = if (darkMode) Color(0xFF2C2C2C) else Color(0xFFEDEDED)
    val accent = Color(0xFF3482FF)
    val text = if (darkMode) Color(0xE6FFFFFF) else Color(0xFF1A1A1A)
    val secondary = if (darkMode) Color(0x80FFFFFF) else Color(0x80000000)
    return LauncherStyle(
        uiStyle = UiStyle.MIUIX,
        screenBackground = bg,
        screenScrim = Brush.verticalGradient(
            listOf(
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = if (darkMode) 0.18f else 0.04f)
            )
        ),
        cardColor = card,
        pressedCardColor = cardPressed,
        selectedCardColor = accent.copy(alpha = if (darkMode) 0.24f else 0.14f),
        outlineColor = if (darkMode) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f),
        titleColor = text,
        bodyColor = text,
        secondaryTextColor = secondary,
        accentColor = accent,
        // MIUIX 的开关 / 选中态统一使用蓝色强调色，而不是绿色
        positiveColor = accent,
        topBarChipColor = card,
        topBarTextColor = text,
        appListBaseColor = card,
        appListFadeColor = bg.copy(alpha = if (darkMode) 0.86f else 0.92f),
        appListPressedOverlay = if (darkMode) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f),
        cardShape = RoundedCornerShape(18.dp),
        compactShape = RoundedCornerShape(14.dp),
        bubbleShape = RoundedCornerShape(999.dp),
        chipShape = CircleShape,
        rowShape = RoundedCornerShape(14.dp),
        panelShape = RoundedCornerShape(22.dp),
        glass = flueGlassTokens(darkMode),
        glassThick = flueGlassTokens(darkMode, GlassTier.Thick),
        watchFaceOverlayStrength = 1f,
        faceSwitchEnterSpec = tween(durationMillis = 260, delayMillis = 40),
        faceSwitchExitSpec = tween(durationMillis = 200),
        pageMotionSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
        launchMaskEnterSpec = tween(durationMillis = 180),
        launchMaskExitSpec = tween(durationMillis = 200),
        pressScaleSpec = spring(stiffness = 600f, dampingRatio = 0.9f),
        switchThumbTravel = 22f,
        // HyperOS 是大圆角而非胶囊：22dp 与 panelShape 对齐，再大就成药丸了。
        capsuleMaxRadius = 22.dp,
        switchVisualScale = 1f,
        switchTouchTarget = 48.dp,
        loadingIndicatorSize = 28.dp,
    )
}

@Composable
fun ProvideLauncherStyle(
    uiStyle: UiStyle,
    darkMode: Boolean,
    content: @Composable () -> Unit
) {
    val style = remember(uiStyle, darkMode) {
        when (uiStyle) {
            UiStyle.APPLE_WATCH -> appleWatchLauncherStyle(darkMode)
            UiStyle.MATERIAL_3 -> materialLauncherStyle(darkMode)
            UiStyle.MIUIX -> miuixLauncherStyle(darkMode)
        }
    }
    CompositionLocalProvider(LocalLauncherStyle provides style, content = content)
}

object LauncherTheme {
    val style: LauncherStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalLauncherStyle.current
}

/**
 * 圆屏胶囊形状：够扁的行取满半高（真胶囊），偏方/偏长的块退回 [fallback]。
 *
 * ## 为什么不能直接用 [CircleShape]
 *
 * `CircleShape` 等于 `RoundedCornerShape(50%)`，半径按**短边**算：
 * - 设置行（181dp × 56dp，宽高比 3.2）→ 半径 28 = 半高 → 正好是胶囊 ✅
 * - 关于页整块竖卡片（181dp × 400dp，宽高比 0.45）→ 半径 90 = 半宽 → 左右各顶一个
 *   大半圆的「竖药丸」，里面的图标文字全被啃掉 ❌
 *
 * 所以护栏必须看实际宽高比。放在 [Shape] 里而不是调用点：调用点在组合期不知道自己
 * 会被测成多高（[MiuixSettingsCard] 这种容器更是完全取决于塞了几行），而
 * `createOutline` 拿到的是绘制期的真实 size —— 只影响裁剪轮廓，不参与测量，
 * 不会踩「布局属性 → 重测量」那个反馈环。
 *
 * @param threshold 宽 ≥ 高 × 该值才算「行状」。2.4 是按圆屏实测量出来的分界：
 *   普通设置行 3.2 在上面，滑块行（含标题 + 数值 + 轨道，约 100dp 高）1.8 在下面 ——
 *   滑块行取满半高会让 50dp 的圆角啃到滑轨两端。
 */
internal data class RowCapsuleShape(
    private val fallback: Shape,
    private val threshold: Float = 2.4f,
    /**
     * 胶囊圆角上限（像素）。`null` = 取满半高（真胶囊，仅 APPLE_WATCH）。
     *
     * MIUIX / MD3 有了上限之后，圆屏行是「大圆角」而不是药丸 —— 三套风格的形状语言
     * 才各自成立。取值来自 [LauncherStyle.capsuleMaxRadius]。
     */
    private val maxRadiusPx: Float? = null,
) : Shape {
    /** 给定尺寸下是否取真胶囊（= 半高圆角）。抽出来是为了可单测阈值行为。 */
    fun isCapsuleAt(size: Size): Boolean =
        size.height > 0f && size.width >= size.height * threshold

    /** 实际采用的圆角半径。与 `FlueCapsuleGeometry.capsuleRadiusPx` 同一套夹取规则。 */
    fun radiusAt(size: Size): Float = com.flue.launcher.ui.style.FlueCapsuleGeometry
        .capsuleRadiusPx(
            rowHeightPx = size.height,
            widthPx = size.width,
            maxRadiusPx = maxRadiusPx,
        )

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = if (isCapsuleAt(size)) {
        Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radiusAt(size))))
    } else {
        fallback.createOutline(size, layoutDirection, density)
    }
}

/** 圆屏元素的轮廓角色。AUTO 保留旧的宽高比护栏。 */
enum class RoundElementRole {
    AUTO,
    CAPSULE,
    PANEL,
}

/** 显式圆屏轮廓策略：行状交互控件可强制胶囊，大型面板固定保留风格圆角。 */
internal data class RoundElementShape(
    private val fallback: Shape,
    private val role: RoundElementRole,
    private val maxRadiusPx: Float? = null,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = when (role) {
        RoundElementRole.CAPSULE -> if (size.height > 0f) {
            Outline.Rounded(
                RoundRect(
                    size.toRect(),
                    CornerRadius(
                        com.flue.launcher.ui.style.FlueCapsuleGeometry.capsuleRadiusPx(
                            rowHeightPx = size.height,
                            widthPx = size.width,
                            maxRadiusPx = maxRadiusPx,
                        ),
                    ),
                ),
            )
        } else {
            fallback.createOutline(size, layoutDirection, density)
        }
        RoundElementRole.PANEL -> fallback.createOutline(size, layoutDirection, density)
        RoundElementRole.AUTO -> RowCapsuleShape(fallback, maxRadiusPx = maxRadiusPx)
            .createOutline(size, layoutDirection, density)
    }
}

/**
 * 圆屏取胶囊、方屏保持原值的形状入口（[cardShape] 档）。
 *
 * 老师定的：圆屏下按钮/卡片一律胶囊，**三套风格统一**（三套的 [chipShape] 都已经是
 * `CircleShape`，天然统一，所以不用新加令牌，也不许新写 `RoundedCornerShape(数字)`
 * 字面量）。方屏继续走各风格自己的 [cardShape]，观感与改动前完全一致。
 *
 * 圆屏返回的是 [RowCapsuleShape]：行状元素取真胶囊，偏方/偏长的块自动退回
 * [cardShape]，调用点不用自己判断高度。
 */
@Composable
@ReadOnlyComposable
fun LauncherStyle.roundAwareCardShape(
    role: RoundElementRole = RoundElementRole.AUTO,
): Shape =
    if (com.flue.launcher.ui.common.isFlueScreenRound()) {
        RoundElementShape(
            fallback = cardShape,
            role = role,
            maxRadiusPx = capsuleMaxRadiusPx(),
        )
    } else {
        cardShape
    }

/** [roundAwareCardShape] 的 [compactShape] 档。 */
@Composable
@ReadOnlyComposable
fun LauncherStyle.roundAwareCompactShape(
    role: RoundElementRole = RoundElementRole.AUTO,
): Shape =
    if (com.flue.launcher.ui.common.isFlueScreenRound()) {
        RoundElementShape(
            fallback = compactShape,
            role = role,
            maxRadiusPx = capsuleMaxRadiusPx(),
        )
    } else {
        compactShape
    }

/**
 * [capsuleMaxRadius] 换算成像素。
 *
 * `Shape.createOutline` 拿到的是像素，而令牌是 `Dp`，换算必须在组合期做（那里有
 * `LocalDensity`）。`null` 原样透传 —— 它表示「不设上限」，不是 0。
 */
@Composable
@ReadOnlyComposable
private fun LauncherStyle.capsuleMaxRadiusPx(): Float? {
    val radius = capsuleMaxRadius ?: return null
    return with(androidx.compose.ui.platform.LocalDensity.current) { radius.toPx() }
}

fun styleAwareEnterTransition(style: LauncherStyle): EnterTransition = EnterTransition.None

fun styleAwareExitTransition(style: LauncherStyle): ExitTransition = ExitTransition.None
