package com.flue.launcher.applist.nativeview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.motion.LocalFlueMotionProfile

@Composable
fun NativeAppListHost(
    content: NativeAppListContent,
    config: NativeAppListConfig,
    onCommand: (AppListCommand) -> Boolean,
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier,
    layoutStrategy: NativeAppListLayoutStrategy = LinearNativeAppListLayoutStrategy,
) {
    val motionProfile = LocalFlueMotionProfile.current
    val nativeConfig = config.copy(motionProfile = motionProfile)
    AndroidView(
        factory = { context ->
            NativeAppListView(context).apply {
                setLayoutStrategy(layoutStrategy)
                setConfig(nativeConfig)
            }
        },
        modifier = modifier,
        update = { view ->
            view.setLayoutStrategy(layoutStrategy)
            view.setConfig(nativeConfig)
            view.onCommand = onCommand
            view.onScrollToTop = onScrollToTop
            view.submit(content)
        },
        onRelease = NativeAppListView::release,
    )
}
