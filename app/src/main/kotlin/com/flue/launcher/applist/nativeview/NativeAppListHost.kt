package com.flue.launcher.applist.nativeview

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.viewinterop.AndroidView
import com.flue.launcher.applist.model.AppItemKey
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.motion.LocalFlueMotionProfile

@Composable
fun NativeAppListHost(
    content: NativeAppListContent,
    config: NativeAppListConfig,
    onCommand: (AppListCommand) -> Boolean,
    onCreateFolder: ((AppListCommand.CreateFolder) -> AppItemKey?)? = null,
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier,
    layoutStrategy: NativeAppListLayoutStrategy = LinearNativeAppListLayoutStrategy,
    /** Kept for call-site compatibility; legacy blur is intentionally not applied to rows. */
    transitionBlurRadiusDp: () -> Float = { 0f },
    transitionAlpha: () -> Float = { 1f },
    transitionBlurEnabled: Boolean = false,
    transitionBlurVisible: Boolean = false,
    transitionActive: Boolean = false,
    /**
     * 覆层（长按菜单 / 文件夹展开）打开时的列表背景模糊半径（dp），在绘制期逐帧采样。
     *
     * 为什么单独开一路、不复用上面的 `transitionBlur*`：那一路服务的是「应用打开/返回」
     * 那种整屏缩放转场，每一帧都在动、每次都要重采重糊。转场模糊在旧系统上代价太高，
     * 这个分支已经把它摘掉了。覆层模糊完全是另一种负载 —— 菜单展开后画面是**静止**的，
     * 一次捕获 + 一次烘焙就能复用整段菜单存活期，所以那笔性能账不成立。
     */
    overlayBlurRadiusDp: () -> Float = { 0f },
    overlayBlurEnabled: Boolean = false,
    overlayBlurVisible: Boolean = false,
    /** 0..1 的淡入进度，同时作为清晰/模糊层的混合权重。 */
    overlayBlurProgress: () -> Float = { if (overlayBlurVisible) 1f else 0f },
) {
    val motionProfile = LocalFlueMotionProfile.current
    val nativeConfig = remember(config, motionProfile) { config.copy(motionProfile = motionProfile) }
    val updateList: (NativeAppListView) -> Unit = { view ->
        view.setLayoutStrategy(layoutStrategy)
        view.setConfig(nativeConfig)
        view.onCommand = onCommand
        view.onCreateFolder = onCreateFolder
        view.onScrollToTop = onScrollToTop
        view.submit(content)
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        // Android 12 以下没有 RenderEffect，覆层背景模糊只能在这里补：
        // NativeAppListView.applyContentOverlayBlur() 第一行就在 31 以下返回，
        // 而 Compose 侧的 GraphicsLayer 录制拿不到 AndroidView 互操作内容
        // （本仓库在表盘那条路上已经踩过「interop 录到空层」的坑），所以只能把
        // 列表包进 NativeAppListBlurHost，由它在 Android 视图层捕获 + 预烘焙。
        //
        // 用普通数组持有引用而不是 remember 一个 State：绘制期写 State 会让整棵
        // 列表在动画每一帧重组 + 重新布局。
        val reference = remember { arrayOfNulls<NativeAppListBlurHost>(1) }
        AndroidView(
            factory = { context -> NativeAppListBlurHost(context).also { reference[0] = it } },
            modifier = modifier.drawWithContent {
                // 只有覆层在场时才逐帧采样宿主。关闭状态一次都不碰它 ——
                // 既没有多余重绘，也不会在列表滚动/转场的每一帧上多走一遍状态机。
                // 关闭所需的重置由下面 update 里的 setOverlayBlurFrameSource(visible=false) 负责。
                if (overlayBlurVisible) {
                    reference[0]?.updateBlurFrame(
                        overlayBlurRadiusDp(),
                        1f,
                        overlayBlurEnabled,
                        true,
                        overlayBlurProgress(),
                    )
                }
                drawContent()
            },
            update = { host ->
                updateList(host.contentView)
                host.setOverlayBlurFrameSource(
                    kernelRadiusDp = overlayBlurRadiusDp(),
                    blend = overlayBlurProgress,
                    enabled = overlayBlurEnabled,
                    visible = overlayBlurVisible,
                )
            },
            onRelease = { host ->
                reference[0] = null
                host.release()
            },
        )
        return
    }
    AndroidView(
        factory = { context ->
            NativeAppListView(context).apply {
                setLayoutStrategy(layoutStrategy)
                setConfig(nativeConfig)
            }
        },
        modifier = modifier,
        update = updateList,
        onRelease = NativeAppListView::release,
    )
}
