package com.flue.launcher.watchface.dingdingcat

import java.io.File

const val DINGDINGCAT_ID_PREFIX = "dingdingcat:"

data class DingDingCatDial(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val rootDir: File,
    val drawPoints: List<DingDingCatDrawPoint>
)

data class DingDingCatDrawPoint(
    val pointType: String,
    val pieces: List<String>,
    val startX: Float,
    val startY: Float,
    val width: Float,
    val height: Float,
    val scale: Float,
    val splitWidth: Float,
    val splitSpace: Float,
    val alpha: Int,
    val tint: Int,
    val color: Int,
    val gravity: Int,
    val text: String,
    val drawOrder: Int,
    val durationMs: Int,
    val visible: Boolean,
    val font: DingDingCatFont
)

data class DingDingCatFont(
    val color: Int,
    val textSize: Float,
    val fontType: String?
)
