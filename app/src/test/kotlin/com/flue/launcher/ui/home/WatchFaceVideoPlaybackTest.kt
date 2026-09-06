package com.flue.launcher.ui.home

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.flue.launcher.watchface.BUILT_IN_VIDEO_WATCHFACE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchFaceVideoPlaybackTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `opening the app list pauses the same video surface and returning resumes it`() {
        val faceVisible = mutableStateOf(true)
        val videoPath = temporaryFolder.newFile("watchface.mp4").absolutePath
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(videoPath),
            ShadowMediaPlayer.MediaInfo(60_000, 0),
        )
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            "com.flue.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val surfaceTexture = SurfaceTexture(0)
        try {
            activity.setContent {
                WatchFaceLayer(
                    watchFaceId = BUILT_IN_VIDEO_WATCHFACE_ID,
                    videoPath = videoPath,
                    isFaceVisible = faceVisible.value,
                    showStatusIndicators = false,
                    showBottomFade = false,
                )
            }
            settle()
            val root = activity.window.decorView
            val originalSurface = findVideoSurface(root)
            assertNotNull("The live video surface must be mounted", originalSurface)
            val host = originalSurface!!.parent as View
            // Robolectric has no real compositor to deliver the surface callback.
            originalSurface.surfaceTextureListener!!.onSurfaceTextureAvailable(surfaceTexture, 320, 320)
            settle()
            val player = requireNotNull(currentPlayer(host))
            assertTrue(player.isPlaying)
            assertPlaybackRequested(host, true)

            repeat(3) {
                faceVisible.value = false
                settle()
                assertSame("Opening the list must retain the decoded video surface", originalSurface, findVideoSurface(root))
                assertTrue(host.isAttachedToWindow)
                assertPlaybackRequested(host, false)
                assertSame(player, currentPlayer(host))
                assertFalse(player.isPlaying)
                val pausedPosition = player.currentPosition
                settle()
                assertEquals(pausedPosition, player.currentPosition)

                faceVisible.value = true
                settle()
                assertSame("Returning must resume the existing surface", originalSurface, findVideoSurface(root))
                assertPlaybackRequested(host, true)
                assertSame(player, currentPlayer(host))
                assertTrue(player.isPlaying)
                assertTrue("Playback must continue from the paused position", player.currentPosition > pausedPosition)
            }
            activity.setContent {}
            settle()
            assertEquals("Leaving the composition still releases the decoder", ShadowMediaPlayer.State.END, shadowOf(player).state)
        } finally {
            activity.setContent {}
            settle()
            controller.pause().stop().destroy()
            surfaceTexture.release()
            settle()
        }
    }

    @Test
    fun `a static video preview does not mount a player`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent {
                BuiltInWatchFacePreview(
                    watchFaceId = BUILT_IN_VIDEO_WATCHFACE_ID,
                    playVideo = false,
                )
            }
            settle()
            assertEquals(null, findVideoSurface(controller.get().window.decorView))
        } finally {
            controller.get().setContent {}
            settle()
            controller.pause().stop().destroy()
            settle()
        }
    }

    private fun findVideoSurface(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findVideoSurface(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun assertPlaybackRequested(host: View, expected: Boolean) {
        val field = host.javaClass.getDeclaredField("shouldPlay").apply { isAccessible = true }
        assertEquals("Visibility must control playback without unmounting the host", expected, field.getBoolean(host))
    }

    private fun currentPlayer(host: View): MediaPlayer? =
        host.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(host) as MediaPlayer?

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }
}
