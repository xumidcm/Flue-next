package com.flue.launcher.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import com.flue.launcher.ui.theme.GlassSurfaceTokens

/**
 * 玻璃表面（控制中心 / 负一屏 / 通知中心共用的材质）。
 *
 * 三层顺序不能换：先 [clip] 定形，再铺 [GlassSurfaceTokens.fill] 半透明填充，
 * 最后压 [GlassSurfaceTokens.highlight] 描边。描边必须在填充之后 —— 反了会被
 * 填充盖掉一半，转角处就回到发虚的样子。
 *
 * [shape] 由调用方给，不从令牌里取：三套风格的形状语言各自独立
 * （MIUIX 大圆角 / MD3 Expressive / AW 26dp），这里只统一质感。
 */
fun Modifier.glassSurface(
    shape: Shape,
    tokens: GlassSurfaceTokens,
): Modifier = this
    .clip(shape)
    .background(tokens.fill)
    .border(tokens.borderWidth, tokens.highlight, shape)
