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
    /** Sampled in draw, so the list uses the same spring/gesture frame as its parent layer. */
    transitionBlurRadiusDp: () -> Float = { 0f },
    transitionAlpha: () -> Float = { 1f },
    transitionBlurEnabled: Boolean = false,
    transitionBlurVisible: Boolean = false,
    transitionActive: Boolean = false,
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
        // Legacy blur already has its own sampled bitmap. Without that effect,
        // cache rasterization so transforms do not replay every icon and shadow.
        view.setTransitionRasterCacheEnabled(
            // The legacy blur target samples the cached child display list;
            // keeping the hardware layer off on API 27-30 made the parent
            // scale replay every icon and shadow on every transition frame.
            // Scroll/content invalidation still drops and rebuilds this layer.
            transitionActive && transitionBlurVisible,
        )
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        // Remember a plain holder: drawing must not write Compose state and cause
        // configuration or layout work on each animation frame.
        val reference = remember { arrayOfNulls<NativeAppListBlurHost>(1) }
        AndroidView(
            factory = { context -> NativeAppListBlurHost(context).also { reference[0] = it } },
            modifier = modifier.drawWithContent {
                reference[0]?.updateBlurFrame(
                    transitionBlurRadiusDp(), transitionAlpha(),
                    transitionBlurEnabled, transitionBlurVisible,
                )
                drawContent()
            },
            update = { host ->
                updateList(host.contentView)
                host.setBlurFrameSource(
                    transitionBlurRadiusDp, transitionAlpha,
                    transitionBlurEnabled, transitionBlurVisible,
                )
            },
            onRelease = { host -> reference[0] = null; host.release() },
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
