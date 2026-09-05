package com.flue.launcher.ui.controlcenter

import androidx.annotation.StringRes
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import com.flue.launcher.R

object MusicTextSwitchAnimations {
    const val DEFAULT_ID = "fade_out_fade_in"

    val presets: List<MusicTextSwitchAnimationPreset> = listOf(
        preset("fade_out_fade_in", R.string.music_text_anim_fade_out_fade_in_label, R.string.music_text_anim_fade_out_fade_in_subtitle, fade(300), fade(300)),
        preset("fade_out_up_fade_in_up", R.string.music_text_anim_fade_out_up_fade_in_up_label, R.string.music_text_anim_fade_out_up_fade_in_up_subtitle, fadeUp(300), fadeUpIn(450)),
        preset("fade_out_down_fade_in_down", R.string.music_text_anim_fade_out_down_fade_in_down_label, R.string.music_text_anim_fade_out_down_fade_in_down_subtitle, fadeDown(300), fadeDownIn(450)),
        preset("fade_out_left_fade_in_right", R.string.music_text_anim_fade_out_left_fade_in_right_label, R.string.music_text_anim_fade_out_left_fade_in_right_subtitle, fadeLeft(350), fadeRightIn(450)),
        preset("fade_out_left_fade_in_up", R.string.music_text_anim_fade_out_left_fade_in_up_label, R.string.music_text_anim_fade_out_left_fade_in_up_subtitle, fadeLeft(300), fadeUpIn(450)),
        preset("fade_out_left_zoom_in", R.string.music_text_anim_fade_out_left_zoom_in_label, R.string.music_text_anim_fade_out_left_zoom_in_subtitle, fadeLeft(300), zoomIn(400)),
        preset("fade_out_left_landing", R.string.music_text_anim_fade_out_left_landing_label, R.string.music_text_anim_fade_out_left_landing_subtitle, fadeLeft(300), landing(700)),
        preset("fade_out_right_fade_in_left", R.string.music_text_anim_fade_out_right_fade_in_left_label, R.string.music_text_anim_fade_out_right_fade_in_left_subtitle, fadeRight(300), fadeLeftIn(450)),
        preset("fade_out_right_fade_in_up", R.string.music_text_anim_fade_out_right_fade_in_up_label, R.string.music_text_anim_fade_out_right_fade_in_up_subtitle, fadeRight(300), fadeUpIn(450)),
        preset("fade_out_right_zoom_in", R.string.music_text_anim_fade_out_right_zoom_in_label, R.string.music_text_anim_fade_out_right_zoom_in_subtitle, fadeRight(200), zoomIn(400)),
        preset("fade_out_right_landing", R.string.music_text_anim_fade_out_right_landing_label, R.string.music_text_anim_fade_out_right_landing_subtitle, fadeRight(300), landing(700)),
        preset("slide_out_left_slide_in_right", R.string.music_text_anim_slide_out_left_slide_in_right_label, R.string.music_text_anim_slide_out_left_slide_in_right_subtitle, slideLeft(300), slideRightIn(450)),
        preset("slide_out_left_fade_in_up", R.string.music_text_anim_slide_out_left_fade_in_up_label, R.string.music_text_anim_slide_out_left_fade_in_up_subtitle, slideLeft(300), fadeUpIn(450)),
        preset("slide_out_left_zoom_in", R.string.music_text_anim_slide_out_left_zoom_in_label, R.string.music_text_anim_slide_out_left_zoom_in_subtitle, slideLeft(300), zoomIn(450)),
        preset("slide_out_left_landing", R.string.music_text_anim_slide_out_left_landing_label, R.string.music_text_anim_slide_out_left_landing_subtitle, slideLeft(300), landing(700)),
        preset("slide_out_right_slide_in_left", R.string.music_text_anim_slide_out_right_slide_in_left_label, R.string.music_text_anim_slide_out_right_slide_in_left_subtitle, slideRight(300), slideLeftIn(450)),
        preset("slide_out_right_fade_in_up", R.string.music_text_anim_slide_out_right_fade_in_up_label, R.string.music_text_anim_slide_out_right_fade_in_up_subtitle, slideRight(300), fadeUpIn(450)),
        preset("slide_out_right_zoom_in", R.string.music_text_anim_slide_out_right_zoom_in_label, R.string.music_text_anim_slide_out_right_zoom_in_subtitle, slideRight(300), zoomIn(450)),
        preset("slide_out_right_landing", R.string.music_text_anim_slide_out_right_landing_label, R.string.music_text_anim_slide_out_right_landing_subtitle, slideRight(300), landing(700)),
        preset("flip_out_x_flip_in_x", R.string.music_text_anim_flip_out_x_flip_in_x_label, R.string.music_text_anim_flip_out_x_flip_in_x_subtitle, flipX(300), flipXIn(450)),
        preset("flip_out_y_flip_in_y", R.string.music_text_anim_flip_out_y_flip_in_y_label, R.string.music_text_anim_flip_out_y_flip_in_y_subtitle, flipY(300), flipYIn(450)),
        preset("rotate_out_rotate_in", R.string.music_text_anim_rotate_out_rotate_in_label, R.string.music_text_anim_rotate_out_rotate_in_subtitle, rotate(200), rotateIn(600)),
        preset("zoom_out_zoom_in", R.string.music_text_anim_zoom_out_zoom_in_label, R.string.music_text_anim_zoom_out_zoom_in_subtitle, zoomOut(200), zoomIn(400)),
        preset("fade_out_left_zoom_in_right", R.string.music_text_anim_fade_out_left_zoom_in_right_label, R.string.music_text_anim_fade_out_left_zoom_in_right_subtitle, fadeLeft(250), zoomRightIn(600)),
        preset("fade_out_right_zoom_in_left", R.string.music_text_anim_fade_out_right_zoom_in_left_label, R.string.music_text_anim_fade_out_right_zoom_in_left_subtitle, fadeRight(250), zoomLeftIn(600))
    )

