package com.flue.launcher.ui.settings.miuix

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * MIUIX（HyperOS 视觉语言）风格线性图标集。
 *
 * 视觉规范：24x24 视口、1.5dp 均匀线宽、圆角端点 / 圆角折角、简洁几何。
 * 全部为纯 Compose [ImageVector] 资产，不依赖任何三方图标库；
 * 颜色固定为黑色，实际显示颜色由 `Icon(tint = ...)` 决定。
 *
 * 包含图标：
 * [Back]、[Forward]、[Check]、[Close]、[Add]、[Remove]、[Search]、
 * [Settings]、[Info]、[Warning]、[Refresh]、[Delete]、[Wifi]、[Bluetooth]、
 * [Battery]、[Notification]、[Brightness]、[Clock]、[Grid]、[MobileData]、
 * [DoNotDisturb]、[SkipPrevious]、[SkipNext]、[ArrowUp]、[ArrowDown]。
 */
object MiuixIcons {

    /** 返回（左细尖角箭头）。 */
    val Back: ImageVector by lazyIcon("Back") {
        stroke {
            moveTo(14.5f, 5.5f)
            lineTo(8f, 12f)
            lineTo(14.5f, 18.5f)
        }
    }

    /** 前进 / 列表行右侧跳转箭头。 */
    val Forward: ImageVector by lazyIcon("Forward") {
        stroke {
            moveTo(9.5f, 5.5f)
            lineTo(16f, 12f)
            lineTo(9.5f, 18.5f)
        }
    }

    /** 明确的上移箭头；带竖杆，避免和折叠/展开图标混淆。 */
    val ArrowUp: ImageVector by lazyIcon("ArrowUp") {
        stroke {
            moveTo(12f, 19f)
            verticalLineTo(5f)
        }
        stroke {
            moveTo(6.6f, 10.4f)
            lineTo(12f, 5f)
            lineTo(17.4f, 10.4f)
        }
    }

    /** 明确的下移箭头；带竖杆，避免和折叠/展开图标混淆。 */
    val ArrowDown: ImageVector by lazyIcon("ArrowDown") {
        stroke {
            moveTo(12f, 5f)
            verticalLineTo(19f)
        }
        stroke {
            moveTo(6.6f, 13.6f)
            lineTo(12f, 19f)
            lineTo(17.4f, 13.6f)
        }
    }

    /** 上一首：左侧定位杆 + 向左播放三角。 */
    val SkipPrevious: ImageVector by lazyIcon("SkipPrevious") {
        stroke {
            moveTo(6.5f, 6f)
            verticalLineTo(18f)
        }
        stroke {
            moveTo(17.5f, 6.5f)
            lineTo(9.5f, 12f)
            lineTo(17.5f, 17.5f)
            close()
        }
    }

    /** 下一首：右侧定位杆 + 向右播放三角。 */
    val SkipNext: ImageVector by lazyIcon("SkipNext") {
        stroke {
            moveTo(17.5f, 6f)
            verticalLineTo(18f)
        }
        stroke {
            moveTo(6.5f, 6.5f)
            lineTo(14.5f, 12f)
            lineTo(6.5f, 17.5f)
            close()
        }
    }

    /** 播放：向右的实心播放三角（与 SkipNext 的三角同一口径，去掉定位杆）。 */
    val Play: ImageVector by lazyIcon("Play") {
        stroke {
            moveTo(8f, 5.5f)
            lineTo(18f, 12f)
            lineTo(8f, 18.5f)
            close()
        }
    }

    /** 暂停：左右两根等宽竖杆。 */
    val Pause: ImageVector by lazyIcon("Pause") {
        stroke {
            moveTo(9f, 5.5f)
            verticalLineTo(18.5f)
        }
        stroke {
            moveTo(15f, 5.5f)
            verticalLineTo(18.5f)
        }
    }

