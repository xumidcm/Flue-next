package com.flue.launcher.applist.nativeview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.flue.launcher.applist.model.AppListCommand
import com.flue.launcher.applist.model.AppListSnapshot

@Composable
fun NativeAppListHost(
    snapshot: AppListSnapshot,
    onCommand: (AppListCommand) -> Unit,
    modifier: Modifier = Modifier,
    layoutStrategy: NativeAppListLayoutStrategy = LinearNativeAppListLayoutStrategy,
) {
    AndroidView(
        factory = { context ->
            NativeAppListView(context).apply {
                setLayoutStrategy(layoutStrategy)
            }
        },
        modifier = modifier,
        update = { view ->
            view.setLayoutStrategy(layoutStrategy)
            view.onCommand = onCommand
            view.submitSnapshot(snapshot)
        },
        onRelease = NativeAppListView::release,
    )
}
