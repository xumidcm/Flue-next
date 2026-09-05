package com.flue.launcher.ui.store

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchStorePageStackTest {
    @Test
    fun `comment window hides for a child page and is restored with its parent`() {
        val navigation = WatchStoreNavigator()
        navigation.push(WatchStoreRoute.Detail("comments"))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent {
                Box {
                    WatchStorePageStack(navigation) { page ->
                        if (page.route is WatchStoreRoute.Detail) {
                            WatchStoreDialog(onDismissRequest = {}) { BasicText("Comments") }
                        }
                    }
                }
            }
            settle()
            val comments = ShadowDialog.getLatestDialog()
            assertTrue(comments.isShowing)

            navigation.push(WatchStoreRoute.Creator("commenter"))
            settle()
            assertFalse(comments.isShowing)
            navigation.pop()
            settle()
            assertTrue(ShadowDialog.getLatestDialog().isShowing)
        } finally {
            controller.pause().stop().destroy()
            settle()
        }
    }

    @Test
    fun `real page host preserves parents and only the top page handles system back`() {
        val navigation = WatchStoreNavigator()
        navigation.push(WatchStoreRoute.Detail("comments"))
        val detail = navigation.current
        val instances = mutableMapOf<Long, Any>()
        val active = mutableMapOf<Long, Boolean>()
        val backCalls = mutableListOf<Long>()
        val refreshes = mutableMapOf<Long, Int>()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            activity.setContent {
                Box {
                    WatchStorePageStack(navigation) { page ->
                        val instance = remember { Any() }
                        val isActive = LocalWatchStorePageActive.current
                        WatchStorePageEffect(page.id) {
                            refreshes[page.id] = (refreshes[page.id] ?: 0) + 1
                        }
                        SideEffect {
                            instances[page.id] = instance
                            active[page.id] = isActive
                        }
                        DisposableEffect(page.id) {
                            onDispose {
                                instances.remove(page.id)
                                active.remove(page.id)
                            }
                        }
                        WatchStoreBackHandler {
                            backCalls += page.id
                            navigation.pop()
                        }
                    }
                }
            }
            settle()
            assertTrue(active[detail.id] == true)
            assertEquals(1, refreshes[detail.id])
            val originalDetailState = instances.getValue(detail.id)

            navigation.push(WatchStoreRoute.Creator("commenter"))
            val creator = navigation.current
            settle()
            assertFalse(active.getValue(detail.id))
            assertEquals(1, refreshes[detail.id])
            assertTrue(active.getValue(creator.id))
            assertSame(originalDetailState, instances[detail.id])

            navigation.push(WatchStoreRoute.MyUploads(startInLoginForm = true))
            val login = navigation.current
            settle()
            assertFalse(active.getValue(creator.id))
            assertTrue(active.getValue(login.id))
            activity.onBackPressedDispatcher.onBackPressed()
            settle()
            assertEquals(listOf(login.id), backCalls)
            assertEquals(creator, navigation.current)
            assertFalse(instances.containsKey(login.id))

            activity.onBackPressedDispatcher.onBackPressed()
            settle()
            assertEquals(listOf(login.id, creator.id), backCalls)
            assertEquals(detail, navigation.current)
            assertTrue(active.getValue(detail.id))
            assertSame(originalDetailState, instances[detail.id])
            assertEquals(2, refreshes[detail.id])
        } finally {
            controller.pause().stop().destroy()
            settle()
        }
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }
}