    /** 移动数据：上下行双箭头。 */
    val MobileData: ImageVector by lazyIcon("MobileData") {
        stroke {
            moveTo(8.5f, 18.5f)
            verticalLineTo(6f)
        }
        stroke {
            moveTo(5.2f, 9.3f)
            lineTo(8.5f, 6f)
            lineTo(11.8f, 9.3f)
        }
        stroke {
            moveTo(15.5f, 5.5f)
            verticalLineTo(18f)
        }
        stroke {
            moveTo(12.2f, 14.7f)
            lineTo(15.5f, 18f)
            lineTo(18.8f, 14.7f)
        }
    }

    /** 勿扰：圆形状态标记内的减号。 */
    val DoNotDisturb: ImageVector by lazyIcon("DoNotDisturb") {
        stroke { circle(12f, 12f, 8.2f) }
        stroke {
            moveTo(7.6f, 12f)
            horizontalLineTo(16.4f)
        }
    }

    /** 勾选。 */
    val Check: ImageVector by lazyIcon("Check") {
        stroke {
            moveTo(5.2f, 12.6f)
            lineTo(10.1f, 17.4f)
            lineTo(18.8f, 7.2f)
        }
    }

    /** 关闭 / 取消。 */
    val Close: ImageVector by lazyIcon("Close") {
        stroke {
            moveTo(6.4f, 6.4f)
            lineTo(17.6f, 17.6f)
        }
        stroke {
            moveTo(17.6f, 6.4f)
            lineTo(6.4f, 17.6f)
        }
    }

    /** 新增。 */
    val Add: ImageVector by lazyIcon("Add") {
        stroke {
            moveTo(12f, 5.2f)
            verticalLineTo(18.8f)
        }
        stroke {
            moveTo(5.2f, 12f)
            horizontalLineTo(18.8f)
        }
    }

    /** 移除。 */
    val Remove: ImageVector by lazyIcon("Remove") {
        stroke {
            moveTo(5.2f, 12f)
            horizontalLineTo(18.8f)
        }
    }

    /** 搜索。 */
    val Search: ImageVector by lazyIcon("Search") {
        stroke { circle(11.2f, 11.2f, 5.7f) }
        stroke {
            moveTo(15.5f, 15.5f)
            lineTo(19.2f, 19.2f)
        }
    }

    /** 设置（八齿齿轮）。 */
    val Settings: ImageVector by lazyIcon("Settings") {
        stroke {
            moveTo(9.7f, 5.33f)
            lineTo(10.42f, 3.04f)
            lineTo(13.58f, 3.04f)
            lineTo(14.3f, 5.33f)
            lineTo(15.09f, 5.66f)
            lineTo(17.22f, 4.55f)
            lineTo(19.45f, 6.78f)
            lineTo(18.34f, 8.91f)
            lineTo(18.67f, 9.7f)
            lineTo(20.96f, 10.42f)
            lineTo(20.96f, 13.58f)
            lineTo(18.67f, 14.3f)
            lineTo(18.34f, 15.09f)
            lineTo(19.45f, 17.22f)
            lineTo(17.22f, 19.45f)
            lineTo(15.09f, 18.34f)
            lineTo(14.3f, 18.67f)
            lineTo(13.58f, 20.96f)
            lineTo(10.42f, 20.96f)
            lineTo(9.7f, 18.67f)
            lineTo(8.91f, 18.34f)
            lineTo(6.78f, 19.45f)
            lineTo(4.55f, 17.22f)
            lineTo(5.66f, 15.09f)
            lineTo(5.33f, 14.3f)
            lineTo(3.04f, 13.58f)
            lineTo(3.04f, 10.42f)
            lineTo(5.33f, 9.7f)
            lineTo(5.66f, 8.91f)
            lineTo(4.55f, 6.78f)
            lineTo(6.78f, 4.55f)
            lineTo(8.91f, 5.66f)
            close()
        }
        stroke { circle(12f, 12f, 3.3f) }
    }

    /** 信息。 */
    val Info: ImageVector by lazyIcon("Info") {
        stroke { circle(12f, 12f, 8.3f) }
        stroke {
            moveTo(12f, 11.2f)
            verticalLineTo(16.2f)
        }
        dot(12f, 8.3f)
    }

