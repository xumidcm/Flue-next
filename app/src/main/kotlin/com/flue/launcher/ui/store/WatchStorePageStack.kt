package com.flue.launcher.ui.store

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flue.launcher.STORE_LAYER_ENTER
import com.flue.launcher.STORE_LAYER_EXIT
import com.flue.launcher.blockPointerPassThrough
import kotlinx.coroutines.CoroutineScope

internal val LocalWatchStorePageActive = staticCompositionLocalOf { true }

/** Covered pages retain their state, but must not intercept back or display modal windows. */
@Composable
internal fun WatchStoreBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled = enabled && LocalWatchStorePageActive.current, onBack = onBack)
}

@Composable
internal fun WatchStoreDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    if (LocalWatchStorePageActive.current) {
        Dialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
    }
}

/** Refresh visible data on return, without disposing drafts, scroll positions or the page itself. */
@Composable
internal fun WatchStorePageEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val active = LocalWatchStorePageActive.current
    LaunchedEffect(active, *keys) {
        if (active) block()
    }
}

@Composable
internal fun WatchStorePageStack(
    navigation: WatchStoreNavigator,
    content: @Composable (WatchStorePageEntry) -> Unit,
) {
    var retained by remember(navigation) { mutableStateOf(emptyList<WatchStorePageEntry>()) }
    val pages = (retained + navigation.entries.drop(1)).distinctBy { it.id }.sortedBy { it.id }
    SideEffect { retained = pages }

    pages.forEachIndexed { depth, entry ->
        key(entry.id) {
            val present = entry in navigation.entries
            val transition = remember { MutableTransitionState(false) }
            transition.targetState = present
            LaunchedEffect(present, transition.isIdle, transition.currentState) {
                if (!present && transition.isIdle && !transition.currentState) {
                    retained = retained.filterNot { it.id == entry.id }
                }
            }
            AnimatedVisibility(
                visibleState = transition,
                modifier = Modifier.fillMaxSize().blockPointerPassThrough().zIndex(depth + 1f),
                enter = STORE_LAYER_ENTER,
                exit = STORE_LAYER_EXIT,
            ) {
                CompositionLocalProvider(LocalWatchStorePageActive provides navigation.isCurrent(entry)) {
                    content(entry)
                }
            }
        }
    }
}
