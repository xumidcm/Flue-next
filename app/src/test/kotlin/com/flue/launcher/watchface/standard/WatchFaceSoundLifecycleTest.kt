package com.flue.launcher.watchface.standard

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.media.MediaPlayer
import android.os.Looper
import android.os.PowerManager
import android.view.View
import com.flue.launcher.watchface.WatchFaceDescriptor
import com.flue.launcher.watchface.WatchFaceType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
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
class WatchFaceSoundLifecycleTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun withView(block: (Activity, WatchmakerWatchFaceView, WatchFaceSoundPlayback) -> Unit) {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            "${com.flue.launcher.BuildConfig.APPLICATION_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        shadowOf(activity.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        val view = WatchmakerWatchFaceView(activity)
        activity.setContentView(view)
        controller.visible()
        val exactSize = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY)
        activity.window.decorView.measure(exactSize, exactSize)
        activity.window.decorView.layout(0, 0, 320, 320)
        view.setFaceVisible(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertTrue("The playback fixture must be attached", view.isAttachedToWindow)
        // A manifest-free Robolectric Activity has no window-server visibility
        // transaction. Simulate that framework transaction on the attached root;
        // the production sound eligibility checks still run unchanged.
        val attachInfo = org.robolectric.util.ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        org.robolectric.util.ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
        view.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals("The playback fixture must have a visible window", View.VISIBLE, view.windowVisibility)
        val playback = field(view, "faceSoundPlayback") as WatchFaceSoundPlayback
        try { block(activity, view, playback) } finally {
            view.dispose()
            controller.pause().stop().destroy()
        }
    }

    private fun start(playback: WatchFaceSoundPlayback, prepareDelayMs: Int = 0): MediaPlayer {
        val file = temporaryFolder.newFile("sound-${System.nanoTime()}.mp3")
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), ShadowMediaPlayer.MediaInfo(60_000, prepareDelayMs))
        playback.play(file)
        return requireNotNull(playback.currentPlayer)
    }

    @Test fun everyHostVisibilityAndPreviewStopPathReleasesPlayingSound() {
        val stopActions: List<(Activity, WatchmakerWatchFaceView) -> Unit> = listOf(
            { _, view -> view.setFaceVisible(false) },
            { _, view -> view.setEditorPreviewPlaying(false) },
            { activity, _ -> activity.setContentView(View(activity)) },
            { _, view ->
                view.javaClass.getDeclaredField("descriptorKey").apply { isAccessible = true }.set(view, "sound-test")
                view.bind(WatchFaceDescriptor("sound-test", WatchFaceType.WATCHMAKER, "Sound test"),
                    0, false, null, null, { _, _ -> })
            },
        )
        stopActions.forEach { stop -> withView { activity, view, playback ->
            val player = start(playback)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(player.isPlaying)
            stop(activity, view)
            assertNull(playback.currentPlayer)
            assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
        } }
    }

    @Test fun delayedPrepareCannotStartAfterViewBecomesHidden() = withView { _, view, playback ->
        val player = start(playback, 500)
        view.setFaceVisible(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(playback.currentPlayer)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
    }

    @Test fun callbackRechecksEligibilityEvenWithoutExplicitStop() {
        var eligible = true
        val playback = WatchFaceSoundPlayback { eligible }
        val player = start(playback, 500)
        eligible = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(playback.currentPlayer)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
    }

    @Test fun replacementCannotBeStartedOrReleasedByOlderPrepare() {
        val playback = WatchFaceSoundPlayback { true }
        val previous = start(playback, 500)
        val replacement = start(playback, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(previous).state)
        assertSame(replacement, playback.currentPlayer)
        assertTrue(replacement.isPlaying)
        playback.stop()
    }

    @Test fun physicalWakeTriggersBrightWithoutAodWhilePageVisibilityDoesNot() = withView { activity, view, _ ->
        val initialEpoch = field(view, "textAnimationStartedAtUptimeMs")
        assertEquals(WatchTextAnimationTrigger.LOAD, field(view, "textAnimationTrigger"))
        view.setFaceVisible(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        view.setFaceVisible(true)
        assertEquals(initialEpoch, field(view, "textAnimationStartedAtUptimeMs"))
        assertEquals(WatchTextAnimationTrigger.LOAD, field(view, "textAnimationTrigger"))
        activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WatchTextAnimationTrigger.BRIGHT, field(view, "textAnimationTrigger"))
        assertNotEquals(initialEpoch, field(view, "textAnimationStartedAtUptimeMs"))
        val wakeEpoch = field(view, "textAnimationStartedAtUptimeMs")
        activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(wakeEpoch, field(view, "textAnimationStartedAtUptimeMs"))
    }

    private fun field(view: WatchmakerWatchFaceView, name: String): Any? =
        view.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(view)
}