    /** 警告。 */
    val Warning: ImageVector by lazyIcon("Warning") {
        stroke {
            moveTo(12f, 4.6f)
            lineTo(20.4f, 19f)
            horizontalLineTo(3.6f)
            close()
        }
        stroke {
            moveTo(12f, 10.2f)
            verticalLineTo(14.4f)
        }
        dot(12f, 16.9f)
    }

    /** 刷新（顺时针环形箭头）。 */
    val Refresh: ImageVector by lazyIcon("Refresh") {
        stroke {
            moveTo(19.4f, 12f)
            arcTo(7.4f, 7.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 4.6f)
        }
        stroke {
            moveTo(9.9f, 2.7f)
            lineTo(12.3f, 4.6f)
            lineTo(9.9f, 6.5f)
        }
    }

    /** 删除（垃圾桶）。 */
    val Delete: ImageVector by lazyIcon("Delete") {
        stroke {
            moveTo(4.9f, 7.1f)
            horizontalLineTo(19.1f)
        }
        stroke {
            moveTo(9.7f, 7.1f)
            verticalLineTo(5.9f)
            curveTo(9.7f, 5.2f, 10.2f, 4.7f, 10.9f, 4.7f)
            horizontalLineTo(13.1f)
            curveTo(13.8f, 4.7f, 14.3f, 5.2f, 14.3f, 5.9f)
            verticalLineTo(7.1f)
        }
        stroke {
            moveTo(6.6f, 7.1f)
            lineTo(7.3f, 18.4f)
            curveTo(7.4f, 19.6f, 8.4f, 20.5f, 9.6f, 20.5f)
            horizontalLineTo(14.4f)
            curveTo(15.6f, 20.5f, 16.6f, 19.6f, 16.7f, 18.4f)
            lineTo(17.4f, 7.1f)
        }
        stroke {
            moveTo(10.1f, 10.6f)
            lineTo(10.35f, 16.9f)
        }
        stroke {
            moveTo(13.9f, 10.6f)
            lineTo(13.65f, 16.9f)
        }
    }

    /** WLAN。 */
    val Wifi: ImageVector by lazyIcon("Wifi") {
        stroke {
            moveTo(3.9f, 10.7f)
            arcTo(11.45f, 11.45f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20.1f, y1 = 10.7f)
        }
        stroke {
            moveTo(6.7f, 13.7f)
            arcTo(7.5f, 7.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17.3f, y1 = 13.7f)
        }
        stroke {
            moveTo(9.45f, 16.65f)
            arcTo(3.6f, 3.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 14.55f, y1 = 16.65f)
        }
        dot(12f, 19.65f, 1.05f)
    }

    /** 退出画布全屏：四角向中心收拢的 MIUIX 细线缩小图标。 */
    val Minimize: ImageVector by lazyIcon("Minimize") {
        stroke {
            moveTo(4.5f, 9.2f)
            horizontalLineTo(9.2f)
            verticalLineTo(4.5f)
        }
        stroke {
            moveTo(19.5f, 9.2f)
            horizontalLineTo(14.8f)
            verticalLineTo(4.5f)
        }
        stroke {
            moveTo(4.5f, 14.8f)
            horizontalLineTo(9.2f)
            verticalLineTo(19.5f)
        }
        stroke {
            moveTo(19.5f, 14.8f)
            horizontalLineTo(14.8f)
            verticalLineTo(19.5f)
        }
    }

    /** 进入全屏：四角向外展开的 MIUIX 细线图标。 */
    val Maximize: ImageVector by lazyIcon("Maximize") {
        stroke {
            moveTo(9.2f, 4.5f)
            horizontalLineTo(4.5f)
            verticalLineTo(9.2f)
        }
        stroke {
            moveTo(14.8f, 4.5f)
            horizontalLineTo(19.5f)
            verticalLineTo(9.2f)
        }
        stroke {
            moveTo(9.2f, 19.5f)
            horizontalLineTo(4.5f)
            verticalLineTo(14.8f)
        }
        stroke {
            moveTo(14.8f, 19.5f)
            horizontalLineTo(19.5f)
            verticalLineTo(14.8f)
        }
    }

