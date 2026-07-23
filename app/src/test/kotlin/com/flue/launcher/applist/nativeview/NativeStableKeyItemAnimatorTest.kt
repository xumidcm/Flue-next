package com.flue.launcher.applist.nativeview

import android.app.Activity
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeStableKeyItemAnimatorTest {
    @Test
    fun `move starts from previous geometry and one batch settles at zero`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val item = View(activity)
        activity.setContentView(item)
        val holder = TestHolder(item)
        val animator = NativeStableKeyItemAnimator(moveDurationMillis = 180L)
        val pre = RecyclerView.ItemAnimator.ItemHolderInfo().apply {
            left = 0
            top = 0
            right = 100
            bottom = 40
        }
        val post = RecyclerView.ItemAnimator.ItemHolderInfo().apply {
            left = 0
            top = 40
            right = 100
            bottom = 80
        }

        assertTrue(animator.animatePersistence(holder, pre, post))
        assertEquals(-40f, item.translationY, 0f)
        animator.runPendingAnimations()
        assertTrue(animator.isRunning)
        assertEquals(180L, animator.moveDuration)

        // Robolectric drains ValueAnimator frames as one task; the production
        // interpolation path is covered by the configured duration and the
        // active-before-idle/final-after-idle lifecycle assertions.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(190L))

        assertEquals(0f, item.translationX, 0f)
        assertEquals(0f, item.translationY, 0f)
        assertFalse(animator.isRunning)
        activity.finish()
    }

    @Test
    fun `ending animations resets every pending and active holder`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val first = TestHolder(View(activity))
        val second = TestHolder(View(activity))
        val animator = NativeStableKeyItemAnimator(moveDurationMillis = 180L)

        animator.animatePersistence(first, info(top = 0), info(top = 40))
        animator.animatePersistence(second, info(top = 40), info(top = 80))
        animator.runPendingAnimations()
        animator.endAnimations()

        assertEquals(0f, first.itemView.translationY, 0f)
        assertEquals(0f, second.itemView.translationY, 0f)
        assertFalse(animator.isRunning)
        activity.finish()
    }

    @Test
    fun `move frame callback observes translations after the batch update`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val item = View(activity)
        activity.setContentView(item)
        val observedTranslations = mutableListOf<Float>()
        val animator = NativeStableKeyItemAnimator(
            moveDurationMillis = 180L,
            onMoveFrame = { observedTranslations += item.translationY },
        )

        assertTrue(
            animator.animatePersistence(
                TestHolder(item),
                info(top = 0),
                info(top = 40),
            ),
        )
        animator.runPendingAnimations()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(190L))

        assertTrue(observedTranslations.isNotEmpty())
        assertTrue(observedTranslations.all { it in -40f..0f })
        assertEquals(0f, observedTranslations.last(), 0f)
        activity.finish()
    }

    @Test
    fun `dragged holder is excluded so animator cannot steal pointer translation`() {
        val holder = TestHolder(View(RuntimeEnvironment.getApplication())).apply {
            itemView.translationY = 27f
        }
        val animator = NativeStableKeyItemAnimator(
            moveDurationMillis = 180L,
            shouldAnimateHolder = { candidate -> candidate !== holder },
        )

        assertFalse(animator.animatePersistence(holder, info(top = 0), info(top = 40)))
        assertEquals(27f, holder.itemView.translationY, 0f)
    }

    private fun info(top: Int) = RecyclerView.ItemAnimator.ItemHolderInfo().apply {
        left = 0
        this.top = top
        right = 100
        bottom = top + 40
    }

    private class TestHolder(view: View) : RecyclerView.ViewHolder(view)
}