    fun byId(id: String?): MusicTextSwitchAnimationPreset =
        presets.firstOrNull { it.id == id } ?: presets.first { it.id == DEFAULT_ID }

    fun normalizeId(id: String?): String = byId(id).id
}

data class MusicTextSwitchAnimationPreset(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val subtitleRes: Int,
    val outMotion: MusicTextSwitchMotion,
    val inMotion: MusicTextSwitchMotion
)

data class MusicTextSwitchMotion(
    val kind: MusicTextSwitchMotionKind,
    val durationMs: Int,
    val easing: Easing = FastOutSlowInEasing
)

enum class MusicTextSwitchPhase {
    Idle,
    Out,
    In
}

enum class MusicTextSwitchMotionKind {
    Fade,
    FadeLeft,
    FadeRight,
    FadeUp,
    FadeDown,
    SlideLeft,
    SlideRight,
    ZoomOut,
    ZoomIn,
    ZoomRightIn,
    ZoomLeftIn,
    Landing,
    FlipX,
    FlipXIn,
    FlipY,
    FlipYIn,
    Rotate,
    RotateIn
}

data class MusicTextSwitchTransform(
    val alpha: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scale: Float = 1f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f
)

fun MusicTextSwitchAnimationPreset.transform(
    phase: MusicTextSwitchPhase,
    progress: Float,
    distancePx: Float
): MusicTextSwitchTransform {
    return when (phase) {
        MusicTextSwitchPhase.Idle -> MusicTextSwitchTransform()
        MusicTextSwitchPhase.Out -> outMotion.transform(progress, distancePx, entering = false)
        MusicTextSwitchPhase.In -> inMotion.transform(progress, distancePx, entering = true)
    }
}

private fun preset(
    id: String,
    @StringRes labelRes: Int,
    @StringRes subtitleRes: Int,
    outMotion: MusicTextSwitchMotion,
    inMotion: MusicTextSwitchMotion
): MusicTextSwitchAnimationPreset {
    return MusicTextSwitchAnimationPreset(id, labelRes, subtitleRes, outMotion, inMotion)
}