    /** 蓝牙。 */
    val Bluetooth: ImageVector by lazyIcon("Bluetooth") {
        stroke {
            moveTo(6.7f, 7.1f)
            lineTo(17.3f, 16.9f)
            lineTo(12.05f, 21.2f)
            verticalLineTo(2.8f)
            lineTo(17.3f, 7.1f)
            lineTo(6.7f, 16.9f)
        }
    }

    /** 电池（横向，含电量段）。 */
    val Battery: ImageVector by lazyIcon("Battery") {
        stroke { roundedRect(3.4f, 8.4f, 15.2f, 7.2f, 2.2f) }
        stroke {
            moveTo(20.8f, 10.9f)
            verticalLineTo(13.1f)
        }
        stroke {
            moveTo(6.6f, 12f)
            horizontalLineTo(10.2f)
        }
    }

    /** 通知（铃铛）。 */
    val Notification: ImageVector by lazyIcon("Notification") {
        // 重画过两次。
        //
        // 第一版钟体 5.6..17.3（高 11.7）、宽 10.6 —— 老师说「太扁」。
        // 第二版把高度拉到 14.2、宽度仍是 11.2，于是**比旁边两个图标窄了一圈**：
        // 市场顶栏里铃铛、宫格、三点是并排的，宫格那种方形图标在 24 视口里横跨约 15 单位，
        // 而 11.2 宽的铃铛在同一个 20dp 框里看着就是「小一号」。老师这次说的
        // 「还是太扁，应该和旁边两个图标等宽」指的就是这件事。
        //
        // 现在整个剪影 x ∈ [4.8, 19.2]（宽 14.4）、y ∈ [3.9, 19.9]（高 16.0），
        // 与方形图标横向齐平，纵向只高出一点点 —— 圆钝形状比正方形略大一点才视觉等重，
        // 这是图标设计里的常规光学补偿。
        //
        // 钟体本身仍然是「高 > 宽」的正常钟形（12.1 高 vs 14.4 宽的罩子 + 顶钮 + 摆锤
        // 一起把纵向撑起来），不会回到第一版那种矮墩墩的观感。
        stroke {
            moveTo(4.8f, 17.6f)
            curveTo(6.5f, 15.8f, 7.2f, 14.2f, 7.2f, 12.1f)
            verticalLineTo(9.8f)
            curveTo(7.2f, 6.9f, 9.2f, 5.5f, 12f, 5.5f)
            curveTo(14.8f, 5.5f, 16.8f, 6.9f, 16.8f, 9.8f)
            verticalLineTo(12.1f)
            curveTo(16.8f, 14.2f, 17.5f, 15.8f, 19.2f, 17.6f)
            close()
        }
        // 顶钮：短竖线而不是小圆，20dp 显示尺寸下圆点会糊成一团。
        stroke {
            moveTo(12f, 5.5f)
            verticalLineTo(3.9f)
        }
        // 摆锤。与底沿留 1 单位空隙，不然会和钟口的横线并成一条。
        stroke {
            moveTo(10.3f, 18.6f)
            arcTo(1.75f, 1.75f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 13.7f, y1 = 18.6f)
        }
    }

    /** 亮度 / 显示（太阳）。 */
    val Brightness: ImageVector by lazyIcon("Brightness") {
        stroke { circle(12f, 12f, 3.5f) }
        stroke { moveTo(18.1f, 12f); horizontalLineTo(20.6f) }
        stroke { moveTo(16.31f, 16.31f); lineTo(18.08f, 18.08f) }
        stroke { moveTo(12f, 18.1f); verticalLineTo(20.6f) }
        stroke { moveTo(7.69f, 16.31f); lineTo(5.92f, 18.08f) }
        stroke { moveTo(5.9f, 12f); horizontalLineTo(3.4f) }
        stroke { moveTo(7.69f, 7.69f); lineTo(5.92f, 5.92f) }
        stroke { moveTo(12f, 5.9f); verticalLineTo(3.4f) }
        stroke { moveTo(16.31f, 7.69f); lineTo(18.08f, 5.92f) }
    }

