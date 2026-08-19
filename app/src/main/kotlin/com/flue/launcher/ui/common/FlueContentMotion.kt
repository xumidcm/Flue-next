package com.flue.launcher.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize

/**
 * 「内容变高变矮」的统一动效。
 *
 * ## 老师报的问题
 *
 * 一个选项的长度发生变化（开关展开出附属卡、副标题换成两行、滑杆下面冒出按钮）时，
 * 下面的元素**直接闪现上来贴合**。要的是 miuix 那种丝滑的向下避让，而且**每种风格、
 * 每个页面**都要有。
 *
 * ## 为什么需要两条 spec 而不是一条
 *
 * 它们管的是两件不同的事，缺任何一条都只有一半效果：
 *
 * - [flueContentSizeSpring] 给 `Modifier.animateContentSize()` —— 管**自己**从 48dp 长到
 *   96dp 的过程；
 * - [fluePlacementSpring] 给 `Modifier.animateItem(placementSpec = …)` —— 管**下面那些行**
 *   跟着往下挪的过程。只有 `animateContentSize` 时，长高的那一格是平滑的，它下面的行
 *   仍然是瞬移；只有 `animateItem` 时，行与行的位移平滑了，但长高那一格自己是跳变。
 *
 * ## 参数取值
 *
 * 阻尼 0.9 / 刚度 500：接近临界阻尼，**不回弹** —— 尺寸变化回弹会读成「抖了一下」，
 * 与位移回弹的观感不是一回事。刚度沿用通知中心 `animateItem` 那一档（500f/0.86），
 * 让「行自己长高」与「行被挤下去」跑在同一个时间尺度上，不会一快一慢。
 *
 * `visibilityThreshold = IntSize(1, 1)`：尺寸是整数像素，默认阈值会在最后 1px 上
 * 磨很久 —— 那 1px 的收敛肉眼看不见，却让动画迟迟不结束、行一直处于「正在动画」态。
 *
 * ## 为什么不挂到某个 FlueMotionProfile 相位上
 *
 * `FlueMotionProfile` 的五个相位（appListEnter / appLaunch / appReturn / pullToFace /
 * edgeBounce）描述的是**转场**，用户在「动效风格」里换预设时改的是那些。设置行长高
 * 不是转场，借用任一相位都会让「换一个转场预设」意外改掉列表的避让节奏。
 */
val flueContentSizeSpring: FiniteAnimationSpec<IntSize> = spring(
    dampingRatio = 0.9f,
    stiffness = 500f,
    visibilityThreshold = IntSize(1, 1),
)

/** 见 [flueContentSizeSpring]：列表里「下面的行跟着挪」用这一条。 */
val fluePlacementSpring: FiniteAnimationSpec<androidx.compose.ui.unit.IntOffset> = spring(
    dampingRatio = 0.9f,
    stiffness = 500f,
    visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1),
)

/** 列表项淡入。比位移略快，让新行「先出现再稳定」而不是一起慢慢浮现。 */
val FLUE_ITEM_FADE_IN = tween<Float>(durationMillis = 190)

/** 列表项淡出。删除比新增快一档：留恋感只会让列表显得迟钝。 */
val FLUE_ITEM_FADE_OUT = tween<Float>(durationMillis = 130)

/**
 * 条件出现的卡片（开关打开后才出现的那一块）的进入动效。
 *
 * 与 [flueContentSizeSpring] 同一套参数，所以「卡片自己展开」和「下面的行被挤下去」
 * 是同一条曲线 —— 两者用不同 spec 时会看出错位。
 */
fun flueExpandEnter(): EnterTransition =
    expandVertically(
        // expandVertically 动的是 IntSize，不是标量 —— 阈值也必须给 IntSize，
        // 给 Int 编译期就过不去（曾经在这里写成 Int）。
        animationSpec = spring(
            dampingRatio = 0.9f,
            stiffness = 500f,
            visibilityThreshold = IntSize(1, 1),
        ),
        expandFrom = Alignment.Top,
        clip = true,
    ) + fadeIn(animationSpec = FLUE_ITEM_FADE_IN)

/** 见 [flueExpandEnter]。收起比展开快一档，与列表项淡出同一个理由。 */
fun flueShrinkExit(): ExitTransition =
    shrinkVertically(
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = 620f,
            visibilityThreshold = IntSize(1, 1),
        ),
        shrinkTowards = Alignment.Top,
        clip = true,
    ) + fadeOut(animationSpec = FLUE_ITEM_FADE_OUT)

/** 供 `AnimatedVisibility` 的 content lambda 使用，避免调用点各写一遍未使用的作用域参数。 */
typealias FlueVisibilityScope = AnimatedVisibilityScope
