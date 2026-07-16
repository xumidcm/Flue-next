package com.flue.launcher.applist.nativeview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.flue.launcher.applist.model.AppListCommand

@Composable
fun NativeAppListHost(
    content: NativeAppListContent,
    config: NativeAppListConfig,
    onCommand: (AppListCommand) -> Unit,
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier,
    layoutStrategy: NativeAppListLayoutStrategy = LinearNativeAppListLayoutStrategy,
) {
    AndroidView(
        factory = { context ->
            NativeAppListView(context).apply {
                setLayoutStrategy(layoutStrategy)
                setConfig(config)
            }
        },
        modifier = modifier,
        update = { view ->
            view.setLayoutStrategy(layoutStrategy)
            view.setConfig(config)
            view.onCommand = onCommand
            view.onScrollToTop = onScrollToTop
            view.submit(content)
        },
        onRelease = NativeAppListView::release,
    )
}