    /** 时钟 / 表盘。 */
    val Clock: ImageVector by lazyIcon("Clock") {
        stroke { circle(12f, 12f, 8.25f) }
        stroke {
            moveTo(12f, 7.4f)
            verticalLineTo(12.3f)
            lineTo(15.2f, 14.15f)
        }
    }

    /** 应用网格。 */
    val Grid: ImageVector by lazyIcon("Grid") {
        stroke { roundedRect(4.3f, 4.3f, 6.4f, 6.4f, 2f) }
        stroke { roundedRect(13.3f, 4.3f, 6.4f, 6.4f, 2f) }
        stroke { roundedRect(4.3f, 13.3f, 6.4f, 6.4f, 2f) }
        stroke { roundedRect(13.3f, 13.3f, 6.4f, 6.4f, 2f) }
    }

    /** 铅笔 / 编辑。 */
    val Edit: ImageVector by lazyIcon("Edit") {
        stroke { moveTo(5f, 19f); lineTo(8.8f, 18.2f); lineTo(18.6f, 8.4f); lineTo(15.6f, 5.4f); lineTo(5.8f, 15.2f); close() }
        stroke { moveTo(13.9f, 7.1f); lineTo(16.9f, 10.1f) }
    }

    /** 回形针 / 附件。 */
    val Attachment: ImageVector by lazyIcon("Attachment") {
        stroke { moveTo(8.2f, 12.9f); lineTo(14.8f, 6.3f); curveTo(16.4f, 4.7f, 19f, 4.7f, 20.5f, 6.2f); curveTo(22f, 7.7f, 22f, 10.3f, 20.4f, 11.9f); lineTo(11.1f, 21.2f); curveTo(8.7f, 23.6f, 4.7f, 23.6f, 2.3f, 21.2f); curveTo(-0.1f, 18.8f, -0.1f, 14.8f, 2.3f, 12.4f); lineTo(11.1f, 3.6f) }
    }

    /** 复制。 */
    val Copy: ImageVector by lazyIcon("Copy") {
        stroke { roundedRect(8f, 8f, 11f, 11f, 2.2f) }
        stroke { moveTo(16f, 8f); verticalLineTo(6.5f); curveTo(16f, 5.1f, 14.9f, 4f, 13.5f, 4f); horizontalLineTo(6.5f); curveTo(5.1f, 4f, 4f, 5.1f, 4f, 6.5f); verticalLineTo(13.5f); curveTo(4f, 14.9f, 5.1f, 16f, 6.5f, 16f); horizontalLineTo(8f) }
    }

    /** 重试。 */
    val Retry: ImageVector by lazyIcon("Retry") {
        stroke { moveTo(7.2f, 7.2f); horizontalLineTo(3.8f); verticalLineTo(3.8f) }
        stroke { moveTo(4.2f, 7.1f); curveTo(6.8f, 3.6f, 11.5f, 2.5f, 15.3f, 4.5f); curveTo(19.1f, 6.4f, 20.9f, 10.9f, 19.6f, 15f); curveTo(18.3f, 19.1f, 14.3f, 21.6f, 10f, 20.8f); curveTo(7.4f, 20.3f, 5.2f, 18.6f, 4f, 16.3f) }
    }

    /** 发送。 */
    val Send: ImageVector by lazyIcon("Send") {
        stroke { moveTo(3.2f, 4.2f); lineTo(21f, 12f); lineTo(3.2f, 19.8f); lineTo(6.1f, 12f); close() }
        stroke { moveTo(6.1f, 12f); horizontalLineTo(14.5f) }
    }

    /** 停止。 */
    val Stop: ImageVector by lazyIcon("Stop") { stroke { roundedRect(6f, 6f, 12f, 12f, 2.4f) } }

    /** 显示 / 隐藏内容。 */
    val Visibility: ImageVector by lazyIcon("Visibility") {
        stroke { moveTo(2.7f, 12f); curveTo(5.1f, 7.8f, 8.2f, 5.7f, 12f, 5.7f); curveTo(15.8f, 5.7f, 18.9f, 7.8f, 21.3f, 12f); curveTo(18.9f, 16.2f, 15.8f, 18.3f, 12f, 18.3f); curveTo(8.2f, 18.3f, 5.1f, 16.2f, 2.7f, 12f); close() }
        stroke { circle(12f, 12f, 2.7f) }
    }