private fun fade(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.Fade, durationMs)
private fun fadeLeft(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FadeLeft, durationMs)
private fun fadeRight(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FadeRight, durationMs)
private fun fadeUp(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FadeUp, durationMs)
private fun fadeDown(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FadeDown, durationMs)
private fun slideLeft(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.SlideLeft, durationMs)
private fun slideRight(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.SlideRight, durationMs)
private fun zoomOut(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.ZoomOut, durationMs)
private fun rotate(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.Rotate, durationMs)
private fun flipX(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FlipX, durationMs)
private fun flipY(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FlipY, durationMs)
private fun fadeRightIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FadeRight, durationMs)
private fun fadeLeftIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FadeLeft, durationMs)
private fun fadeUpIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FadeUp, durationMs)
private fun fadeDownIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FadeDown, durationMs)
private fun slideRightIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.SlideRight, durationMs)
private fun slideLeftIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.SlideLeft, durationMs)
private fun zoomIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.ZoomIn, durationMs)
private fun zoomRightIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.ZoomRightIn, durationMs)
private fun zoomLeftIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.ZoomLeftIn, durationMs)
private fun landing(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.Landing, durationMs)
private fun rotateIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.RotateIn, durationMs)
private fun flipXIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FlipXIn, durationMs)
private fun flipYIn(durationMs: Int) = MusicTextSwitchMotion(MusicTextSwitchMotionKind.FlipYIn, durationMs)

private fun MusicTextSwitchMotion.transform(
    progress: Float,
    distancePx: Float,
    entering: Boolean
): MusicTextSwitchTransform {
    val p = progress.coerceIn(0f, 1f)
    val alpha = if (entering) p else 1f - p
    fun offset(sign: Float): Float = if (entering) sign * distancePx * (1f - p) else sign * distancePx * p
    return when (kind) {
        MusicTextSwitchMotionKind.Fade -> MusicTextSwitchTransform(alpha = alpha)
        MusicTextSwitchMotionKind.FadeLeft -> MusicTextSwitchTransform(alpha = alpha, translationX = offset(-1f))
        MusicTextSwitchMotionKind.FadeRight -> MusicTextSwitchTransform(alpha = alpha, translationX = offset(1f))
        MusicTextSwitchMotionKind.FadeUp -> MusicTextSwitchTransform(alpha = alpha, translationY = offset(if (entering) 1f else -1f))
        MusicTextSwitchMotionKind.FadeDown -> MusicTextSwitchTransform(alpha = alpha, translationY = offset(if (entering) -1f else 1f))
        MusicTextSwitchMotionKind.SlideLeft -> MusicTextSwitchTransform(alpha = 1f, translationX = offset(-1.35f))
        MusicTextSwitchMotionKind.SlideRight -> MusicTextSwitchTransform(alpha = 1f, translationX = offset(1.35f))
        MusicTextSwitchMotionKind.ZoomOut -> MusicTextSwitchTransform(alpha = alpha, scale = 1f - 0.18f * p)
        MusicTextSwitchMotionKind.ZoomIn -> MusicTextSwitchTransform(alpha = alpha, scale = 0.82f + 0.18f * p)
        MusicTextSwitchMotionKind.ZoomRightIn -> MusicTextSwitchTransform(alpha = alpha, translationX = distancePx * (1f - p), scale = 0.82f + 0.18f * p)
        MusicTextSwitchMotionKind.ZoomLeftIn -> MusicTextSwitchTransform(alpha = alpha, translationX = -distancePx * (1f - p), scale = 0.82f + 0.18f * p)
        MusicTextSwitchMotionKind.Landing -> MusicTextSwitchTransform(
            alpha = alpha,
            translationY = -distancePx * 0.65f * (1f - p),
            scale = 0.92f + 0.08f * p
        )
        MusicTextSwitchMotionKind.FlipX -> MusicTextSwitchTransform(alpha = alpha, rotationX = 84f * p)
        MusicTextSwitchMotionKind.FlipXIn -> MusicTextSwitchTransform(alpha = alpha, rotationX = -84f * (1f - p))
        MusicTextSwitchMotionKind.FlipY -> MusicTextSwitchTransform(alpha = alpha, rotationY = 84f * p)
        MusicTextSwitchMotionKind.FlipYIn -> MusicTextSwitchTransform(alpha = alpha, rotationY = -84f * (1f - p))
        MusicTextSwitchMotionKind.Rotate -> MusicTextSwitchTransform(alpha = alpha, rotationZ = -24f * p, scale = 1f - 0.12f * p)
        MusicTextSwitchMotionKind.RotateIn -> MusicTextSwitchTransform(alpha = alpha, rotationZ = 24f * (1f - p), scale = 0.88f + 0.12f * p)
    }
}