    /** 更多操作。 */
    val More: ImageVector by lazyIcon("More") { dot(5f, 12f); dot(12f, 12f); dot(19f, 12f) }

    /** AI / 四角星。 */
    val Sparkle: ImageVector by lazyIcon("Sparkle") {
        stroke {
            moveTo(12f, 2.8f)
            curveTo(12.9f, 8.6f, 15.4f, 11.1f, 21.2f, 12f)
            curveTo(15.4f, 12.9f, 12.9f, 15.4f, 12f, 21.2f)
            curveTo(11.1f, 15.4f, 8.6f, 12.9f, 2.8f, 12f)
            curveTo(8.6f, 11.1f, 11.1f, 8.6f, 12f, 2.8f)
            close()
        }
        stroke {
            moveTo(18.3f, 3.7f)
            curveTo(18.6f, 5.4f, 19.3f, 6.1f, 21f, 6.4f)
            curveTo(19.3f, 6.7f, 18.6f, 7.4f, 18.3f, 9.1f)
            curveTo(18f, 7.4f, 17.3f, 6.7f, 15.6f, 6.4f)
            curveTo(17.3f, 6.1f, 18f, 5.4f, 18.3f, 3.7f)
            close()
        }
    }

    /** Agent / 工具状态。 */
    val Tool: ImageVector by lazyIcon("Tool") {
        stroke { roundedRect(4f, 5.5f, 16f, 13f, 4f) }
        stroke { moveTo(8f, 5.5f); verticalLineTo(3.5f); horizontalLineTo(16f); verticalLineTo(5.5f) }
        dot(9f, 12f); dot(15f, 12f); stroke { moveTo(9f, 15.5f); horizontalLineTo(15f) }
    }
}

/** 线宽 1.5 / 24dp，对应 MIUIX 的均匀细线。 */
private const val STROKE_WIDTH = 1.5f

/** 资产内的固定绘制色；实际着色交给 `Icon(tint = ...)`。 */
private val IconColor = Color.Black

/** 单个图标的构建作用域：若干条描边路径 + 可选实心圆点。 */
private class MiuixIconScope(private val builder: ImageVector.Builder) {

    fun stroke(pathBuilder: PathBuilder.() -> Unit) {
        builder.path(
            fill = null,
            stroke = SolidColor(IconColor),
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
            pathBuilder = pathBuilder,
        )
    }

    /** 实心小圆点（描边线段画不出稳定的点，这里用填充圆）。 */
    fun dot(centerX: Float, centerY: Float, radius: Float = 0.95f) {
        builder.path(
            fill = SolidColor(IconColor),
            stroke = null,
            pathFillType = PathFillType.NonZero,
        ) {
            circle(centerX, centerY, radius)
        }
    }
}

/** 完整圆：两段半圆弧拼合。 */
private fun PathBuilder.circle(centerX: Float, centerY: Float, radius: Float) {
    moveTo(centerX - radius, centerY)
    arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = centerX + radius, y1 = centerY)
    arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = centerX - radius, y1 = centerY)
    close()
}

/** 圆角矩形（PathBuilder 没有现成 API，用四段圆弧手绘）。 */
private fun PathBuilder.roundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float) {
    moveTo(x + radius, y)
    lineTo(x + width - radius, y)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + width, y1 = y + radius)
    lineTo(x + width, y + height - radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + width - radius, y1 = y + height)
    lineTo(x + radius, y + height)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x, y1 = y + height - radius)
    lineTo(x, y + radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + radius, y1 = y)
    close()
}

/** 惰性构建一个 24x24 的 MIUIX 图标。 */
private fun lazyIcon(
    name: String,
    content: MiuixIconScope.() -> Unit,
): Lazy<ImageVector> = lazy(LazyThreadSafetyMode.NONE) {
    val builder = ImageVector.Builder(
        name = "MiuixIcons.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    MiuixIconScope(builder).content()
    builder.build()
}
